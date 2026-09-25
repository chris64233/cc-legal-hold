package com.chris64233.cc.legalhold.repo;

import com.chris64233.cc.legalhold.domain.DeletionToken;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeletionTokenRepository extends JpaRepository<DeletionToken, UUID> {

    Optional<DeletionToken> findByTokenValue(String tokenValue);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from DeletionToken t where t.tokenValue = :tokenValue")
    Optional<DeletionToken> findByTokenValueForUpdate(@Param("tokenValue") String tokenValue);
}
