package com.gpstore.territory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * A subzone's outline, and the one question worth asking of it: is this
 * customer inside?
 *
 * WHY THIS IS NOT PostGIS. CI and production run the plain postgres:17 image, which has no
 * PostGIS, and turning the extension on in Supabase is a change to a live
 * production database. What it would buy is a spatial index over twenty-six
 * polygons of a few dozen vertices each - a data set that fits in a few
 * kilobytes and that this class scans in microseconds. The boundary is stored
 * as JSON, so if the map ever grows to the point where an index earns its
 * keep, that is a data migration rather than a redesign.
 *
 * THE ALGORITHM is the standard even-odd ray cast: draw a ray from the point
 * to infinity and count boundary crossings. Odd means inside. It handles
 * concave outlines, which matters more here than it might sound - a territory
 * bounded by a river bend or wrapping around a walled factory is concave, and
 * a convex-hull test would swallow the neighbouring territory whole.
 *
 * COORDINATES ARE TREATED AS A FLAT PLANE, deliberately. Over a delivery area
 * a scooter can cover, the error from ignoring the Earth's curvature is far
 * below the precision of the boundary itself: nobody draws a territory edge
 * to the metre, they draw it down the middle of a road. Distances - where
 * curvature does matter - go through DeliveryEstimateService's Haversine
 * instead.
 *
 * EDGE CASES, and why they are decided the way they are. A point exactly on
 * an edge, or on a shared vertex between two territories, must land in
 * EXACTLY ONE of them or a house on a boundary road would flip between riders
 * on consecutive orders. The half-open rule below ({@code >} on one end,
 * {@code <=} on the other) is what guarantees that: each edge counts its
 * lower endpoint and not its upper, so two territories sharing that vertex
 * cannot both claim it.
 */
public final class TerritoryPolygon {

    /** Fewer than three points cannot enclose anything. */
    private static final int MIN_VERTICES = 3;

    private final double[] lat;
    private final double[] lng;

    /** Bounding box, computed once. See {@link #contains}. */
    private final double minLat;
    private final double maxLat;
    private final double minLng;
    private final double maxLng;

    private TerritoryPolygon(double[] lat, double[] lng) {
        this.lat = lat;
        this.lng = lng;

        double nLat = Double.MAX_VALUE;
        double xLat = -Double.MAX_VALUE;
        double nLng = Double.MAX_VALUE;
        double xLng = -Double.MAX_VALUE;
        for (int i = 0; i < lat.length; i++) {
            nLat = Math.min(nLat, lat[i]);
            xLat = Math.max(xLat, lat[i]);
            nLng = Math.min(nLng, lng[i]);
            xLng = Math.max(xLng, lng[i]);
        }
        this.minLat = nLat;
        this.maxLat = xLat;
        this.minLng = nLng;
        this.maxLng = xLng;
    }

    public int vertexCount() {
        return lat.length;
    }

    /**
     * Parses {@code [[lat,lng],[lat,lng],...]}.
     *
     * Returns null rather than throwing for anything it cannot read - a
     * malformed boundary, an empty array, a ring with two points. The caller
     * is a resolver that must FAIL CLOSED: a territory whose outline cannot be
     * read matches nothing, which surfaces as "we do not know this address's
     * territory" rather than as a rider being sent somewhere on the strength
     * of a half-parsed polygon.
     */
    public static TerritoryPolygon parse(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isArray()) {
                return null;
            }

            List<double[]> points = new ArrayList<>();
            for (JsonNode pair : root) {
                if (!pair.isArray() || pair.size() < 2) {
                    return null;
                }
                JsonNode a = pair.get(0);
                JsonNode b = pair.get(1);
                if (!a.isNumber() || !b.isNumber()) {
                    return null;
                }
                points.add(new double[]{a.asDouble(), b.asDouble()});
            }

            // A ring that repeats its first point at the end is the GeoJSON
            // convention and is perfectly valid input; the ray cast closes
            // the ring itself, so the duplicate would just be a zero-length
            // edge. Dropping it keeps the crossing count honest.
            int size = points.size();
            if (size >= 2) {
                double[] first = points.get(0);
                double[] last = points.get(size - 1);
                if (first[0] == last[0] && first[1] == last[1]) {
                    points.remove(size - 1);
                }
            }

            if (points.size() < MIN_VERTICES) {
                return null;
            }

            double[] lat = new double[points.size()];
            double[] lng = new double[points.size()];
            for (int i = 0; i < points.size(); i++) {
                lat[i] = points.get(i)[0];
                lng[i] = points.get(i)[1];
            }
            return new TerritoryPolygon(lat, lng);
        } catch (Exception e) {
            // Deliberately broad and deliberately silent here: the resolver
            // logs the subzone this came from, which is the useful half of
            // the message. A boundary that will not parse is a configuration
            // problem, not a request problem, and must never propagate into a
            // customer's checkout.
            return null;
        }
    }

    /**
     * True when the point falls inside this territory.
     *
     * The bounding-box check first is not premature optimisation - it is what
     * makes scanning all 26 territories cheap. A point is inside at most one
     * of them, so 25 of the 26 tests should cost four comparisons rather than
     * a full ring walk.
     */
    public boolean contains(double pointLat, double pointLng) {
        if (pointLat < minLat || pointLat > maxLat || pointLng < minLng || pointLng > maxLng) {
            return false;
        }

        boolean inside = false;
        int n = lat.length;

        for (int i = 0, j = n - 1; i < n; j = i++) {
            // Half-open edge test: this edge owns its lower endpoint and not
            // its upper one. Two territories meeting at a vertex therefore
            // cannot both claim a house sitting exactly on it, which is what
            // keeps a boundary-road address with one rider instead of
            // alternating between two.
            boolean straddles = (lng[i] > pointLng) != (lng[j] > pointLng);
            if (!straddles) {
                continue;
            }

            // Latitude of this edge at the point's longitude. The ray runs in
            // the -latitude direction, so a crossing counts when the edge is
            // on the far side of the point.
            double crossingLat =
                    (lat[j] - lat[i]) * (pointLng - lng[i]) / (lng[j] - lng[i]) + lat[i];

            if (pointLat < crossingLat) {
                inside = !inside;
            }
        }

        return inside;
    }

    /**
     * Centroid of the vertices, used only to rank candidate territories by
     * rough proximity - never to decide membership.
     *
     * The vertex average, not the true area centroid: it is stabler for the
     * lopsided outlines road-following boundaries produce, and for a ranking
     * signal the difference is noise.
     */
    public double[] approximateCentre() {
        double sumLat = 0;
        double sumLng = 0;
        for (int i = 0; i < lat.length; i++) {
            sumLat += lat[i];
            sumLng += lng[i];
        }
        return new double[]{sumLat / lat.length, sumLng / lng.length};
    }

    /**
     * True when the two outlines share interior, not merely a boundary road.
     * Adjacent territories that meet at an edge must still be allowed.
     */
    public boolean overlapsInterior(TerritoryPolygon other) {
        if (other == null) {
            return false;
        }
        if (maxLat < other.minLat || minLat > other.maxLat
                || maxLng < other.minLng || minLng > other.maxLng) {
            return false;
        }
        double[] here = approximateCentre();
        double[] there = other.approximateCentre();
        return other.contains(here[0], here[1]) || contains(there[0], there[1]);
    }
}
