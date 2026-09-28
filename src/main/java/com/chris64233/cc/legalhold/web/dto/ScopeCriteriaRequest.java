package com.chris64233.cc.legalhold.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * 范围生成条件。对象命中任一条件即纳入目标范围：
 * 显式对象标识，或（类别命中且创建时间落在 [createdFrom, createdTo) 区间，边界可空表示不限）。
 */
public record ScopeCriteriaRequest(
        List<@NotBlank @Size(max = 128) String> businessKeys,
        List<@NotBlank @Size(max = 64) String> categories,
        Instant createdFrom,
        Instant createdTo) {

    public boolean isEmpty() {
        return (businessKeys == null || businessKeys.isEmpty())
                && (categories == null || categories.isEmpty())
                && createdFrom == null
                && createdTo == null;
    }
}
