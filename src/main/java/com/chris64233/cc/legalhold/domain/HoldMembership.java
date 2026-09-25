package com.chris64233.cc.legalhold.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * 对象在某案件下当前有效的保全关系。解除保全即删除该行。
 */
@Entity
@Table(name = "hold_membership", uniqueConstraints = {
        @UniqueConstraint(name = "uk_hold_membership_case_object",
                columnNames = {"case_no", "object_id"})
})
public class HoldMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_no", nullable = false, length = 64)
    private String caseNo;

    @Column(name = "object_id", nullable = false)
    private Long objectId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected HoldMembership() {
    }

    public HoldMembership(String caseNo, Long objectId) {
        this.caseNo = caseNo;
        this.objectId = objectId;
    }

    public Long getId() {
        return id;
    }

    public String getCaseNo() {
        return caseNo;
    }

    public Long getObjectId() {
        return objectId;
    }
}
