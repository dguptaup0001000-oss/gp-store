package com.gpstore.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "delivery_partners")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryPartner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String mobile;

    private String vehicleType;

    private String vehicleNumber;

    private Boolean available;

    private Boolean active;

    // Live GPS position, pushed by the partner's own app every few seconds
    // while on a run. Null until their first location update - never assume
    // these are populated, especially for a partner who has never gone on
    // a run yet.
    private Double currentLatitude;

    private Double currentLongitude;

    private LocalDateTime locationUpdatedAt;

    // Links this operational roster record to the real Customer account
    // (role=DELIVERY_BOY) that lets this person actually log in - a
    // DeliveryPartner row alone was never a login identity, just a name on
    // a list. Hidden from JSON: this is an internal link, not something the
    // Flutter admin screens need to render.
    @OneToOne
    @JoinColumn(name = "account_customer_id")
    @JsonIgnore
    private Customer account;
}
