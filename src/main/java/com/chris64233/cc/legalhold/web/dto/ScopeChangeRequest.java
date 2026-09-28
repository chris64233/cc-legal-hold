package com.chris64233.cc.legalhold.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 案件范围变更请求。changeNo 为客户端提供的变更业务号，服务端保证幂等：
 * 同一 changeNo 重复提交只会返回同一个范围版本，绝不产生第二份效果。
 */
public record ScopeChangeRequest(
        @NotBlank @Size(max = 64) String caseNo,
        @NotBlank @Size(max = 64) String changeNo,
        @NotBlank @Size(max = 512) String reason,
        @Size(max = 64) String createdBy,
        @NotNull @Valid ScopeCriteriaRequest criteria) {
}
