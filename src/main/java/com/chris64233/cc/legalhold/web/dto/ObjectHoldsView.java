package com.chris64233.cc.legalhold.web.dto;

import com.chris64233.cc.legalhold.domain.ObjectStatus;
import java.time.Instant;
import java.util.List;

/**
 * 对象受到的全部保全，含范围版本来源与案件状态。
 */
public record ObjectHoldsView(
        String businessKey,
        String category,
        ObjectStatus status,
        Instant createdAt,
        int holdCount,
        List<ObjectHoldView> holds) {
}
