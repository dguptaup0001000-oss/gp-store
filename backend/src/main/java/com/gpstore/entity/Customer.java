package com.gpstore.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.gpstore.upload.CatalogImageUrlSerializer;
import java.util.List;

import jakarta.persistence.*;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter

@Entity
@Table(name = "customers")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fullName;

    // unique=true still allows multiple NULLs (an OTP-only account may have no
    // email yet; an email+password account created before phone verification
    // may have no mobile). Neither of these had a DB-level uniqueness
    // guarantee before - only application-level checks, which don't protect
    // against a race condition creating two accounts with the same
    // email/phone at the same instant.
    @Column(unique = true)
    private String mobileNumber;

    @Column(unique = true)
    private String email;

    // WRITE_ONLY: the client can send a password when creating/updating, but it
    // NEVER comes back out in a JSON response - this was leaking the bcrypt
    // hash on every endpoint that returned a Customer object.
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    
    @Enumerated(EnumType.STRING)
private Role role;

private Boolean enabled;
    
    // Same reasoning as cart below: addresses have their own dedicated
    // endpoint (GET /api/addresses/mine, see AddressController), and without
    // this every Customer/Profile JSON response (GET /me, admin customer
    // list, etc) was pulling the full lazy Address collection - GPS
    // coordinates, mobile numbers, full text - for no reason any client
    // actually needs.
    @JsonIgnore
    @OneToMany(mappedBy = "customer")
private List<Address> addresses;

// @OneToOne defaults to EAGER, so cart is always loaded alongside its
// Customer - and Cart.customer (the other side of this same relationship)
// has no reciprocal @JsonIgnore/@JsonBackReference either. Without this
// annotation, serializing ANY Customer with a cart infinite-loops:
// Customer -> cart -> Cart.customer -> Customer -> cart -> ... This is the
// exact same bug class already caught and fixed for Cart.items/CartItem.cart
// (see the comment there) and for Address.customer - this one side was
// missed. Cart data has its own dedicated endpoint (GET /api/carts/mine,
// see CartController) - nothing legitimately needs cart nested inside a
// Customer/Profile JSON response, so ignoring it here costs nothing.
@JsonIgnore
@OneToOne(mappedBy = "customer", cascade = CascadeType.ALL)
private Cart cart;

  private Boolean verified;

private Boolean active;

// The device token this account's app instance registered for push
// notifications (see CustomerController.updateMyFcmToken /
// PushNotificationService). Single-token-per-account for now - if the same
// person is logged in on two devices, only the most recently registered
// one receives pushes. Hidden from JSON: this is an internal delivery
// detail, never something a client needs to read back.
@JsonIgnore
private String fcmToken;

// The customer's own avatar, stored as a private-bucket reference rather
// than a URL - same convention as Category.imageUrl and
// ProductVariant.imageUrl, and for the same reason: a signed URL expires,
// so persisting one would leave broken images behind within the hour.
// CatalogImageUrlSerializer turns the stored ref into a fresh signed URL on
// the way out.
//
// Null for every account that has not set one, which is most of them. The
// app falls back to the initial-letter avatar it already draws, so nothing
// depends on this being present.
@JsonSerialize(using = CatalogImageUrlSerializer.class)
@Column(name = "profile_image_url")
private String profileImageUrl;

public Cart getCart() {
    return cart;
}

public void setCart(Cart cart) {
    this.cart = cart;
}
    
}
