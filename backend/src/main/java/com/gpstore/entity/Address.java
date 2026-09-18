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
     * THE TERRITORY STAMP IS NOT HERE ANY MORE, and moving it was the fix for
     * a boundary bug rather than a tidy-up.
     *
     * <p>This row used to carry {@code subzone_id} and {@code subzone_locked}:
     * one territory, and one hand-placed pin, on a row that belongs to the
     * CUSTOMER. A customer who buys from two kiranas has one home and two
     * shops' maps over it, so the two columns could only ever hold one shop's
     * answer and the shops took it from each other - whoever saved, re-resolved
     * or pinned last won, one merchant's pin froze the address for everybody,
     * and a shop reading somebody else's stamp got nothing back and quietly
     * resolved live.
     *
     * <p>The answer now lives in {@code address_territory_stamps}, one row per
     * shop, shop-owned and therefore narrowed by the same tenant filter as the
     * territory itself. See {@link com.gpstore.territory.AddressTerritoryStamp}
     * and {@link com.gpstore.territory.AddressTerritory}; the columns stay on
     * the table for one release as the rollback path and are read by nothing.
     */

    public Address() {
    }

    @PrePersist
    void onCreate() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = java.time.LocalDateTime.now();
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
