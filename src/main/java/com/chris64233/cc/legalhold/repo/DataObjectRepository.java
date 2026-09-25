package com.chris64233.cc.legalhold.repo;

import com.chris64233.cc.legalhold.domain.DataObject;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DataObjectRepository extends JpaRepository<DataObject, Long> {

    Optional<DataObject> findByBusinessKey(String businessKey);

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
}
