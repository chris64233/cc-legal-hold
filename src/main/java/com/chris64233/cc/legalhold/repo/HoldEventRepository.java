package com.chris64233.cc.legalhold.repo;

import com.chris64233.cc.legalhold.domain.HoldEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HoldEventRepository extends JpaRepository<HoldEvent, Long> {
}
