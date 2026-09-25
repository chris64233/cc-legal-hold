package com.chris64233.cc.legalhold.service;

import com.chris64233.cc.legalhold.domain.DataObjectStatus;
import java.time.Instant;
import java.util.List;

public record EligibilityResult(
        String businessKey,
        DataObjectStatus status,
        boolean eligible,
        Instant retentionDeadline,
        boolean retentionSatisfied,
        List<String> activeCaseNumbers,
        List<String> blockingReasons) {
}
