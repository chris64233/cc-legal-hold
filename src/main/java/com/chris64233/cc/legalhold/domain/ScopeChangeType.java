package com.chris64233.cc.legalhold.domain;

/**
 * 范围变更方向。
 * EXPAND：仅新增对象（或范围完全相同），无释放风险，计算完成后直接生效；
 * SHRINK：存在移除对象，必须两名不同人员批准后方可释放。
 */
public enum ScopeChangeType {
    CREATE,
    EXPAND,
    SHRINK
}
