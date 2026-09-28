package com.chris64233.cc.legalhold.repo;

import com.chris64233.cc.legalhold.domain.DataObject;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DataObjectRepository extends JpaRepository<DataObject, Long> {

    Optional<DataObject> findByBusinessKey(String businessKey);

    List<DataObject> findByBusinessKeyIn(List<String> businessKeys);

    /**
     * 悲观写锁。保全加入/解除、删除申请/确认均先锁对象行以串行化。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from DataObject o where o.id = :id")
    Optional<DataObject> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from DataObject o where o.id in :ids order by o.id")
    List<DataObject> findByIdsForUpdateOrderById(@Param("ids") List<Long> ids);

    /**
     * 仅取 ID 的投影查询，不把实体装入持久化上下文，供后续加锁加载使用。
     */
    @Query("select o.id from DataObject o where o.businessKey = :businessKey")
    Optional<Long> findIdByBusinessKey(@Param("businessKey") String businessKey);

    /**
     * 范围物化用键集分页（带类别条件）：扫描 (afterId, maxId] 区间内、命中类别
     * 与创建时间条件的对象。maxId 在变更开始时固化为重算高水位，保证重试得到
     * 同一目标集合。
     */
    @Query("""
            select o from DataObject o
            where o.id > :afterId and o.id <= :maxId and o.category in :categories
                  and o.status <> com.chris64233.cc.legalhold.domain.ObjectStatus.DELETED
                  and (:createdFrom is null or o.createdAt >= :createdFrom)
                  and (:createdTo is null or o.createdAt < :createdTo)
            order by o.id
            """)
    List<DataObject> scanScopePageWithCategories(@Param("afterId") long afterId,
                                                  @Param("maxId") long maxId,
                                                  @Param("categories") List<String> categories,
                                                  @Param("createdFrom") Instant createdFrom,
                                                  @Param("createdTo") Instant createdTo,
                                                  Pageable pageable);

    /**
     * 范围物化用键集分页（无类别条件，仅创建时间区间）。
     */
    @Query("""
            select o from DataObject o
            where o.id > :afterId and o.id <= :maxId
                  and o.status <> com.chris64233.cc.legalhold.domain.ObjectStatus.DELETED
                  and (:createdFrom is null or o.createdAt >= :createdFrom)
                  and (:createdTo is null or o.createdAt < :createdTo)
            order by o.id
            """)
    List<DataObject> scanScopePage(@Param("afterId") long afterId,
                                   @Param("maxId") long maxId,
                                   @Param("createdFrom") Instant createdFrom,
                                   @Param("createdTo") Instant createdTo,
                                   Pageable pageable);

    @Query("select coalesce(max(o.id), 0) from DataObject o")
    long findMaxId();
}
