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

/**
 * 某个范围版本物化后的成员行（目标集合的不可变快照）。
 * delta 标记相对基准版本是新增、保留还是移除；分批物化时按批次插入，
 * 已提交批次在重试时通过 (versionId, objectId) 唯一约束跳过。
 */
@Entity
@Table(name = "case_scope_member", uniqueConstraints = {
        @UniqueConstraint(name = "uk_case_scope_member_version_object",
                columnNames = {"version_id", "object_id"})
})
public class CaseScopeMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version_id", nullable = false, updatable = false)
    private Long versionId;

    @Column(name = "case_no", nullable = false, updatable = false, length = 64)
    private String caseNo;

    @Column(name = "object_id", nullable = false, updatable = false)
    private Long objectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "delta_type", nullable = false, updatable = false, length = 16)
    private ScopeDeltaType deltaType;

    protected CaseScopeMember() {
    }

    public CaseScopeMember(Long versionId, String caseNo, Long objectId,
                           ScopeDeltaType deltaType) {
        this.versionId = versionId;
        this.caseNo = caseNo;
        this.objectId = objectId;
        this.deltaType = deltaType;
    }

    public Long getId() {
        return id;
    }

    public Long getVersionId() {
        return versionId;
    }

    public String getCaseNo() {
        return caseNo;
    }

    public Long getObjectId() {
        return objectId;
    }

    public ScopeDeltaType getDeltaType() {
        return deltaType;
    }
}
