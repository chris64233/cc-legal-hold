package com.chris64233.cc.legalhold.web.dto;

import com.chris64233.cc.legalhold.domain.ChangeType;
import com.chris64233.cc.legalhold.domain.ScopeVersionStatus;
import java.time.Instant;

/**
 * 范围变更/版本视图。无论内部分多少批物化，对外只暴露一个完整版本。
 */
public record ScopeVersionView(
        String caseNo,
        int versionNo,
        String changeNo,
        ChangeType changeType,
        ScopeVersionStatus status,
        int basedOnVersionNo,
        ScopeCriteriaRequest criteria,
        String reason,
        String createdBy,
        Instant createdAt,
        Instant effectiveAt,
        int addedCount,
        int retainedCount,
        int removedCount,
        String rejectReason,
        boolean idempotentReplay) {
}
