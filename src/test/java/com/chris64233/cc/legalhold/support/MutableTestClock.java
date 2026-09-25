package com.chris64233.cc.legalhold.support;

import com.chris64233.cc.legalhold.time.DomainClock;
import java.time.Instant;

/**
 * 测试用可控时钟。以 @Primary 覆盖生产环境的 SystemDomainClock。
 */
public class MutableTestClock implements DomainClock {

    private volatile Instant current = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public Instant now() {
        return current;
    }

    public void setTime(Instant instant) {
        this.current = instant;
    }

    public void advanceSeconds(long seconds) {
        this.current = current.plusSeconds(seconds);
    }

    public void advanceDays(long days) {
        this.current = current.plusSeconds(days * 24L * 60 * 60);
    }
}
