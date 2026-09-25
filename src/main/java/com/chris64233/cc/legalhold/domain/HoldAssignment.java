package com.chris64233.cc.legalhold.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;

@Entity
@Table(name = "hold_assignment",
        uniqueConstraints = @UniqueConstraint(columnNames = {"case_id", "object_id"}))
public class HoldAssignment {

    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "object_id", nullable = false)
    private UUID objectId;

    @Column(nullable = false)
    private boolean active;

    protected HoldAssignment() {
    }

    public HoldAssignment(UUID caseId, UUID objectId) {
        this.id = UUID.randomUUID();
        this.caseId = caseId;
        this.objectId = objectId;
        this.active = true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getObjectId() {
        return objectId;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
