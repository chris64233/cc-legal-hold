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
 * 案件范围的不可变版本。
 *
 * <p>版本一经创建，范围条件（{@code criteriaJson}）永不可改；扩大或缩小范围只能
 * 新建版本。新版本生效时旧版本置为 {@link ScopeVersionStatus#SUPERSEDED} 保留留痕。
 *
 * <p>大批量计算按 ID 游标分批进行：{@code computeDone=false} 时 {@code cursorId}
 * 记录已处理位置，服务重启后从该位置继续；计算期间版本为
 * {@link ScopeVersionStatus#COMPUTING}，对外查询范围仍返回上一生效版本。
 *
 * <p>缩围版本在提交时固化待释放对象的约束快照 {@code releaseSnapshotJson}
 * （其他案件、保留截止时间），终确认时逐项重算比对，任一变化即整批拒绝。
 */
@Entity
@Table(name = "case_scope_version", uniqueConstraints = {
        @UniqueConstraint(name = "uk_scope_version_change_no", columnNames = "change_no"),
        @UniqueConstraint(name = "uk_scope_version_case_version",
                columnNames = {"case_no", "version_no"})
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
    private ScopeChangeType changeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ScopeVersionStatus status;

    @Lob
    @Column(name = "criteria_json", nullable = false, updatable = false)
    private String criteriaJson;

    @Column(name = "base_version_no", updatable = false)
    private Integer baseVersionNo;

    @Column(name = "close_case", nullable = false, updatable = false)
    private boolean closeCase;

    @Column(name = "requested_by", nullable = false, updatable = false, length = 64)
    private String requestedBy;

    @Column(name = "reason", nullable = false, updatable = false, length = 512)
    private String reason;

    @Column(name = "batch_size", nullable = false, updatable = false)
    private int batchSize;

    /** 分批游标：已处理对象的最大 ID；{@code null} 表示尚未开始。 */
    @Column(name = "cursor_id")
    private Long cursorId;

    @Column(name = "compute_done", nullable = false)
    private boolean computeDone;

    @Column(name = "member_count")
    private Integer memberCount;

    @Column(name = "added_count")
    private Integer addedCount;

    @Column(name = "removed_count")
    private Integer removedCount;

    /** 缩围/关闭提交时固化的待释放对象约束快照（JSON）。 */
    @Lob
    @Column(name = "release_snapshot_json")
    private String releaseSnapshotJson;

    @Column(name = "reject_reason", length = 4000)
    private String rejectReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "effective_at")
    private Instant effectiveAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected CaseScopeVersion() {
    }

    public CaseScopeVersion(String caseNo, int versionNo, String changeNo,
                            ScopeChangeType changeType, String criteriaJson,
                            Integer baseVersionNo, boolean closeCase,
                            String requestedBy, String reason, int batchSize,
                            Instant createdAt) {
        this.caseNo = caseNo;
        this.versionNo = versionNo;
        this.changeNo = changeNo;
        this.changeType = changeType;
        this.criteriaJson = criteriaJson;
        this.baseVersionNo = baseVersionNo;
        this.closeCase = closeCase;
        this.requestedBy = requestedBy;
        this.reason = reason;
        this.batchSize = batchSize;
        this.status = ScopeVersionStatus.COMPUTING;
        this.computeDone = false;
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

    public ScopeChangeType getChangeType() {
        return changeType;
    }

    public void setChangeType(ScopeChangeType changeType) {
        this.changeType = changeType;
    }

    public ScopeVersionStatus getStatus() {
        return status;
    }

    public void setStatus(ScopeVersionStatus status) {
        this.status = status;
    }

    public String getCriteriaJson() {
        return criteriaJson;
    }

    public Integer getBaseVersionNo() {
        return baseVersionNo;
    }

    public boolean isCloseCase() {
        return closeCase;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public String getReason() {
        return reason;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public Long getCursorId() {
        return cursorId;
    }

    public void setCursorId(Long cursorId) {
        this.cursorId = cursorId;
    }

    public boolean isComputeDone() {
        return computeDone;
    }

    public void setComputeDone(boolean computeDone) {
        this.computeDone = computeDone;
    }

    public Integer getMemberCount() {
        return memberCount;
    }

    public void setMemberCount(Integer memberCount) {
        this.memberCount = memberCount;
    }

    public Integer getAddedCount() {
        return addedCount;
    }

    public void setAddedCount(Integer addedCount) {
        this.addedCount = addedCount;
    }

    public Integer getRemovedCount() {
        return removedCount;
    }

    public void setRemovedCount(Integer removedCount) {
        this.removedCount = removedCount;
    }

    public String getReleaseSnapshotJson() {
        return releaseSnapshotJson;
    }

    public void setReleaseSnapshotJson(String releaseSnapshotJson) {
        this.releaseSnapshotJson = releaseSnapshotJson;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public void setEffectiveAt(Instant effectiveAt) {
        this.effectiveAt = effectiveAt;
    }
}
