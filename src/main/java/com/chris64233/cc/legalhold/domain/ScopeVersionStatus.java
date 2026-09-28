package com.chris64233.cc.legalhold.domain;

/**
 * 案件范围版本状态。版本行一经写入不可修改，状态只能沿既定方向推进：
 * PENDING_MATERIALIZE -> PENDING_APPROVAL / EFFECTIVE / REJECTED
 * PENDING_APPROVAL    -> EFFECTIVE / REJECTED
 * EFFECTIVE           -> SUPERSEDED（被更新的生效版本取代）
 */
public enum ScopeVersionStatus {
    PENDING_MATERIALIZE,
    PENDING_APPROVAL,
    EFFECTIVE,
    SUPERSEDED,
    REJECTED
}
