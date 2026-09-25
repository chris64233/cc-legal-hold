package com.chris64233.cc.legalhold.service;

import com.chris64233.cc.legalhold.domain.DataObject;
import com.chris64233.cc.legalhold.domain.DataObjectStatus;
import com.chris64233.cc.legalhold.domain.HoldAssignment;
import com.chris64233.cc.legalhold.domain.RetentionRule;
import com.chris64233.cc.legalhold.repo.HoldAssignmentRepository;
import com.chris64233.cc.legalhold.repo.LegalHoldCaseRepository;
import com.chris64233.cc.legalhold.repo.RetentionRuleRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EligibilityService {

    private final RetentionRuleRepository ruleRepository;
    private final HoldAssignmentRepository assignmentRepository;
    private final LegalHoldCaseRepository caseRepository;
    private final Clock clock;

    public EligibilityService(RetentionRuleRepository ruleRepository,
                              HoldAssignmentRepository assignmentRepository,
                              LegalHoldCaseRepository caseRepository,
                              Clock clock) {
        this.ruleRepository = ruleRepository;
        this.assignmentRepository = assignmentRepository;
        this.caseRepository = caseRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public EligibilityResult evaluate(DataObject object) {
        Instant now = clock.instant();
        List<String> blockingReasons = new ArrayList<>();

        Instant deadline = null;
        boolean retentionSatisfied = false;
        Optional<RetentionRule> rule = ruleRepository.findByCategory(object.getCategory());
        if (rule.isEmpty()) {
            blockingReasons.add("类别 " + object.getCategory() + " 未配置保留规则");
        } else {
            deadline = object.getCreatedAt().plus(rule.get().getMinRetentionDays(), ChronoUnit.DAYS);
            retentionSatisfied = !now.isBefore(deadline);
            if (!retentionSatisfied) {
                blockingReasons.add("保留期未届满，截止时间为 " + deadline);
            }
        }

        List<String> activeCases = assignmentRepository.findActiveByObjectId(object.getId()).stream()
                .map(HoldAssignment::getCaseId)
                .map(caseId -> caseRepository.findById(caseId)
                        .map(c -> c.getCaseNumber())
                        .orElse(caseId.toString()))
                .sorted()
                .toList();
        if (!activeCases.isEmpty()) {
            blockingReasons.add("存在有效法律保全案件: " + String.join(", ", activeCases));
        }

        if (object.getStatus() == DataObjectStatus.DELETED) {
            blockingReasons.add("对象已被删除");
        }

        boolean eligible = object.getStatus() == DataObjectStatus.ACTIVE
                && retentionSatisfied
                && activeCases.isEmpty();
        return new EligibilityResult(object.getBusinessKey(), object.getStatus(), eligible,
                deadline, retentionSatisfied, activeCases, List.copyOf(blockingReasons));
    }
}
