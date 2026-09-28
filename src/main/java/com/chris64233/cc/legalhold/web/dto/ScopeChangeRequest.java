package com.chris64233.cc.legalhold.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 案件范围变更请求。{@code changeNo} 是调用方提供的变更业务号，全局唯一、幂等：
 * 同一业务号重复提交返回同一版本，不会产生第二条变更。
 *
 * @param closeCase 是否关闭案件；字段缺省视为 false（目标范围强制为空，需双批准）
 */
public record ScopeChangeRequest(
        @NotBlank @Size(max = 64) String changeNo,
        @NotNull @Valid ScopeCriteriaRequest criteria,
        Boolean closeCase,
        @NotBlank @Size(max = 64) String requestedBy,
        @NotBlank @Size(max = 512) String reason,
        @Min(1) Integer batchSize) {

    public boolean isCloseCase() {
        return Boolean.TRUE.equals(closeCase);
    }
}
