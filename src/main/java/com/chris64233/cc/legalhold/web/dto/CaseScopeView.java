package com.chris64233.cc.legalhold.web.dto;

import com.chris64233.cc.legalhold.domain.CaseStatus;
import java.util.List;

/**
 * 案件范围状态：案件状态、当前生效版本号与全部版本历史（含计算中/待批/被拒）。
 */
public record CaseScopeView(
        String caseNo,
        CaseStatus status,
        Integer currentVersionNo,
        List<ScopeChangeView> versions) {
}
