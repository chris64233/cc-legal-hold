package com.chris64233.cc.legalhold.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private UUID objectId;

    @Column(nullable = false)
    private String businessKey;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(length = 1000)
    private String detail;

    protected AuditEvent() {
    }

    public AuditEvent(String eventType, UUID objectId, String businessKey, Instant occurredAt, String detail) {
        this.id = UUID.randomUUID();
        this.eventType = eventType;
        this.objectId = objectId;
        this.businessKey = businessKey;
        this.occurredAt = occurredAt;
        this.detail = detail;
    }

    public UUID getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getObjectId() {
        return objectId;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getDetail() {
        return detail;
    }
}
