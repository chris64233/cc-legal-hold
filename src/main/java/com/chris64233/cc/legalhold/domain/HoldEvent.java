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
import java.time.Instant;

/**
 * 法律保全操作事件。每次纳入/解除保全生成一条，写入后不可修改。
 */
@Entity
@Table(name = "hold_event", uniqueConstraints = {
        @UniqueConstraint(name = "uk_hold_event_event_no_object",
                columnNames = {"event_no", "object_id"})
})
public class HoldEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_no", nullable = false, updatable = false, length = 64)
    private String eventNo;

    @Column(name = "case_no", nullable = false, updatable = false, length = 64)
    private String caseNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 16)
    private HoldEventType eventType;

    @Column(name = "object_id", nullable = false, updatable = false)
    private Long objectId;

    @Column(name = "reason", nullable = false, updatable = false, length = 512)
    private String reason;

    @Column(name = "effective_at", nullable = false, updatable = false)
    private Instant effectiveAt;

    protected HoldEvent() {
    }

    public HoldEvent(String eventNo, String caseNo, HoldEventType eventType, Long objectId,
                     String reason, Instant effectiveAt) {
        this.eventNo = eventNo;
        this.caseNo = caseNo;
        this.eventType = eventType;
        this.objectId = objectId;
        this.reason = reason;
        this.effectiveAt = effectiveAt;
    }

    public Long getId() {
        return id;
    }

    public String getEventNo() {
        return eventNo;
    }

    public String getCaseNo() {
        return caseNo;
    }

    public HoldEventType getEventType() {
        return eventType;
    }

    public Long getObjectId() {
        return objectId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }
}
