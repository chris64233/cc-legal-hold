package com.chris64233.cc.legalhold.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 审批投票。缩围/关案必须由两名互不相同、且不同于申请人的人员分别 APPROVE。
 */
public record ApprovalRequest(
        @NotBlank @Size(max = 64) String changeNo,
        @NotBlank @Size(max = 64) String reviewer,
        @NotBlank @Size(max = 16) String vote,
        @Size(max = 512) String comment) {
}
