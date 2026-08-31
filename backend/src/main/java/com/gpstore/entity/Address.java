package com.gpstore.entity;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "addresses")
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    @JsonIgnore
    private Customer customer;

    private String fullName;

    private String mobileNumber;

    private String houseNo;

    private String area;

    private String landmark;

    private String city;

    private String district;

    private String state;

    private String pincode;

    private String country;

    // Needed to compute a distance-based delivery estimate. Nullable because
    // older addresses won't have this until re-saved with coordinates.
    private Double latitude;

    private Double longitude;

    private Boolean defaultAddress;

    // ------------------------------------------------ map-confirmed detail
    //
    // Added in V34. Every one of these is nullable, because every address
    // saved before this work has none of them and must keep working.
    //
    // COLUMN LENGTHS MATCH V34 EXACTLY. Production runs ddl-auto=validate, so
    // a @Column(length = ...) that disagrees with the migration is not a
    // warning - it is the application refusing to start.

    /** Home / Work / Shop / Other. The customer's own word for this place. */
    @Column(length = 20)
    private String label;

    @Column(name = "building_name", length = 200)
    private String buildingName;

    @Column(length = 50)
    private String floor;

    @Column(length = 200)
    private String street;

    /**
     * The provider's own one-line rendering of this place.
     *
     * Stored rather than rebuilt on read: it is what the customer actually
     * saw and confirmed on the map, and reassembling it later from the parts
     * would produce a different string than the one they agreed to.
     */
    @Column(name = "formatted_address", length = 500)
    private String formattedAddress;

    /** "Enter from the lane beside the medical store." Shown to the rider. */
    @Column(name = "delivery_instructions", length = 500)
    private String deliveryInstructions;

    /**
     * Metres of uncertainty reported by the device when the pin was confirmed.
     *
     * Null for a pin placed by search or dragged by hand - those have no
     * device accuracy, and recording a fabricated one would make a hand-placed
     * pin look GPS-verified.
     */
    @Column(name = "location_accuracy")
    private Double locationAccuracy;

    /** The provider's stable id for this place, where one was selected. */
    @Column(name = "place_id", length = 255)
    private String placeId;

    /** Which provider produced the coordinates. Never trusted from a client. */
    @Column(name = "geocoding_provider", length = 40)
    private String geocodingProvider;

    @Column(name = "created_at")
    private java.time.LocalDateTime createdAt;

    @Column(name = "updated_at")
    private java.time.LocalDateTime updatedAt;

    /**
     * When the customer last confirmed this pin on a map.
     *
     * Null means these coordinates have never been through the map
     * confirmation step - true of every address saved before this work, and
     * the honest thing to show a rider about a location nobody has verified.
     */
    @Column(name = "confirmed_at")
    private java.time.LocalDateTime confirmedAt;

    /**
     * The permanent delivery territory this address belongs to.
     *
     * Stamped once, from the coordinates, when the address is saved - never
     * recomputed per order. Two reasons, and both matter.
     *
     * PERMANENCE. A customer who resolved to Z7B must still be Z7B next
     * month. If this were derived on every read, an administrator nudging a
     * boundary would silently move existing customers between riders, and the
     * territory knowledge the whole design is built on would quietly rot.
     *
     * COST. Checkout preview runs on every cart change. Keeping the
     * point-in-polygon test off that path means the territory system adds no
     * per-request database work at all.
     *
     * Null is a real and permitted state: an address saved before the
     * territory map existed, or one whose coordinates fall outside every
     * drawn subzone. TerritoryDispatchService treats it as "no territory
     * information" and says so, rather than guessing a subzone.
     */
    /*
     * @JsonIgnore, and this is about what a customer's phone should be sent
     * as much as about serialisation.
     *
     * AddressController returns this entity directly, so without the
     * annotation a DeliverySubzone travels with every address - and a
     * DeliverySubzone carries its polygon boundary, its zone, its assigned
     * DeliveryPartner (with that partner's name and phone number) and, one
     * hop further, its neighbour list. None of that is the customer's, and
     * all of it was being shipped to their phone the moment the first
     * territory was drawn.
     *
     * It was also the mechanism of a 500. neighbours is a lazy collection, so
     * with open-session-in-view off (see spring.jpa.open-in-view) Jackson
     * reaching it during serialisation raises LazyInitializationException.
     * Under open-session-in-view it did not throw - it ran the queries, which
     * is worse in every way except that nobody noticed.
     *
     * NOTHING READS IT FROM THE RESPONSE. The Flutter AddressModel does not
     * declare a subzone field at all, so this removes a value no client has
     * ever used. The territory itself is unchanged and still stamped on the
     * row - it is simply the server's business, which is where the dispatch
     * code reads it from.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subzone_id")
    private DeliverySubzone subzone;

    /**
     * True when an administrator placed this address in its subzone by hand.
     *
     * The map cannot know that a house sits on the wrong side of a line, or
     * that a gated colony's only gate opens into the next territory. A human
     * can. When they say so, nothing automatic may overwrite it - not a
     * coordinate update, not a boundary edit, not a bulk re-resolve.
     */
    // NOT declared nullable = false here, and that is deliberate. This column
    // is being ADDED to a table that already holds every customer's addresses,
    // and Postgres cannot add a NOT NULL column to a non-empty table without a
    // default - Hibernate's ddl-auto emits exactly that ALTER, it fails, and
    // Hibernate logs the failure and carries on, leaving the column missing
    // entirely. V19 is what makes it NOT NULL, by the add/backfill/alter route
    // that works on a populated table. The @PrePersist below is what actually
    // keeps the value non-null, since Hibernate binds an explicit NULL for an
    // unset field rather than letting the column DEFAULT apply.
    //
    // @JsonIgnore so a customer POST cannot lock themselves out of auto-stamp
    // (or pick a rider). Admin pinning goes through TerritoryAdminService.
    @JsonIgnore
    private Boolean subzoneLocked = Boolean.FALSE;

    public Address() {
    }

    @PrePersist
    void onCreate() {
        normaliseTerritoryFlags();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        normaliseTerritoryFlags();
        updatedAt = java.time.LocalDateTime.now();
    }

    void normaliseTerritoryFlags() {
        // Hibernate binds an explicit NULL for an unset field rather than
        // omitting the column, so a database DEFAULT never applies on insert.
        // The column is NOT NULL; this is what actually keeps it satisfied.
        if (subzoneLocked == null) {
            subzoneLocked = Boolean.FALSE;
        }
    }

    public DeliverySubzone getSubzone() {
        return subzone;
    }

    public void setSubzone(DeliverySubzone subzone) {
        this.subzone = subzone;
    }

    public Boolean getSubzoneLocked() {
        return subzoneLocked;
    }

    public void setSubzoneLocked(Boolean subzoneLocked) {
        this.subzoneLocked = subzoneLocked;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getMobileNumber() {
        return mobileNumber;
    }

    public void setMobileNumber(String mobileNumber) {
        this.mobileNumber = mobileNumber;
    }

    public String getHouseNo() {
        return houseNo;
    }

    public void setHouseNo(String houseNo) {
        this.houseNo = houseNo;
    }

    public String getArea() {
        return area;
    }

    public void setArea(String area) {
        this.area = area;
    }

    public String getLandmark() {
        return landmark;
    }

    public void setLandmark(String landmark) {
        this.landmark = landmark;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getDistrict() {
        return district;
    }

    public void setDistrict(String district) {
        this.district = district;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getPincode() {
        return pincode;
    }

    public void setPincode(String pincode) {
        this.pincode = pincode;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public Boolean getDefaultAddress() {
        return defaultAddress;
    }

    public void setDefaultAddress(Boolean defaultAddress) {
        this.defaultAddress = defaultAddress;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getBuildingName() { return buildingName; }
    public void setBuildingName(String buildingName) { this.buildingName = buildingName; }

    public String getFloor() { return floor; }
    public void setFloor(String floor) { this.floor = floor; }

    public String getStreet() { return street; }
    public void setStreet(String street) { this.street = street; }

    public String getFormattedAddress() { return formattedAddress; }
    public void setFormattedAddress(String formattedAddress) { this.formattedAddress = formattedAddress; }

    public String getDeliveryInstructions() { return deliveryInstructions; }
    public void setDeliveryInstructions(String deliveryInstructions) { this.deliveryInstructions = deliveryInstructions; }

    public Double getLocationAccuracy() { return locationAccuracy; }
    public void setLocationAccuracy(Double locationAccuracy) { this.locationAccuracy = locationAccuracy; }

    public String getPlaceId() { return placeId; }
    public void setPlaceId(String placeId) { this.placeId = placeId; }

    public String getGeocodingProvider() { return geocodingProvider; }
    public void setGeocodingProvider(String geocodingProvider) { this.geocodingProvider = geocodingProvider; }

    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime createdAt) { this.createdAt = createdAt; }

    public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public java.time.LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(java.time.LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
}
