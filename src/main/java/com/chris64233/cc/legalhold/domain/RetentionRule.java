package com.chris64233.cc.legalhold.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Table(name = "retention_rule", uniqueConstraints = {
        @UniqueConstraint(name = "uk_retention_rule_category", columnNames = "category")
})
public class RetentionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category", nullable = false, length = 64, updatable = false)
    private String category;

    @Column(name = "min_retention_days", nullable = false)
    private int minRetentionDays;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected RetentionRule() {
    }

    public RetentionRule(String category, int minRetentionDays) {
        this.category = category;
        this.minRetentionDays = minRetentionDays;
    }

    public Long getId() {
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
