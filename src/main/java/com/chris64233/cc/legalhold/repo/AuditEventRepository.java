package com.chris64233.cc.legalhold.repo;

import com.chris64233.cc.legalhold.domain.AuditEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findByObjectIdOrderByOccurredAtAsc(Long objectId);
}
