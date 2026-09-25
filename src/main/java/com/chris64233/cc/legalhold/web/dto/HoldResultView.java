package com.chris64233.cc.legalhold.web.dto;

import com.chris64233.cc.legalhold.domain.HoldEventType;
import java.time.Instant;
import java.util.List;

public record HoldResultView(
        String eventNo,
        String caseNo,
        HoldEventType eventType,
        List<String> businessKeys,
        String reason,
        Instant effectiveAt) {
}
