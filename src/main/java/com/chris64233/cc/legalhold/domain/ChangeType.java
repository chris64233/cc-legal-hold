package com.chris64233.cc.legalhold.domain;

/**
 * 案件范围变更类型。
 * EXPAND：仅新增对象，创建版本后立即生效；
 * SHRINK：存在移除对象，必须两名不同人员批准后才生效；
 * CLOSE：目标范围为空，关闭案件，同样需要双人批准。
 */
public enum ChangeType {
    EXPAND,
    SHRINK,
    CLOSE
}
