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
 * 范围变更审批事件，仅追加。每个 (变更业务号, 审批人, 投票类型) 只能出现一次，
 * 保证审批事件幂等；缩围/关案必须有两名不同审批人的 APPROVE。
 */
@Entity
@Table(name = "scope_approval", uniqueConstraints = {
        @UniqueConstraint(name = "uk_scope_approval_change_reviewer_vote",
                columnNames = {"change_no", "reviewer", "vote_type"})
})
public class ScopeApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "change_no", nullable = false, updatable = false, length = 64)
    private String changeNo;

    @Column(name = "case_no", nullable = false, updatable = false, length = 64)
    private String caseNo;

    @Column(name = "version_id", nullable = false, updatable = false)
    private Long versionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "vote_type", nullable = false, updatable = false, length = 16)
    private ApprovalVoteType voteType;

    @Column(name = "reviewer", nullable = false, updatable = false, length = 64)
    private String reviewer;

    @Column(name = "comment", updatable = false, length = 512)
    private String comment;

    @Column(name = "voted_at", nullable = false, updatable = false)
    private Instant votedAt;

    protected ScopeApproval() {
    }

    public ScopeApproval(String changeNo, String caseNo, Long versionId,
                         ApprovalVoteType voteType, String reviewer, String comment,
                         Instant votedAt) {
        this.changeNo = changeNo;
        this.caseNo = caseNo;
        this.versionId = versionId;
        this.voteType = voteType;
        this.reviewer = reviewer;
        this.comment = comment;
        this.votedAt = votedAt;
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

    public Long getVersionId() {
        return versionId;
    }

    public ApprovalVoteType getVoteType() {
        return voteType;
    }

    public String getReviewer() {
        return reviewer;
    }

    public String getComment() {
        return comment;
    }

    public Instant getVotedAt() {
        return votedAt;
    }
}
