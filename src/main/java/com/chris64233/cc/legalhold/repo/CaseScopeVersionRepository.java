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

    Optional<CaseScopeVersion> findByCaseNoAndStatus(String caseNo, ScopeVersionStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from CaseScopeVersion v where v.id = :id")
    Optional<CaseScopeVersion> findByIdForUpdate(@Param("id") Long id);
}
