package com.chris64233.cc.legalhold.repo;

import com.chris64233.cc.legalhold.domain.DataObject;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DataObjectRepository extends JpaRepository<DataObject, UUID> {

    Optional<DataObject> findByBusinessKey(String businessKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from DataObject o where o.businessKey = :businessKey")
    Optional<DataObject> findByBusinessKeyForUpdate(@Param("businessKey") String businessKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from DataObject o where o.id = :id")
    Optional<DataObject> findByIdForUpdate(@Param("id") UUID id);
}
