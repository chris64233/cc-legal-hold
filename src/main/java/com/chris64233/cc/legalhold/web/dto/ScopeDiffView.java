package com.chris64233.cc.legalhold.web.dto;

import java.util.List;

/**
 * 两个范围版本之间的成员差异。fromVersionNo 省略时与空版本比较（即该版本全部为新增）。
 */
public record ScopeDiffView(
        String caseNo,
        Integer fromVersionNo,
        int toVersionNo,
        int fromCount,
        int toCount,
        List<String> addedBusinessKeys,
        List<String> removedBusinessKeys,
        List<String> unchangedBusinessKeys) {
}
