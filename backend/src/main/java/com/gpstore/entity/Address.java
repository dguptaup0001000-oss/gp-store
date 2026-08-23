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
    private Boolean subzoneLocked = Boolean.FALSE;

    public Address() {
    }

    @PrePersist
    @PreUpdate
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
}