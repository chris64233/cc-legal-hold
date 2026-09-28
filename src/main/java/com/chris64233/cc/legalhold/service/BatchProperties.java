package com.chris64233.cc.legalhold.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 范围物化分批参数。
 */
@Component
@ConfigurationProperties(prefix = "legalhold.scope")
public class BatchProperties {

    /** 单批扫描/写入的对象数量。 */
    private int materializeBatchSize = 500;

    /** 单次请求内最多续跑的批次数，防止超大范围长时间占用请求。 */
    private int maxBatchesPerCall = 20;

    public int getMaterializeBatchSize() {
        return materializeBatchSize;
    }

    public void setMaterializeBatchSize(int materializeBatchSize) {
        this.materializeBatchSize = materializeBatchSize;
    }

    public int getMaxBatchesPerCall() {
        return maxBatchesPerCall;
    }

    public void setMaxBatchesPerCall(int maxBatchesPerCall) {
        this.maxBatchesPerCall = maxBatchesPerCall;
    }
}
