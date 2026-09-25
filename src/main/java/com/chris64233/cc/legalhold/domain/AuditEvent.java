package com.chris64233.cc.legalhold.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 审计事件，仅追加。删除确认成功必须留痕。
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, updatable = false, length = 32)
    private String eventType;

    @Column(name = "object_id", nullable = false, updatable = false)
    private Long objectId;

    @Column(name = "business_key", nullable = false, updatable = false, length = 128)
    private String businessKey;

    @Column(name = "detail", length = 512, updatable = false)
    private String detail;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AuditEvent() {
    }

    public AuditEvent(String eventType, Long objectId, String businessKey, String detail,
                      Instant occurredAt) {
        this.eventType = eventType;
        this.objectId = objectId;
        this.businessKey = businessKey;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public Long getObjectId() {
        return objectId;
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
