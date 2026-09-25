package com.chris64233.cc.legalhold.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "data_object")
public class DataObject {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String businessKey;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DataObjectStatus status;

    private Instant deletedAt;

    protected DataObject() {
    }

    public DataObject(String businessKey, String category, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.businessKey = businessKey;
        this.category = category;
        this.createdAt = createdAt;
        this.status = DataObjectStatus.ACTIVE;
    }

    public UUID getId() {
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

    public DataObjectStatus getStatus() {
        return status;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void markDeleted(Instant deletedAt) {
        this.status = DataObjectStatus.DELETED;
        this.deletedAt = deletedAt;
    }
}
