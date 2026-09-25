package com.chris64233.cc.legalhold.time;

import java.time.Instant;

public class SystemDomainClock implements DomainClock {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
