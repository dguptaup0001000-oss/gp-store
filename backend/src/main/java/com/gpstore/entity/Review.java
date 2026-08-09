package com.gpstore.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "reviews")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private Customer customer;

    // Deliberately left EAGER: ReviewController.getForProduct()/getMyReviews()
    // return raw Review entities with no @Transactional on the service methods,
    // so Jackson serializes after the session closes - LAZY here throws
    // LazyInitializationException on those endpoints. Revisit alongside a DTO
    // refactor of ReviewController, not in isolation.
    @ManyToOne
    private Product product;

    private Integer rating;

    private String comment;

    private LocalDateTime reviewDate;

    private Boolean active;
}