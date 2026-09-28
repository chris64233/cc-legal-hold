package com.chris64233.cc.legalhold.web.dto;

import com.chris64233.cc.legalhold.domain.ScopeVersionStatus;
import java.time.Instant;
import java.util.List;

/**
 * 范围变更审批进度。requiredApprovals 固定为 2，批准人必须互不相同且不能是申请人。
 */
public record ApprovalProgressView(
        String changeNo,
        String caseNo,
        int versionNo,
        String requestedBy,
        ScopeVersionStatus versionStatus,
        int requiredApprovals,
        int approvalCount,
        List<ApprovalView> approvals,
        boolean complete,
        boolean effective,
        boolean rejected,
        String rejectReason,
        List<String> rejectReasons,
        Instant effectiveAt) {
}
