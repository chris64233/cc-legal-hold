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

/**
 * 删除确认令牌：申请删除时生成，短期有效、一次性使用。
 */
@Entity
@Table(name = "deletion_token", uniqueConstraints = {
        @UniqueConstraint(name = "uk_deletion_token_value", columnNames = "token_value")
})
public class DeletionToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_value", nullable = false, updatable = false, length = 64)
    private String tokenValue;

    @Column(name = "object_id", nullable = false, updatable = false)
    private Long objectId;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DeletionTokenStatus status;

    @Column(name = "reject_reason", length = 256)
    private String rejectReason;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected DeletionToken() {
    }

    public DeletionToken(String tokenValue, Long objectId, Instant expiresAt) {
        this.tokenValue = tokenValue;
        this.objectId = objectId;
        this.expiresAt = expiresAt;
        this.status = DeletionTokenStatus.PENDING;
    }

    public Long getId() {
        return id;
    }

    public String getTokenValue() {
        return tokenValue;
    }

    public Long getObjectId() {
        return objectId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public DeletionTokenStatus getStatus() {
        return status;
    }

    public void setStatus(DeletionTokenStatus status) {
        this.status = status;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }
}
