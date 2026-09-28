package com.chris64233.cc.legalhold.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * 范围变更审批事件，只追加、不可修改。
 *
 * <p>唯一约束 {@code (change_no, approver)} 保证同一变更下每人只能批准一次，
 * 重复提交审批幂等返回既有事件，不重复计数——这是“两名不同人员批准”的基础。
 */
@Entity
@Table(name = "scope_approval", uniqueConstraints = {
        @UniqueConstraint(name = "uk_scope_approval_change_approver",
                columnNames = {"change_no", "approver"})
})
public class ScopeApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "change_no", nullable = false, updatable = false, length = 64)
    private String changeNo;

    @Column(name = "case_no", nullable = false, updatable = false, length = 64)
    private String caseNo;

    @Column(name = "approver", nullable = false, updatable = false, length = 64)
    private String approver;

    @Column(name = "comment", length = 512, updatable = false)
    private String comment;

    @Column(name = "approved_at", nullable = false, updatable = false)
    private Instant approvedAt;

    protected ScopeApproval() {
    }

    public ScopeApproval(String changeNo, String caseNo, String approver,
                         String comment, Instant approvedAt) {
        this.changeNo = changeNo;
        this.caseNo = caseNo;
        this.approver = approver;
        this.comment = comment;
        this.approvedAt = approvedAt;
    }

    public Long getId() {
        return id;
    }

    public String getChangeNo() {
        return changeNo;
    }

    public String getCaseNo() {
        return caseNo;
    }

    public String getApprover() {
        return approver;
    }

    public String getComment() {
        return comment;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }
}
