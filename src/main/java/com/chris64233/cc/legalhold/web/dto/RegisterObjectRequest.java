package com.chris64233.cc.legalhold.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterObjectRequest(
        @NotBlank @Size(max = 128) String businessKey,
        @NotBlank @Size(max = 64) String category) {
}
