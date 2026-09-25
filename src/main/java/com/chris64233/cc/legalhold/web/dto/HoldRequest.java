package com.chris64233.cc.legalhold.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 保全纳入/解除请求。事件号与生效时间由服务端生成，客户端不可声明。
 */
public record HoldRequest(
        @NotBlank @Size(max = 64) String caseNo,
        @NotEmpty List<@NotBlank @Size(max = 128) String> businessKeys,
        @NotBlank @Size(max = 512) String reason) {
}
