package com.chris64233.cc.legalhold.service.scope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 服务启动后恢复所有计算中断的范围版本，保证重启后不会遗留半计算状态。
 */
@Component
public class ScopeRecoveryRunner {

    private static final Logger log = LoggerFactory.getLogger(ScopeRecoveryRunner.class);

    private final CaseScopeService caseScopeService;

    public ScopeRecoveryRunner(CaseScopeService caseScopeService) {
        this.caseScopeService = caseScopeService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            caseScopeService.recoverInterruptedComputations();
        } catch (RuntimeException e) {
            log.error("启动恢复范围版本失败，等待再次触发或人工介入", e);
        }
    }
}
