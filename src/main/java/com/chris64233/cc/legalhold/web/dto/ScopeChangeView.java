package com.chris64233.cc.legalhold.web.dto;

import com.chris64233.cc.legalhold.domain.ScopeChangeType;
import com.chris64233.cc.legalhold.domain.ScopeVersionStatus;
import java.time.Instant;
import java.util.List;

/**
 * 范围变更提交/续算/生效后的版本视图。计算中版本对外不代表有效范围。
 */
public record ScopeChangeView(
        String changeNo,
        String caseNo,
        int versionNo,
        Integer baseVersionNo,
        ScopeChangeType changeType,
        ScopeVersionStatus status,
        ScopeCriteriaRequest criteria,
        boolean closeCase,
        boolean computeDone,
        Long cursorId,
        Integer memberCount,
        Integer addedCount,
        Integer removedCount,
        boolean effective,
        Integer currentVersionNo,
        Instant createdAt,
        Instant effectiveAt,
        String rejectReason,
        List<String> rejectReasons) {
}
