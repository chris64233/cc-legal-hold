package com.chris64233.cc.legalhold.service;

import com.chris64233.cc.legalhold.domain.DataObject;
import com.chris64233.cc.legalhold.domain.HoldMembership;
import com.chris64233.cc.legalhold.domain.ObjectStatus;
import com.chris64233.cc.legalhold.domain.RetentionRule;
import com.chris64233.cc.legalhold.repo.DataObjectRepository;
import com.chris64233.cc.legalhold.repo.HoldMembershipRepository;
import com.chris64233.cc.legalhold.repo.RetentionRuleRepository;
import com.chris64233.cc.legalhold.time.DomainClock;
import com.chris64233.cc.legalhold.web.dto.DataObjectView;
import com.chris64233.cc.legalhold.web.dto.EligibilityView;
import com.chris64233.cc.legalhold.web.dto.RegisterObjectRequest;
import com.chris64233.cc.legalhold.web.dto.RetentionRuleRequest;
import com.chris64233.cc.legalhold.web.dto.RetentionRuleView;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ObjectService {

    private final DataObjectRepository objectRepository;
    private final RetentionRuleRepository ruleRepository;
    private final HoldMembershipRepository membershipRepository;
    private final DomainClock clock;

    public ObjectService(DataObjectRepository objectRepository,
                         RetentionRuleRepository ruleRepository,
                         HoldMembershipRepository membershipRepository,
                         DomainClock clock) {
        this.objectRepository = objectRepository;
        this.ruleRepository = ruleRepository;
        this.membershipRepository = membershipRepository;
        this.clock = clock;
    }

    @Transactional
    public DataObjectView register(RegisterObjectRequest request) {
        if (objectRepository.findByBusinessKey(request.businessKey()).isPresent()) {
            throw new ConflictException("业务标识已存在: " + request.businessKey());
        }
        DataObject saved = objectRepository.save(
                new DataObject(request.businessKey(), request.category(), clock.now()));
        return toView(saved);
    }

    @Transactional
    public RetentionRuleView saveRule(RetentionRuleRequest request) {
        RetentionRule rule = ruleRepository.findByCategory(request.category())
                .map(existing -> {
                    existing.setMinRetentionDays(request.minRetentionDays());
                    return existing;
                })
                .orElseGet(() -> new RetentionRule(request.category(), request.minRetentionDays()));
        RetentionRule saved = ruleRepository.save(rule);
        return new RetentionRuleView(saved.getCategory(), saved.getMinRetentionDays());
    }

    @Transactional(readOnly = true)
    public EligibilityView eligibility(String businessKey) {
        DataObject dataObject = requireObject(businessKey);
        Instant now = clock.now();

        Integer minRetentionDays = null;
        Instant deadline = null;
        boolean retentionSatisfied = false;
        List<String> blockingReasons = new ArrayList<>();

        RetentionRule rule = ruleRepository.findByCategory(dataObject.getCategory()).orElse(null);
        if (rule == null) {
            blockingReasons.add("类别缺少有效保留规则: " + dataObject.getCategory());
        } else {
            minRetentionDays = rule.getMinRetentionDays();
            deadline = dataObject.getCreatedAt().plus(rule.getMinRetentionDays(), ChronoUnit.DAYS);
            retentionSatisfied = !now.isBefore(deadline);
            if (!retentionSatisfied) {
                blockingReasons.add("保留期未满，保留截止时间: " + deadline);
            }
        }

        List<String> activeCases = membershipRepository
                .findByObjectIdOrderByCaseNo(dataObject.getId())
                .stream()
                .map(HoldMembership::getCaseNo)
                .toList();
        if (!activeCases.isEmpty()) {
            blockingReasons.add("存在有效法律保全案件: " + String.join(", ", activeCases));
        }

        if (dataObject.getStatus() == ObjectStatus.DELETED) {
            blockingReasons.add("对象已删除");
        }

        boolean eligible = dataObject.getStatus() != ObjectStatus.DELETED
                && retentionSatisfied
                && activeCases.isEmpty();

        return new EligibilityView(
                dataObject.getBusinessKey(),
                dataObject.getCategory(),
                dataObject.getStatus(),
                dataObject.getCreatedAt(),
                minRetentionDays,
                deadline,
                retentionSatisfied,
                activeCases,
                eligible,
                List.copyOf(blockingReasons),
                now);
    }

    private DataObject requireObject(String businessKey) {
        return objectRepository.findByBusinessKey(businessKey)
                .orElseThrow(() -> new NotFoundException("对象不存在: " + businessKey));
    }

    static DataObjectView toView(DataObject dataObject) {
        return new DataObjectView(
                dataObject.getId(),
                dataObject.getBusinessKey(),
                dataObject.getCategory(),
                dataObject.getCreatedAt(),
                dataObject.getStatus());
    }
}
