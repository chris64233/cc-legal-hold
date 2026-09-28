package com.chris64233.cc.legalhold.repo;

import com.chris64233.cc.legalhold.domain.HoldEvent;
import com.chris64233.cc.legalhold.domain.HoldEventType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HoldEventRepository extends JpaRepository<HoldEvent, Long> {

    List<HoldEvent> findByObjectIdAndEventTypeOrderByEffectiveAtDesc(
            Long objectId, HoldEventType eventType);
}
