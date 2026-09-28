package com.chris64233.cc.legalhold.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 案件范围条件。命中规则为“显式对象标识”与“类别 + 创建时间窗口”的并集：
 * 显式列出的 businessKey 始终纳入（必须已登记）；类别/时间条件按对象属性过滤。
 * 所有字段为空表示不限条件（关闭案件时条件被忽略，新版本范围强制为空）。
 *
 * @param businessKeys  显式纳入的对象业务标识
 * @param categories    命中类别集合，空表示不限类别
 * @param createdAfter  创建时间下界（含），空表示不限
 * @param createdBefore 创建时间上界（含），空表示不限
 */
public record ScopeCriteriaRequest(
        List<@jakarta.validation.constraints.NotBlank String> businessKeys,
        List<@jakarta.validation.constraints.NotBlank String> categories,
        Instant createdAfter,
        Instant createdBefore) {

    public List<String> safeBusinessKeys() {
        return businessKeys == null ? List.of() : businessKeys;
    }

    public List<String> safeCategories() {
        return categories == null ? List.of() : categories;
    }
}
