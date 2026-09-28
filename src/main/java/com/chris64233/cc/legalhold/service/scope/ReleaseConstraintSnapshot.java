package com.chris64233.cc.legalhold.service.scope;

import java.time.Instant;
import java.util.List;

/**
 * 待释放对象在缩围/关闭提交（双批准开始）时的约束快照。
 * 终确认时逐项重算，任一字段变化即整批拒绝。
 *
 * @param objectId          对象 ID
 * @param businessKey       对象业务标识
 * @param otherCases        提交时该对象受到的其他案件保全（不含本案）
 * @param retentionDeadline 提交时保留截止时间（保留规则缺失时为 null，
 *                          由 {@code retentionRuleMissing} 标记）
 * @param retentionRuleMissing 提交时类别是否缺少保留规则（缺失本身也是约束，
 *                          会阻断删除，缺失→存在也视为变化）
 * @param category          保留规则按类别匹配，记录以防规则维度混淆
 */
public record ReleaseConstraintSnapshot(
        Long objectId,
        String businessKey,
        List<String> otherCases,
        Instant retentionDeadline,
        boolean retentionRuleMissing,
        String category) {
}
