package com.chris64233.cc.legalhold.web.dto;

/**
 * 对象当前受到的单条保全。
 */
public record ObjectHoldView(
        String caseNo,
        Integer effectiveVersionNo,
        java.time.Instant establishedAt,
        boolean caseClosed) {
}
