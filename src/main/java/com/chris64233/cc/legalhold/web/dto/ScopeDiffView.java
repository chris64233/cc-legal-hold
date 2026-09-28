package com.chris64233.cc.legalhold.web.dto;

import java.util.List;

/**
 * 两个范围版本之间的差异：新增对象、移除对象、保留对象（业务标识）。
 */
public record ScopeDiffView(
        String caseNo,
        int fromVersionNo,
        int toVersionNo,
        List<String> addedBusinessKeys,
        List<String> removedBusinessKeys,
        List<String> retainedBusinessKeys) {
}
