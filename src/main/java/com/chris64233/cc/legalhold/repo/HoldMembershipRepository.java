package com.chris64233.cc.legalhold.repo;

import com.chris64233.cc.legalhold.domain.HoldMembership;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HoldMembershipRepository extends JpaRepository<HoldMembership, Long> {

    List<HoldMembership> findByObjectIdOrderByCaseNo(Long objectId);

    Optional<HoldMembership> findByCaseNoAndObjectId(String caseNo, Long objectId);

    boolean existsByObjectId(Long objectId);

    boolean existsByCaseNoAndObjectId(String caseNo, Long objectId);

    long countByCaseNo(String caseNo);

    /**
     * 范围版本缩围生效：只删除本案件指定对象的成员行。先按对象行加锁再调用，
     * 与删除确认严格串行化，不会释放仍处于有效范围内的对象。
     */
    @Modifying
    @Query("delete from HoldMembership m where m.caseNo = :caseNo and m.objectId in :objectIds")
    int deleteByCaseNoAndObjectIdIn(@Param("caseNo") String caseNo,
                                    @Param("objectIds") List<Long> objectIds);
}
