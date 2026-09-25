package com.chris64233.cc.legalhold.web.dto;

import com.chris64233.cc.legalhold.domain.ObjectStatus;
import java.time.Instant;

public record DataObjectView(
        Long id,
        String businessKey,
        String category,
        Instant createdAt,
        ObjectStatus status) {
}
