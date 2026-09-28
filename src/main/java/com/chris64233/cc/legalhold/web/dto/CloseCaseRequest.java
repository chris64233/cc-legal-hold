package com.chris64233.cc.legalhold.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 关闭案件请求：目标范围视为空集，整案释放需双人批准。
 */
public record CloseCaseRequest(
        @NotBlank @Size(max = 64) String caseNo,
        @NotBlank @Size(max = 64) String changeNo,
        @NotBlank @Size(max = 512) String reason,
        @Size(max = 64) String createdBy) {
}
