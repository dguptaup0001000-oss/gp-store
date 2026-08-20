package com.gpstore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One Hindi/Hinglish grocery word and the English word to search for
 * instead - see V12__add_search_synonyms.sql for why this is a table of
 * concepts rather than a list of spellings.
 */
@Entity
@Table(name = "search_synonyms")
public class SearchSynonym {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String term;

    @Column(name = "canonical_term", nullable = false, length = 64)
    private String canonicalTerm;

    @Column(nullable = false)
    private Boolean active = true;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTerm() {
        return term;
    }

    public void setTerm(String term) {
        this.term = term;
    }

    public String getCanonicalTerm() {
        return canonicalTerm;
    }

    public void setCanonicalTerm(String canonicalTerm) {
        this.canonicalTerm = canonicalTerm;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
