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
 * 案件聚合根。每个案件一行，保存当前生效版本号与案件状态。
 *
 * <p>范围变更事务内先对该行加悲观写锁，串行化同一案件的扩围、缩围与关闭，
 * 保证“基线版本 + 幂等业务号”判断不被并发击穿。
 */
@Entity
@Table(name = "case_scope", uniqueConstraints = {
        @UniqueConstraint(name = "uk_case_scope_case_no", columnNames = "case_no")
})
public class CaseScope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_no", nullable = false, updatable = false, length = 64)
    private String caseNo;

    @Column(name = "current_version_no", nullable = false)
    private int currentVersionNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CaseStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected CaseScope() {
    }

    public CaseScope(String caseNo) {
        this.caseNo = caseNo;
        this.currentVersionNo = 0;
        this.status = CaseStatus.OPEN;
    }

    public Long getId() {
        return id;
    }

    public String getCaseNo() {
        return caseNo;
    }

    public int getCurrentVersionNo() {
        return currentVersionNo;
    }

    public void setCurrentVersionNo(int currentVersionNo) {
        this.currentVersionNo = currentVersionNo;
    }

    public CaseStatus getStatus() {
        return status;
    }

    public void setStatus(CaseStatus status) {
        this.status = status;
    }
}
