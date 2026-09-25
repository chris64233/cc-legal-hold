package com.chris64233.cc.legalhold.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "retention_rule")
public class RetentionRule {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String category;

    @Column(nullable = false)
    private int minRetentionDays;

    protected RetentionRule() {
    }

    public RetentionRule(String category, int minRetentionDays) {
        this.id = UUID.randomUUID();
        this.category = category;
        this.minRetentionDays = minRetentionDays;
    }

    public UUID getId() {
        return id;
    }

    public String getCategory() {
        return category;
    }

    public int getMinRetentionDays() {
        return minRetentionDays;
    }

    public void setMinRetentionDays(int minRetentionDays) {
        this.minRetentionDays = minRetentionDays;
    }
}
