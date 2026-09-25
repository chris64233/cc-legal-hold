package com.chris64233.cc.legalhold.time;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    @Bean
    @ConditionalOnMissingBean(DomainClock.class)
    public DomainClock domainClock() {
        return new SystemDomainClock();
    }
}
