package com.chris64233.cc.legalhold.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 单个审批事件视图。
 */
public record ApprovalView(
        String changeNo,
        String caseNo,
        String approver,
        String comment,
        Instant approvedAt) {
}
