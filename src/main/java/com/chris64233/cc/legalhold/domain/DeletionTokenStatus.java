package com.chris64233.cc.legalhold.domain;

/**
 * 一次性删除确认令牌状态。
 */
public enum DeletionTokenStatus {
    PENDING,
    CONFIRMED,
    REJECTED,
    EXPIRED
}
