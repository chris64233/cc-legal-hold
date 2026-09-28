package com.chris64233.cc.legalhold.web.dto;

import com.chris64233.cc.legalhold.domain.ScopeVersionStatus;
import java.util.List;

/**
 * 审批进度：需要两名不同人员批准，列出已投票事件与当前审批计数。
 */
public record ApprovalProgressView(
        String caseNo,
        String changeNo,
        int versionNo,
        ScopeVersionStatus status,
        int requiredApprovals,
        int approveCount,
        List<String> approvers,
        List<ApprovalEventView> events,
        String rejectReason) {
}
