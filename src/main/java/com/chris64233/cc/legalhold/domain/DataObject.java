package com.chris64233.cc.legalhold.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "data_object", uniqueConstraints = {
        @UniqueConstraint(name = "uk_data_object_business_key", columnNames = "business_key")
})
public class DataObject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_key", nullable = false, length = 128)
    private String businessKey;

    @Column(name = "category", nullable = false, length = 64)
    private String category;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ObjectStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected DataObject() {
    }

    public DataObject(String businessKey, String category, Instant createdAt) {
        this.businessKey = businessKey;
        this.category = category;
        this.createdAt = createdAt;
        this.status = ObjectStatus.ACTIVE;
    }

    public Long getId() {
        return id;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public String getCategory() {
        return category;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public ObjectStatus getStatus() {
        return status;
    }

    public void setStatus(ObjectStatus status) {
        this.status = status;
    }
}
