package com.chris64233.cc.legalhold.repo;

import com.chris64233.cc.legalhold.domain.ScopeApproval;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScopeApprovalRepository extends JpaRepository<ScopeApproval, Long> {

    List<ScopeApproval> findByChangeNoOrderByApprovedAtAsc(String changeNo);

    Optional<ScopeApproval> findByChangeNoAndApprover(String changeNo, String approver);

    long countByChangeNo(String changeNo);
}
