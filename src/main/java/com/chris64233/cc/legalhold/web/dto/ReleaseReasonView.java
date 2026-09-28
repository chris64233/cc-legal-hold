package com.chris64233.cc.legalhold.web.dto;

import com.chris64233.cc.legalhold.domain.HoldEventType;
import java.time.Instant;

/**
 * 释放（解除保全）原因查询的单条记录，来自只追加的保全事件流。
 */
public record ReleaseReasonView(
        String eventNo,
        String changeNo,
        String caseNo,
        HoldEventType eventType,
        String reason,
        Instant effectiveAt) {
}
