package com.chris64233.cc.legalhold.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * 案件范围的不可变版本。扩大或缩小范围都创建新版本，绝不覆盖旧版本；
 * 目标对象集合由 {@link CaseScopeMember} 行物化，版本行只记录元数据。
 */
@Entity
@Table(name = "case_scope_version", uniqueConstraints = {
        @UniqueConstraint(name = "uk_case_scope_version_no",
                columnNames = {"case_no", "version_no"}),
        @UniqueConstraint(name = "uk_case_scope_change_no", columnNames = "change_no")
})
public class CaseScopeVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_no", nullable = false, updatable = false, length = 64)
    private String caseNo;

    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    @Column(name = "change_no", nullable = false, updatable = false, length = 64)
    private String changeNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 16)
    private ChangeType changeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ScopeVersionStatus status;

    @Column(name = "based_on_version_no", nullable = false, updatable = false)
    private int basedOnVersionNo;

    /** 生成范围所用条件（对象标识、类别、创建时间区间）的 JSON 快照。 */
    @Lob
    @Column(name = "criteria_json", nullable = false, updatable = false)
    private String criteriaJson;

    @Column(name = "reason", nullable = false, updatable = false, length = 512)
    private String reason;

    @Column(name = "created_by", updatable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 分批物化续跑游标：目标集合扫描已处理到的对象 ID。 */
    @Column(name = "materialize_after_id")
    private Long materializeAfterId;

    /** 变更开始时固化的对象 ID 高水位；重算只扫到此 ID，保证重试目标集合不变。 */
    @Column(name = "materialize_max_id")
    private Long materializeMaxId;

    /** REMOVED 差异行分批续跑游标（基准版本成员扫描位置）。 */
    @Column(name = "removed_after_id")
    private Long removedAfterId;

    /** 目标集合/差异行物化是否全部完成。 */
    @Column(name = "materialized", nullable = false)
    private boolean materialized;

    /** 显式对象标识是否已并入目标集合（它们只处理一次）。 */
    @Column(name = "explicit_done", nullable = false)
    private boolean explicitDone;

    @Column(name = "target_count", nullable = false)
    private int targetCount;

    @Column(name = "added_count", nullable = false)
    private int addedCount;

    @Column(name = "retained_count", nullable = false)
    private int retainedCount;

    @Column(name = "removed_count", nullable = false)
    private int removedCount;

    /** 待释放对象在提交时的约束快照 JSON，最终确认时逐项重核。 */
    @Lob
    @Column(name = "constraint_snapshot_json")
    private String constraintSnapshotJson;

    @Column(name = "effective_at")
    private Instant effectiveAt;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "reject_reason", length = 1024)
    private String rejectReason;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected CaseScopeVersion() {
    }

    public CaseScopeVersion(String caseNo, int versionNo, String changeNo, ChangeType changeType,
                            ScopeVersionStatus status, int basedOnVersionNo, String criteriaJson,
                            String reason, String createdBy, Instant createdAt) {
        this.caseNo = caseNo;
        this.versionNo = versionNo;
        this.changeNo = changeNo;
        this.changeType = changeType;
        this.status = status;
        this.basedOnVersionNo = basedOnVersionNo;
        this.criteriaJson = criteriaJson;
        this.reason = reason;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getCaseNo() {
        return caseNo;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public String getChangeNo() {
        return changeNo;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public void setChangeType(ChangeType changeType) {
        this.changeType = changeType;
    }

    public ScopeVersionStatus getStatus() {
        return status;
    }

    public void setStatus(ScopeVersionStatus status) {
        this.status = status;
    }

    public int getBasedOnVersionNo() {
        return basedOnVersionNo;
    }

    public String getCriteriaJson() {
        return criteriaJson;
    }

    public String getReason() {
        return reason;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getMaterializeAfterId() {
        return materializeAfterId;
    }

    public void setMaterializeAfterId(Long materializeAfterId) {
        this.materializeAfterId = materializeAfterId;
    }

    public Long getMaterializeMaxId() {
        return materializeMaxId;
    }

    public void setMaterializeMaxId(Long materializeMaxId) {
        this.materializeMaxId = materializeMaxId;
    }

    public Long getRemovedAfterId() {
        return removedAfterId;
    }

    public void setRemovedAfterId(Long removedAfterId) {
        this.removedAfterId = removedAfterId;
    }

    public boolean isMaterialized() {
        return materialized;
    }

    public void setMaterialized(boolean materialized) {
        this.materialized = materialized;
    }

    public boolean isExplicitDone() {
        return explicitDone;
    }

    public void setExplicitDone(boolean explicitDone) {
        this.explicitDone = explicitDone;
    }

    public int getTargetCount() {
        return targetCount;
    }

    public void setTargetCount(int targetCount) {
        this.targetCount = targetCount;
    }

    public int getAddedCount() {
        return addedCount;
    }

    public void setAddedCount(int addedCount) {
        this.addedCount = addedCount;
    }

    public int getRetainedCount() {
        return retainedCount;
    }

    public void setRetainedCount(int retainedCount) {
        this.retainedCount = retainedCount;
    }

    public int getRemovedCount() {
        return removedCount;
    }

    public void setRemovedCount(int removedCount) {
        this.removedCount = removedCount;
    }

    public String getConstraintSnapshotJson() {
        return constraintSnapshotJson;
    }

    public void setConstraintSnapshotJson(String constraintSnapshotJson) {
        this.constraintSnapshotJson = constraintSnapshotJson;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public void setEffectiveAt(Instant effectiveAt) {
        this.effectiveAt = effectiveAt;
    }

    public Instant getRejectedAt() {
        return rejectedAt;
    }

    public void setRejectedAt(Instant rejectedAt) {
        this.rejectedAt = rejectedAt;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }
}
