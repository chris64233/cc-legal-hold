package com.chris64233.cc.legalhold.web.dto;

import java.time.Instant;

public record DeletionRequestView(
        String token,
        Instant expiresAt) {
}
