package com.chris64233.cc.legalhold.service;

import com.chris64233.cc.legalhold.domain.DataObject;
import com.chris64233.cc.legalhold.domain.HoldEvent;
import com.chris64233.cc.legalhold.domain.HoldEventType;
import com.chris64233.cc.legalhold.domain.HoldMembership;
import com.chris64233.cc.legalhold.domain.ObjectStatus;
import com.chris64233.cc.legalhold.repo.DataObjectRepository;
import com.chris64233.cc.legalhold.repo.HoldEventRepository;
import com.chris64233.cc.legalhold.repo.HoldMembershipRepository;
import com.chris64233.cc.legalhold.time.DomainClock;
import com.chris64233.cc.legalhold.web.dto.HoldRequest;
import com.chris64233.cc.legalhold.web.dto.HoldResultView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LegalHoldService {

    private final DataObjectRepository objectRepository;
    private final HoldMembershipRepository membershipRepository;
    private final HoldEventRepository eventRepository;
    private final DomainClock clock;

    public LegalHoldService(DataObjectRepository objectRepository,
                            HoldMembershipRepository membershipRepository,
                            HoldEventRepository eventRepository,
                            DomainClock clock) {
        this.objectRepository = objectRepository;
        this.membershipRepository = membershipRepository;
        this.eventRepository = eventRepository;
        this.clock = clock;
    }

    /**
     * 将多个对象纳入案件保全。已在该案件保全中的对象幂等跳过。
     */
    @Transactional
    public HoldResultView apply(HoldRequest request) {
        List<DataObject> locked = lockObjects(request.businessKeys());
        Instant effectiveAt = clock.now();
        String eventNo = newEventNo();
        List<String> affected = new ArrayList<>();

        for (DataObject dataObject : locked) {
            if (dataObject.getStatus() == ObjectStatus.DELETED) {
                throw new ConflictException("对象已删除，不能加入保全: "
                        + dataObject.getBusinessKey());
            }
            if (membershipRepository
                    .findByCaseNoAndObjectId(request.caseNo(), dataObject.getId())
                    .isPresent()) {
                continue;
            }
            membershipRepository.save(
                    new HoldMembership(request.caseNo(), dataObject.getId()));
            eventRepository.save(new HoldEvent(eventNo, request.caseNo(), HoldEventType.APPLY,
                    dataObject.getId(), request.reason(), effectiveAt));
            affected.add(dataObject.getBusinessKey());
        }
        return new HoldResultView(eventNo, request.caseNo(), HoldEventType.APPLY,
                affected, request.reason(), effectiveAt);
    }

    /**
     * 解除案件对多个对象的保全。任一对象不存在有效保全即整体失败。
     */
    @Transactional
    public HoldResultView release(HoldRequest request) {
        List<DataObject> locked = lockObjects(request.businessKeys());
        Instant effectiveAt = clock.now();
        String eventNo = newEventNo();

        List<HoldMembership> memberships = new ArrayList<>();
        for (DataObject dataObject : locked) {
            HoldMembership membership = membershipRepository
                    .findByCaseNoAndObjectId(request.caseNo(), dataObject.getId())
                    .orElseThrow(() -> new ConflictException(
                            "案件对该对象无有效保全，不能解除: " + dataObject.getBusinessKey()));
            memberships.add(membership);
        }

        for (int i = 0; i < locked.size(); i++) {
            membershipRepository.delete(memberships.get(i));
            eventRepository.save(new HoldEvent(eventNo, request.caseNo(), HoldEventType.RELEASE,
                    locked.get(i).getId(), request.reason(), effectiveAt));
        }

        List<String> businessKeys = locked.stream()
                .map(DataObject::getBusinessKey)
                .toList();
        return new HoldResultView(eventNo, request.caseNo(), HoldEventType.RELEASE,
                businessKeys, request.reason(), effectiveAt);
    }

    /**
     * 按主键升序锁定对象行，保证多对象操作与删除确认之间的锁顺序一致，避免死锁。
     */
    private List<DataObject> lockObjects(List<String> businessKeys) {
        List<Long> ids = new ArrayList<>();
        for (String key : businessKeys.stream().distinct().toList()) {
            ids.add(objectRepository.findIdByBusinessKey(key)
                    .orElseThrow(() -> new NotFoundException("对象不存在: " + key)));
        }
        ids.sort(Long::compareTo);
        return objectRepository.findByIdsForUpdateOrderById(ids);
    }

    private String newEventNo() {
        return "EVT-" + UUID.randomUUID();
    }
}
