package com.gpstore.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
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

    private String mobileNumber;

    private String email;

    // WRITE_ONLY: the client can send a password when creating/updating, but it
    // NEVER comes back out in a JSON response - this was leaking the bcrypt
    // hash on every endpoint that returned a Customer object.
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    
    @Enumerated(EnumType.STRING)
private Role role;

private Boolean enabled;
    
    @OneToMany(mappedBy = "customer")
private List<Address> addresses;

@OneToOne(mappedBy = "customer", cascade = CascadeType.ALL)
private Cart cart;

  private Boolean verified;

private Boolean active;

public Cart getCart() {
    return cart;
}

public void setCart(Cart cart) {
    this.cart = cart;
}
    
}
