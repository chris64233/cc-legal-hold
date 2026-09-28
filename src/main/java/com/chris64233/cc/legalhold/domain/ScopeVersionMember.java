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

/**
 * 某范围版本下的成员对象。分批计算时写入，写满后随版本一次性生效。
 *
 * <p>{@code membershipChange} 相对基线版本标记差异：ADDED 为本次新增保全对象，
 * REMOVED 为本次待释放对象，UNCHANGED 为基线已含、本版仍含。旧版本成员行不删除，
 * 可随时计算任意两个版本之间的差异。
 */
@Entity
@Table(name = "scope_version_member", uniqueConstraints = {
        @UniqueConstraint(name = "uk_scope_member_version_object",
                columnNames = {"version_id", "object_id"})
})
public class ScopeVersionMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version_id", nullable = false, updatable = false)
    private Long versionId;

    @Column(name = "case_no", nullable = false, updatable = false, length = 64)
    private String caseNo;

    @Column(name = "object_id", nullable = false, updatable = false)
    private Long objectId;

    @Column(name = "business_key", nullable = false, updatable = false, length = 128)
    private String businessKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "membership_change", nullable = false, updatable = false, length = 16)
    private MembershipChange membershipChange;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ScopeVersionMember() {
    }

    public ScopeVersionMember(Long versionId, String caseNo, Long objectId,
                              String businessKey, MembershipChange membershipChange) {
        this.versionId = versionId;
        this.caseNo = caseNo;
        this.objectId = objectId;
        this.businessKey = businessKey;
        this.membershipChange = membershipChange;
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

    public String getBusinessKey() {
        return businessKey;
    }

    public MembershipChange getMembershipChange() {
        return membershipChange;
    }
}
