package com.chris64233.cc.legalhold.service;

import com.chris64233.cc.legalhold.domain.DeletionToken;
import java.time.Instant;

public record ConfirmationResult(
        String tokenValue,
        DeletionToken.Outcome outcome,
        boolean deleted,
        String detail,
        Instant resolvedAt) {

    static ConfirmationResult of(DeletionToken token, boolean deleted) {
        return new ConfirmationResult(token.getTokenValue(), token.getOutcome(), deleted,
                token.getOutcomeDetail(), token.getResolvedAt());
    }
}
