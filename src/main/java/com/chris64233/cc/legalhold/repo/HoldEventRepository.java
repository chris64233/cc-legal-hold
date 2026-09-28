package com.chris64233.cc.legalhold.repo;

import com.chris64233.cc.legalhold.domain.HoldEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HoldEventRepository extends JpaRepository<HoldEvent, Long> {

    boolean existsByEventNo(String eventNo);

    boolean existsByChangeNoAndObjectId(String changeNo, Long objectId);

    List<HoldEvent> findByObjectIdOrderByEffectiveAtAsc(Long objectId);
}
