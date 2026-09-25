package com.chris64233.cc.legalhold.repo;

import com.chris64233.cc.legalhold.domain.RetentionRule;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RetentionRuleRepository extends JpaRepository<RetentionRule, Long> {

    Optional<RetentionRule> findByCategory(String category);
}
