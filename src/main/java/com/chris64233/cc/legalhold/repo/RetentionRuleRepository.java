package com.chris64233.cc.legalhold.repo;

import com.chris64233.cc.legalhold.domain.RetentionRule;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RetentionRuleRepository extends JpaRepository<RetentionRule, Long> {

    Optional<RetentionRule> findByCategory(String category);

    /**
     * 悲观写锁。范围释放最终复核时锁定保留规则行，
     * 与规则延长/缩短更新串行化，避免“复核通过后规则又变化”。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RetentionRule r where r.category = :category")
    Optional<RetentionRule> findByCategoryForUpdate(@Param("category") String category);
}
