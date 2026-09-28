package com.chris64233.cc.legalhold.repo;

import com.chris64233.cc.legalhold.domain.CaseScope;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaseScopeRepository extends JpaRepository<CaseScope, Long> {

    Optional<CaseScope> findByCaseNo(String caseNo);

    /**
     * 案件行悲观写锁：范围变更提交、审批生效、关闭均在此锁内串行化。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CaseScope c where c.caseNo = :caseNo")
    Optional<CaseScope> findByCaseNoForUpdate(@Param("caseNo") String caseNo);
}
