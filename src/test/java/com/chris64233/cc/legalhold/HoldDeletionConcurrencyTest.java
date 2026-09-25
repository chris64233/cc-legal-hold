package com.chris64233.cc.legalhold;

import static org.assertj.core.api.Assertions.assertThat;

import com.chris64233.cc.legalhold.domain.DataObject;
import com.chris64233.cc.legalhold.domain.DeletionTokenStatus;
import com.chris64233.cc.legalhold.repo.DataObjectRepository;
import com.chris64233.cc.legalhold.repo.DeletionTokenRepository;
import com.chris64233.cc.legalhold.repo.HoldMembershipRepository;
import com.chris64233.cc.legalhold.service.DeletionService;
import com.chris64233.cc.legalhold.service.LegalHoldService;
import com.chris64233.cc.legalhold.service.ObjectService;
import com.chris64233.cc.legalhold.support.MutableTestClock;
import com.chris64233.cc.legalhold.support.TestClockConfig;
import com.chris64233.cc.legalhold.web.dto.DeletionRequestView;
import com.chris64233.cc.legalhold.web.dto.HoldRequest;
import com.chris64233.cc.legalhold.web.dto.RegisterObjectRequest;
import com.chris64233.cc.legalhold.web.dto.RetentionRuleRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 保全加入与删除确认并发时，不得出现“已有有效保全却删除成功”。
 */
@SpringBootTest(classes = {CcLegalHoldApplication.class, TestClockConfig.class})
class HoldDeletionConcurrencyTest {

    private static final int PAIRS = 24;

    @Autowired
    private DeletionService deletionService;
    @Autowired
    private LegalHoldService legalHoldService;
    @Autowired
    private ObjectService objectService;
    @Autowired
    private DataObjectRepository objectRepository;
    @Autowired
    private HoldMembershipRepository membershipRepository;
    @Autowired
    private DeletionTokenRepository tokenRepository;
    @Autowired
    private MutableTestClock clock;

    private final List<String> businessKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock.setTime(Instant.parse("2026-01-01T00:00:00Z"));
        tokenRepository.deleteAll();
        membershipRepository.deleteAll();
        objectRepository.deleteAll();
        objectService.saveRule(new RetentionRuleRequest("DOC", 30));
        for (int i = 0; i < PAIRS; i++) {
            String key = "c-" + i;
            objectService.register(new RegisterObjectRequest(key, "DOC"));
            businessKeys.add(key);
        }
        clock.advanceDays(31);
    }

    @Test
    void holdApplyAndDeleteConfirmAreSerializable() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String key : businessKeys) {
                CyclicBarrier barrier = new CyclicBarrier(2);
                DeletionRequestView request = deletionService.requestDeletion(key);

                futures.add(pool.submit(() -> {
                    barrier.await();
                    try {
                        legalHoldService.apply(new HoldRequest(
                                "CASE-" + key, List.of(key), "并发保全"));
                    } catch (com.chris64233.cc.legalhold.service.ConflictException expectedWhenDeleteWins) {
                        // 删除先提交后，已删除对象不能再加入保全
                    }
                    return null;
                }));
                futures.add(pool.submit(() -> {
                    barrier.await();
                    try {
                        deletionService.confirmDeletion(request.token());
                    } catch (RuntimeException expectedWhenHoldWins) {
                        // 保全先拿到锁时，确认被拒绝并使令牌失效
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        for (String key : businessKeys) {
            DataObject dataObject = objectRepository.findByBusinessKey(key).orElseThrow();
            boolean holdActive = membershipRepository
                    .findByCaseNoAndObjectId("CASE-" + key, dataObject.getId())
                    .isPresent();
            if (holdActive) {
                assertThat(dataObject.getStatus())
                        .as("存在有效保全时对象不能被删除: " + key)
                        .isNotEqualTo(com.chris64233.cc.legalhold.domain.ObjectStatus.DELETED);
            } else if (dataObject.getStatus()
                    == com.chris64233.cc.legalhold.domain.ObjectStatus.DELETED) {
                assertThat(tokenRepository.findAll().stream()
                        .filter(token -> token.getObjectId().equals(dataObject.getId()))
                        .findFirst().orElseThrow().getStatus())
                        .isEqualTo(DeletionTokenStatus.CONFIRMED);
            }
        }
    }
}
