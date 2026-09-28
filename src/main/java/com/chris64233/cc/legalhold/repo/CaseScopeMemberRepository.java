package com.chris64233.cc.legalhold.repo;

import com.chris64233.cc.legalhold.domain.CaseScopeMember;
import com.chris64233.cc.legalhold.domain.ScopeDeltaType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaseScopeMemberRepository extends JpaRepository<CaseScopeMember, Long> {

    List<CaseScopeMember> findByVersionId(Long versionId);

    List<CaseScopeMember> findByVersionIdAndDeltaType(Long versionId, ScopeDeltaType deltaType);

    boolean existsByVersionIdAndObjectId(Long versionId, Long objectId);

    /**
     * 某版本的目标集合（ADDED + RETAINED）对象 ID，作为差异查询基准。
     */
    @Query("""
            select m.objectId from CaseScopeMember m
            where m.versionId = :versionId and m.deltaType <> com.chris64233.cc.legalhold.domain.ScopeDeltaType.REMOVED
            order by m.objectId
            """)
    List<Long> findTargetObjectIds(@Param("versionId") Long versionId);
}
