package com.chris64233.cc.legalhold.repo;

import com.chris64233.cc.legalhold.domain.LegalCase;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LegalCaseRepository extends JpaRepository<LegalCase, Long> {

    Optional<LegalCase> findByCaseNo(String caseNo);

    /**
     * 案件级悲观写锁。所有范围变更与审批生效都先锁案件行，串行化同案件并发。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from LegalCase c where c.caseNo = :caseNo")
    Optional<LegalCase> findByCaseNoForUpdate(@Param("caseNo") String caseNo);
}
