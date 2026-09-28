package com.chris64233.cc.legalhold.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 案件聚合根。对案件范围的任何变更都先对该行加悲观写锁，
 * 以此串行化同案件的并发扩围、缩围与关闭，杜绝两个版本同时生效。
 */
@Entity
@Table(name = "legal_case")
public class LegalCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_no", nullable = false, updatable = false, length = 64, unique = true)
    private String caseNo;

    /** 当前对外生效的版本号；没有任何生效版本时为 0。 */
    @Column(name = "current_version_no", nullable = false)
    private int currentVersionNo;

    @Column(name = "closed", nullable = false)
    private boolean closed;

    /**
     * 正在进行中的变更业务号：处于物化或待审批阶段时占用，
     * 生效/拒绝后清空。崩溃后可用同一 changeNo 安全重试。
     */
    @Column(name = "active_change_no", length = 64)
    private String activeChangeNo;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected LegalCase() {
    }

    public LegalCase(String caseNo) {
        this.caseNo = caseNo;
        this.currentVersionNo = 0;
        this.closed = false;
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

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    public String getActiveChangeNo() {
        return activeChangeNo;
    }

    public void setActiveChangeNo(String activeChangeNo) {
        this.activeChangeNo = activeChangeNo;
    }
}
