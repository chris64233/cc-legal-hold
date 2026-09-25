package com.chris64233.cc.legalhold.web.dto;

import com.chris64233.cc.legalhold.domain.DeletionTokenStatus;

/**
 * @param alreadyConfirmed true 表示这是重复确认，返回原结果且未再次执行删除
 */
public record DeletionConfirmView(
        String token,
        DeletionTokenStatus status,
        String businessKey,
        boolean deleted,
        boolean alreadyConfirmed,
        String rejectReason) {
}
