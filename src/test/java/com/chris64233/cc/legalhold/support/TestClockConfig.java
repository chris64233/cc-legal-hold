package com.chris64233.cc.legalhold.support;

import com.chris64233.cc.legalhold.time.DomainClock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public DomainClock mutableTestClock() {
        return new MutableTestClock();
    }
}
