package com.chris64233.cc.legalhold.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RetentionRuleRequest(
        @NotBlank @Size(max = 64) String category,
        @NotNull @Min(0) Integer minRetentionDays) {
}
