package com.chris64233.cc.legalhold.web.dto;

import java.time.Instant;

/**
 * 对象释放原因（来自 hold_event 的 RELEASE 记录）。
 */
public record ReleaseReasonView(
        String caseNo,
        String eventNo,
        String changeNo,
        Integer versionNo,
        String reason,
        Instant releasedAt) {
}
