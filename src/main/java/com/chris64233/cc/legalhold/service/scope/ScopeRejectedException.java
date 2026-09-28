package com.chris64233.cc.legalhold.service.scope;

import java.util.List;

/**
 * 整批拒绝异常：缩围/关闭终确认时任一对象约束变化，或生效时发现对象已删除。
 * 拒绝状态已在独立事务中落库后抛出，映射为 HTTP 409。
 */
public class ScopeRejectedException extends RuntimeException {

    private final List<String> reasons;

    public ScopeRejectedException(String message, List<String> reasons) {
        super(message);
        this.reasons = List.copyOf(reasons);
    }

    public List<String> getReasons() {
        return reasons;
    }
}
