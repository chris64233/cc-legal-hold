package com.chris64233.cc.legalhold.repo;

import com.chris64233.cc.legalhold.domain.LegalHoldCase;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LegalHoldCaseRepository extends JpaRepository<LegalHoldCase, UUID> {

    Optional<LegalHoldCase> findByCaseNumber(String caseNumber);
}
