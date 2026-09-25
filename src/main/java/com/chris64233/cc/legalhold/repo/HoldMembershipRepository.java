package com.chris64233.cc.legalhold.repo;

import com.chris64233.cc.legalhold.domain.HoldMembership;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HoldMembershipRepository extends JpaRepository<HoldMembership, Long> {

    List<HoldMembership> findByObjectIdOrderByCaseNo(Long objectId);

    Optional<HoldMembership> findByCaseNoAndObjectId(String caseNo, Long objectId);

    boolean existsByObjectId(Long objectId);
}
