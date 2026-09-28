package com.chris64233.cc.legalhold.repo;

import com.chris64233.cc.legalhold.domain.MembershipChange;
import com.chris64233.cc.legalhold.domain.ScopeVersionMember;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScopeVersionMemberRepository extends JpaRepository<ScopeVersionMember, Long> {

    List<ScopeVersionMember> findByVersionIdOrderByObjectId(Long versionId);

    List<ScopeVersionMember> findByVersionIdAndMembershipChangeOrderByObjectId(
            Long versionId, MembershipChange change);

    long countByVersionIdAndMembershipChange(Long versionId, MembershipChange change);

    boolean existsByVersionIdAndObjectId(Long versionId, Long objectId);

    @Query("select m.objectId from ScopeVersionMember m where m.versionId = :versionId")
    List<Long> findObjectIdsByVersionId(@Param("versionId") Long versionId);
}
