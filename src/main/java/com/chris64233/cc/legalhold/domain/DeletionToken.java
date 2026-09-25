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
@Table(name = "deletion_token")
public class DeletionToken {

    public enum Outcome {
        PENDING,
        CONFIRMED,
        REJECTED,
        EXPIRED
    }

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String tokenValue;

    @Column(nullable = false)
    private UUID objectId;

    @Column(nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Outcome outcome;

    @Column(length = 1000)
    private String outcomeDetail;

    private Instant resolvedAt;

    protected DeletionToken() {
    }

    public DeletionToken(String tokenValue, UUID objectId, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.tokenValue = tokenValue;
        this.objectId = objectId;
        this.expiresAt = expiresAt;
        this.outcome = Outcome.PENDING;
    }

    public UUID getId() {
        return id;
    }

    public String getTokenValue() {
        return tokenValue;
    }

    public UUID getObjectId() {
        return objectId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getOutcomeDetail() {
        return outcomeDetail;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public boolean isPending() {
        return outcome == Outcome.PENDING;
    }

    public void resolve(Outcome outcome, String detail, Instant resolvedAt) {
        this.outcome = outcome;
        this.outcomeDetail = detail;
        this.resolvedAt = resolvedAt;
    }
}
