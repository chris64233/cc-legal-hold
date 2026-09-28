package com.chris64233.cc.legalhold.web.dto;

import com.chris64233.cc.legalhold.domain.ApprovalVoteType;
import java.time.Instant;

/**
 * 单条审批事件。
 */
public record ApprovalEventView(
        String reviewer,
        ApprovalVoteType vote,
        String comment,
        Instant votedAt) {
}
