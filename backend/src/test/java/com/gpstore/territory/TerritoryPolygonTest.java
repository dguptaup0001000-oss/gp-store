package com.gpstore.territory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The geometry the whole territory system rests on.
 *
 * Pure unit tests with no Spring and no database, because this is the one
 * piece where being wrong is invisible: a polygon test that is subtly off
 * does not throw, it just puts a house in the wrong territory, and nobody
 * finds out until a rider is sent across a river.
 */
class TerritoryPolygonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** A unit square with corners at (0,0) and (1,1). */
    private static final String SQUARE = "[[0,0],[0,1],[1,1],[1,0]]";

    /**
     * An L, carved out of the top-right of that square. The point (0.75, 0.75)
     * is inside the square's bounding box but OUTSIDE this shape - which is
     * the case a convex-hull test gets wrong and a ray cast gets right.
     */
    private static final String L_SHAPE = "[[0,0],[0,1],[0.5,1],[0.5,0.5],[1,0.5],[1,0]]";

    @Test
    @DisplayName("a point inside the outline is inside")
    void insideIsInside() {
        TerritoryPolygon polygon = TerritoryPolygon.parse(SQUARE, mapper);
        assertNotNull(polygon);
        assertTrue(polygon.contains(0.5, 0.5));
    }

    @Test
    @DisplayName("a point outside the outline is outside")
    void outsideIsOutside() {
        TerritoryPolygon polygon = TerritoryPolygon.parse(SQUARE, mapper);
        assertFalse(polygon.contains(1.5, 0.5));
        assertFalse(polygon.contains(0.5, 1.5));
        assertFalse(polygon.contains(-0.1, 0.5));
    }

    @Test
    @DisplayName("a concave territory does not swallow the notch cut out of it")
    void concaveShapesAreHandled() {
        // The reason this matters: territories that follow roads are rarely
        // convex. One wrapping around a walled factory or bending with a river
        // has exactly this shape, and a convex test would claim the factory -
        // and everything on the far side of it - for the wrong rider.
        TerritoryPolygon polygon = TerritoryPolygon.parse(L_SHAPE, mapper);
        assertNotNull(polygon);

        assertTrue(polygon.contains(0.25, 0.25), "the corner of the L is inside");
        assertTrue(polygon.contains(0.25, 0.75), "the tall arm is inside");
        assertTrue(polygon.contains(0.75, 0.25), "the wide arm is inside");
        assertFalse(polygon.contains(0.75, 0.75),
                "the notch is inside the bounding box but outside the shape");
    }

    @Test
    @DisplayName("two territories sharing a border claim a point on it exactly once")
    void aSharedBorderIsNotClaimedTwice() {
        // A house on a boundary road must not alternate between two riders on
        // consecutive orders. The half-open edge rule is what guarantees that,
        // and this is the assertion that would catch losing it.
        TerritoryPolygon west = TerritoryPolygon.parse("[[0,0],[0,1],[1,1],[1,0]]", mapper);
        TerritoryPolygon east = TerritoryPolygon.parse("[[0,1],[0,2],[1,2],[1,1]]", mapper);
        assertNotNull(west);
        assertNotNull(east);

        for (double lat = 0.1; lat < 1.0; lat += 0.1) {
            boolean inWest = west.contains(lat, 1.0);
            boolean inEast = east.contains(lat, 1.0);
            assertNotEquals(inWest, inEast,
                    "the point (" + lat + ", 1.0) sits on the shared border and must belong to "
                            + "exactly one territory, not both and not neither");
        }
    }

    @Test
    @DisplayName("a ring that repeats its first point is read the same as one that does not")
    void closedRingsAreAccepted() {
        // GeoJSON convention closes the ring. Most map editors emit it that
        // way, so refusing it - or counting the zero-length edge - would mean
        // half the boundaries an administrator pastes in behave differently
        // from the other half.
        TerritoryPolygon open = TerritoryPolygon.parse(SQUARE, mapper);
        TerritoryPolygon closed = TerritoryPolygon.parse("[[0,0],[0,1],[1,1],[1,0],[0,0]]", mapper);

        assertNotNull(closed);
        assertEquals(open.vertexCount(), closed.vertexCount());
        assertTrue(closed.contains(0.5, 0.5));
        assertFalse(closed.contains(2.0, 2.0));
    }

    @Test
    @DisplayName("anything unreadable parses to null rather than a half-built shape")
    void malformedBoundariesFailClosed() {
        // Every one of these must be null, not an empty polygon and not a
        // throw. The resolver treats null as "this territory matches nobody",
        // which surfaces as a visible FALLBACK assignment - whereas a
        // half-parsed shape would send a rider somewhere on the strength of
        // two of the four corners.
        assertNull(TerritoryPolygon.parse(null, mapper));
        assertNull(TerritoryPolygon.parse("", mapper));
        assertNull(TerritoryPolygon.parse("   ", mapper));
        assertNull(TerritoryPolygon.parse("not json at all", mapper));
        assertNull(TerritoryPolygon.parse("{\"type\":\"Polygon\"}", mapper), "an object, not a ring");
        assertNull(TerritoryPolygon.parse("[]", mapper), "empty");
        assertNull(TerritoryPolygon.parse("[[0,0],[0,1]]", mapper), "two points cannot enclose anything");
        assertNull(TerritoryPolygon.parse("[[0,0],[0,1],[1]]", mapper), "a point with one coordinate");
        assertNull(TerritoryPolygon.parse("[[0,0],[0,1],[\"a\",\"b\"]]", mapper), "non-numeric");
    }

    @Test
    @DisplayName("a realistic territory does not leak into its neighbour")
    void adjacentRealisticTerritoriesDoNotOverlap() {
        // Delhi-scale coordinates rather than a unit square, so this would
        // catch a precision mistake that only shows up at real latitudes -
        // the differences between adjacent boundary points here are in the
        // third decimal place, roughly a hundred metres.
        TerritoryPolygon north = TerritoryPolygon.parse(
                "[[28.620,77.200],[28.620,77.220],[28.640,77.220],[28.640,77.200]]", mapper);
        TerritoryPolygon south = TerritoryPolygon.parse(
                "[[28.600,77.200],[28.600,77.220],[28.620,77.220],[28.620,77.200]]", mapper);
        assertNotNull(north);
        assertNotNull(south);

        assertTrue(north.contains(28.630, 77.210));
        assertFalse(south.contains(28.630, 77.210));

        assertTrue(south.contains(28.610, 77.210));
        assertFalse(north.contains(28.610, 77.210));
    }

    @Test
    @DisplayName("adjacent territories are not treated as overlapping")
    void adjacentSquaresDoNotOverlapInterior() {
        TerritoryPolygon west = TerritoryPolygon.parse("[[0,0],[0,1],[1,1],[1,0]]", mapper);
        TerritoryPolygon east = TerritoryPolygon.parse("[[0,1],[0,2],[1,2],[1,1]]", mapper);
        assertFalse(west.overlapsInterior(east));
        assertFalse(east.overlapsInterior(west));
    }

    @Test
    @DisplayName("one square covering another is an overlap")
    void nestedSquaresOverlap() {
        TerritoryPolygon outer = TerritoryPolygon.parse("[[0,0],[0,4],[4,4],[4,0]]", mapper);
        TerritoryPolygon inner = TerritoryPolygon.parse("[[1,1],[1,2],[2,2],[2,1]]", mapper);
        assertTrue(outer.overlapsInterior(inner));
        assertTrue(inner.overlapsInterior(outer));
    }
}
