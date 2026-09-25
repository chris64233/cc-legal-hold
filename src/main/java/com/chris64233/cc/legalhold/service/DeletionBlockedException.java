package com.chris64233.cc.legalhold.service;

import java.util.List;

/**
 * 删除条件不满足（保留期未满、存在有效保全或对象已删除）。
 */
public class DeletionBlockedException extends RuntimeException {

    private final List<String> reasons;

    public DeletionBlockedException(List<String> reasons) {
        super(String.join("; ", reasons));
        this.reasons = List.copyOf(reasons);
    }

    public List<String> getReasons() {
        return reasons;
    }
}
