package com.chris64233.cc.legalhold.web.dto;

/**
 * 对象当前受到的单条保全来源。scopeManaged=false 表示该案件是手工保全（无范围版本）。
 */
public record ObjectHoldView(
        String caseNo,
        boolean scopeManaged,
        Integer effectiveVersionNo,
        String effectiveSinceChangeNo,
        String caseStatus) {
}
