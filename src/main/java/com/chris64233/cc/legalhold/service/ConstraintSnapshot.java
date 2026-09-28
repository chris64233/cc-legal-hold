package com.chris64233.cc.legalhold.service;

import java.util.List;

/**
 * 待释放对象在范围版本物化完成时的约束快照。最终确认时逐项与当前状态比对，
 * 任一项变化（其他案件保全、保留规则/届满状态、当前生效版本、本案保全是否仍在）
 * 都判定为“条件变化”，整批拒绝。
 */
public record ConstraintSnapshot(
        Long objectId,
        String businessKey,
        String category,
        Integer minRetentionDays,
        String retentionDeadline,
        boolean retentionSatisfied,
        List<String> otherCases,
        int currentVersionNo,
        boolean caseMembershipActive) {
}
