package com.chris64233.cc.legalhold.repo;

import com.chris64233.cc.legalhold.domain.HoldAssignment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HoldAssignmentRepository extends JpaRepository<HoldAssignment, UUID> {

    Optional<HoldAssignment> findByCaseIdAndObjectId(UUID caseId, UUID objectId);

    List<HoldAssignment> findByObjectIdAndActiveTrue(UUID objectId);

    @Query("select a from HoldAssignment a where a.objectId = :objectId and a.active = true")
    List<HoldAssignment> findActiveByObjectId(@Param("objectId") UUID objectId);
}
