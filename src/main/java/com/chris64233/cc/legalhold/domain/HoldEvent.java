package com.chris64233.cc.legalhold.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "hold_event")
public class HoldEvent {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String eventNumber;

    @Column(nullable = false)
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HoldEventType type;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "hold_event_object", joinColumns = @JoinColumn(name = "event_id"))
    @Column(name = "object_id", nullable = false)
    private List<UUID> objectIds;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private Instant effectiveAt;

    @Column(nullable = false)
    private Instant recordedAt;

    protected HoldEvent() {
    }

    public HoldEvent(String eventNumber, UUID caseId, HoldEventType type, List<UUID> objectIds,
                     String reason, Instant effectiveAt, Instant recordedAt) {
        this.id = UUID.randomUUID();
        this.eventNumber = eventNumber;
        this.caseId = caseId;
        this.type = type;
        this.objectIds = List.copyOf(objectIds);
        this.reason = reason;
        this.effectiveAt = effectiveAt;
        this.recordedAt = recordedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getEventNumber() {
        return eventNumber;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public HoldEventType getType() {
        return type;
    }

    public List<UUID> getObjectIds() {
        return objectIds;
    }

    public String getReason() {
        return reason;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
