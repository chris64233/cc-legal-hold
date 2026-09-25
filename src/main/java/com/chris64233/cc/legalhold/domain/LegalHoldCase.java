package com.chris64233.cc.legalhold.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "legal_hold_case")
public class LegalHoldCase {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String caseNumber;

    @Column(nullable = false)
    private String title;

    protected LegalHoldCase() {
    }

    public LegalHoldCase(String caseNumber, String title) {
        this.id = UUID.randomUUID();
        this.caseNumber = caseNumber;
        this.title = title;
    }

    public UUID getId() {
        return id;
    }

    public String getCaseNumber() {
        return caseNumber;
    }

    public String getTitle() {
        return title;
    }
}
