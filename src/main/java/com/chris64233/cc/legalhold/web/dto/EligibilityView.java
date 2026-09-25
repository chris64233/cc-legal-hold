package com.chris64233.cc.legalhold.web.dto;

import com.chris64233.cc.legalhold.domain.ObjectStatus;
import java.time.Instant;
import java.util.List;

/**
 * 删除资格解释。
 */
public record EligibilityView(
        String businessKey,
        String category,
        ObjectStatus status,
        Instant createdAt,
        Integer minRetentionDays,
        Instant retentionDeadline,
        boolean retentionSatisfied,
        List<String> activeHoldCases,
        boolean eligible,
        List<String> blockingReasons,
        Instant evaluatedAt) {
}
