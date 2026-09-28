package com.chris64233.cc.legalhold.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ScopeApprovalRequest(
        @NotBlank @Size(max = 64) String approver,
        @Size(max = 512) String comment) {
}
