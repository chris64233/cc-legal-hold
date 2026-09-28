package com.chris64233.cc.legalhold.repo;

import com.chris64233.cc.legalhold.domain.CaseScopeVersion;
import com.chris64233.cc.legalhold.domain.ScopeVersionStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaseScopeVersionRepository extends JpaRepository<CaseScopeVersion, Long> {

    Optional<CaseScopeVersion> findByChangeNo(String changeNo);

    List<CaseScopeVersion> findByCaseNoOrderByVersionNoAsc(String caseNo);

    Optional<CaseScopeVersion> findByCaseNoAndVersionNo(String caseNo, int versionNo);

    /**
     * 一个案件同时只允许一个进行中（计算中或待审批）的变更，保证版本按序生效。
     */
    List<CaseScopeVersion> findByCaseNoAndStatusIn(String caseNo,
                                                   List<ScopeVersionStatus> statuses);

    List<CaseScopeVersion> findByStatus(ScopeVersionStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from CaseScopeVersion v where v.id = :id")
    Optional<CaseScopeVersion> findByIdForUpdate(@Param("id") Long id);
}
