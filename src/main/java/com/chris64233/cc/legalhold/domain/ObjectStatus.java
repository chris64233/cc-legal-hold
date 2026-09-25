package com.chris64233.cc.legalhold.domain;

/**
 * 数据对象生命周期状态。
 * DELETED 为终态，表示不可恢复删除。
 */
public enum ObjectStatus {
    ACTIVE,
    PENDING_DELETION,
    DELETED
}
