package com.chris64233.cc.legalhold.domain;

/**
 * 范围版本状态。
 *
 * <p>COMPUTING：按条件分批计算成员中，对外不可见；
 * PENDING_APPROVAL：缩小范围版本待两名不同人员批准；
 * EFFECTIVE：当前唯一生效版本（对外可见的完整范围）；
 * SUPERSEDED：已被新版本取代的历史不可变版本；
 * REJECTED：终确认时条件变化被整批拒绝的版本。
 */
public enum ScopeVersionStatus {
    COMPUTING,
    PENDING_APPROVAL,
    EFFECTIVE,
    SUPERSEDED,
    REJECTED
}
