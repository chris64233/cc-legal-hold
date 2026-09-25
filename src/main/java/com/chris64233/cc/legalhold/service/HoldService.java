package com.chris64233.cc.legalhold.service;

import com.chris64233.cc.legalhold.domain.DataObject;
import com.chris64233.cc.legalhold.domain.DataObjectStatus;
import com.chris64233.cc.legalhold.domain.HoldAssignment;
import com.chris64233.cc.legalhold.domain.HoldEvent;
import com.chris64233.cc.legalhold.domain.HoldEventType;
import com.chris64233.cc.legalhold.domain.LegalHoldCase;
import com.chris64233.cc.legalhold.repo.DataObjectRepository;
import com.chris64233.cc.legalhold.repo.HoldAssignmentRepository;
import com.chris64233.cc.legalhold.repo.HoldEventRepository;
import com.chris64233.cc.legalhold.repo.LegalHoldCaseRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HoldService {

    private final LegalHoldCaseRepository caseRepository;
    private final DataObjectRepository objectRepository;
    private final HoldAssignmentRepository assignmentRepository;
    private final HoldEventRepository eventRepository;
    private final Clock clock;

    public HoldService(LegalHoldCaseRepository caseRepository,
                       DataObjectRepository objectRepository,
                       HoldAssignmentRepository assignmentRepository,
                       HoldEventRepository eventRepository,
                       Clock clock) {
        this.caseRepository = caseRepository;
        this.objectRepository = objectRepository;
        this.assignmentRepository = assignmentRepository;
        this.eventRepository = eventRepository;
        this.clock = clock;
    }

    @Transactional
    public LegalHoldCase createCase(String caseNumber, String title) {
        caseRepository.findByCaseNumber(caseNumber).ifPresent(existing -> {
            throw new BusinessException("案件号已存在: " + caseNumber);
        });
        return caseRepository.save(new LegalHoldCase(caseNumber, title));
    }

    @Transactional
    public HoldEvent placeHold(String caseNumber, List<String> objectKeys, String reason, Instant effectiveAt) {
        return apply(caseNumber, objectKeys, reason, effectiveAt, HoldEventType.PLACE);
    }

    @Transactional
    public HoldEvent releaseHold(String caseNumber, List<String> objectKeys, String reason, Instant effectiveAt) {
        return apply(caseNumber, objectKeys, reason, effectiveAt, HoldEventType.RELEASE);
    }

    private HoldEvent apply(String caseNumber, List<String> objectKeys, String reason,
                            Instant effectiveAt, HoldEventType type) {
        LegalHoldCase holdCase = caseRepository.findByCaseNumber(caseNumber)
                .orElseThrow(() -> new NotFoundException("案件不存在: " + caseNumber));
        if (objectKeys == null || objectKeys.isEmpty()) {
            throw new BusinessException("保全事件必须包含至少一个对象");
        }
        Instant effective = effectiveAt != null ? effectiveAt : clock.instant();

        List<UUID> objectIds = new ArrayList<>();
        for (String key : new TreeSet<>(objectKeys)) {
            DataObject object = objectRepository.findByBusinessKeyForUpdate(key)
                    .orElseThrow(() -> new NotFoundException("数据对象不存在: " + key));
            if (type == HoldEventType.PLACE && object.getStatus() == DataObjectStatus.DELETED) {
                throw new BusinessException("对象已删除，无法加入保全: " + key);
            }
            HoldAssignment assignment = assignmentRepository
                    .findByCaseIdAndObjectId(holdCase.getId(), object.getId())
                    .orElseGet(() -> assignmentRepository.save(new HoldAssignment(holdCase.getId(), object.getId())));
            assignment.setActive(type == HoldEventType.PLACE);
            objectIds.add(object.getId());
        }

        HoldEvent event = new HoldEvent("EVT-" + UUID.randomUUID(), holdCase.getId(), type,
                objectIds, reason, effective, clock.instant());
        return eventRepository.save(event);
    }
}
