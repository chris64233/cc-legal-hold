package com.chris64233.cc.legalhold.time;

import java.time.Instant;

/**
 * 业务时间来源。所有保留期、生效时间、令牌有效期均以此为准，便于测试替换。
 */
public interface DomainClock {

    Instant now();
}
