package com.chris64233.cc.legalhold.repo;

import com.chris64233.cc.legalhold.domain.HoldMembership;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HoldMembershipRepository extends JpaRepository<HoldMembership, Long> {

    List<HoldMembership> findByObjectIdOrderByCaseNo(Long objectId);

    Optional<HoldMembership> findByCaseNoAndObjectId(String caseNo, Long objectId);

    List<HoldMembership> findByCaseNoAndObjectIdInOrderByObjectId(String caseNo,
                                                                   List<Long> objectIds);

    /**
     * 缩围/关案时分页枚举本案当前实际生效的保全对象（键集分页），
     * 以“当前保全集合 - 新版本目标集合”计算待释放对象。
     */
    @Query("""
            select m.objectId from HoldMembership m
            where m.caseNo = :caseNo and m.objectId > :afterId
            order by m.objectId
            """)
    List<Long> scanActiveObjectIds(@Param("caseNo") String caseNo,
                                   @Param("afterId") long afterId,
                                   Pageable pageable);

    boolean existsByObjectId(Long objectId);
}
