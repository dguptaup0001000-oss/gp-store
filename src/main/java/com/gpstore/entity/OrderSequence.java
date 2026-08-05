package com.gpstore.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_sequence")
public class OrderSequence {
    @Id
    private String datePrefix;
    private Integer seq;
    public String getDatePrefix() { return datePrefix; }
    public void setDatePrefix(String datePrefix) { this.datePrefix = datePrefix; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
}
