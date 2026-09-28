package com.chris64233.cc.legalhold.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * 对象在某案件下当前有效的保全关系。
 *
 * <p>解除保全默认删除该行（见 LegalHoldService 的直接解除）；范围版本生效走
 * “逻辑释放”路径：填充 releasedAt/releaseChangeNo 后删除的语义由调用方在同一
 * 事务内完成删除，而记录释放原因则通过 hold_event / 审批留痕完成。
 * 这里保留 effectiveVersionNo 以回答“该保全由哪个范围版本建立”。</p>
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

    /** 建立该保全关系的案件范围版本号；版本功能上线前的直接保全为 null。 */
    @Column(name = "effective_version_no")
    private Integer effectiveVersionNo;

    @Column(name = "established_at")
    private Instant establishedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected HoldMembership() {
    }

    public HoldMembership(String caseNo, Long objectId) {
        this.caseNo = caseNo;
        this.objectId = objectId;
    }

    public HoldMembership(String caseNo, Long objectId, Integer effectiveVersionNo,
                          Instant establishedAt) {
        this.caseNo = caseNo;
        this.objectId = objectId;
        this.effectiveVersionNo = effectiveVersionNo;
        this.establishedAt = establishedAt;
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

    public Integer getEffectiveVersionNo() {
        return effectiveVersionNo;
    }

    public Instant getEstablishedAt() {
        return establishedAt;
    }
}
