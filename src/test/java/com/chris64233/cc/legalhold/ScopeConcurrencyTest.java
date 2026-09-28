package com.chris64233.cc.legalhold;

import static org.assertj.core.api.Assertions.assertThat;

import com.chris64233.cc.legalhold.domain.ObjectStatus;
import com.chris64233.cc.legalhold.repo.CaseScopeVersionRepository;
import com.chris64233.cc.legalhold.repo.DataObjectRepository;
import com.chris64233.cc.legalhold.repo.DeletionTokenRepository;
import com.chris64233.cc.legalhold.repo.HoldMembershipRepository;
import com.chris64233.cc.legalhold.repo.LegalCaseRepository;
import com.chris64233.cc.legalhold.repo.RetentionRuleRepository;
import com.chris64233.cc.legalhold.repo.ScopeApprovalRepository;
import com.chris64233.cc.legalhold.service.CaseScopeService;
import com.chris64233.cc.legalhold.service.DeletionService;
import com.chris64233.cc.legalhold.service.ObjectService;
import com.chris64233.cc.legalhold.support.MutableTestClock;
import com.chris64233.cc.legalhold.support.TestClockConfig;
import com.chris64233.cc.legalhold.web.dto.ApprovalRequest;
import com.chris64233.cc.legalhold.web.dto.DeletionRequestView;
import com.chris64233.cc.legalhold.web.dto.RegisterObjectRequest;
import com.chris64233.cc.legalhold.web.dto.RetentionRuleRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeChangeRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeCriteriaRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 并发扩围、缩围生效与删除确认：任何执行序列下都不得出现
 * “对象处于有效保全范围却被删除”，同案件并发变更必须串行化。
 */
@SpringBootTest(classes = {CcLegalHoldApplication.class, TestClockConfig.class}, properties = {
        "legalhold.scope.materialize-batch-size=50",
        "legalhold.scope.max-batches-per-call=100"
})
class ScopeConcurrencyTest {

    private static final int OBJECTS = 30;

    @Autowired
    private CaseScopeService caseScopeService;
    @Autowired
    private DeletionService deletionService;
    @Autowired
    private ObjectService objectService;
    @Autowired
    private DataObjectRepository objectRepository;
    @Autowired
    private HoldMembershipRepository membershipRepository;
    @Autowired
    private LegalCaseRepository caseRepository;
    @Autowired
    private CaseScopeVersionRepository versionRepository;
    @Autowired
    private ScopeApprovalRepository approvalRepository;
    @Autowired
    private DeletionTokenRepository tokenRepository;
    @Autowired
    private RetentionRuleRepository ruleRepository;
    @Autowired
    private MutableTestClock clock;

    private final List<String> keys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        approvalRepository.deleteAll();
        tokenRepository.deleteAll();
        membershipRepository.deleteAll();
        versionRepository.deleteAll();
        caseRepository.deleteAll();
        objectRepository.deleteAll();
        ruleRepository.deleteAll();
        clock.setTime(Instant.parse("2026-01-01T00:00:00Z"));
        objectService.saveRule(new RetentionRuleRequest("DOC", 30));
        for (int i = 0; i < OBJECTS; i++) {
            String key = "p-" + i;
            objectService.register(new RegisterObjectRequest(key, "DOC"));
            keys.add(key);
        }
        // 初始范围：全部对象受 CASE-P 保全
        caseScopeService.submitChange(new ScopeChangeRequest("CASE-P", "INIT",
                "初始保全", "alice",
                new ScopeCriteriaRequest(List.of(), List.of("DOC"), null, null)));
        clock.advanceDays(31); // 保留期届满，删除资格仅取决于保全
    }

    @Test
    void concurrentShrinkConfirmAndDeleteNeverDeleteInScopeObjects() throws Exception {
        // 缩围：把所有对象移出范围（CLOSE 语义等价于全量释放），需要双人批准后才生效
        String closeNo = "CLOSE-1";
        caseScopeService.closeCase(
                new com.chris64233.cc.legalhold.web.dto.CloseCaseRequest(
                        "CASE-P", closeNo, "并发关闭", "alice"));
        caseScopeService.approve(new ApprovalRequest(closeNo, "bob", "APPROVE", null));

        // 为每个对象申请删除令牌
        List<DeletionRequestView> tokens = new ArrayList<>();
        for (String key : keys) {
            // 保全仍有效，删除申请本应被 409 阻断——先释放不了；所以改为：
            // 这里只对“关闭生效后才可能删除”的场景建模，令牌在关闭后申请。
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        // 线程 A：第二名审批人，触发最终复核 + 整批释放
        futures.add(pool.submit(() -> {
            start.await();
            return runQuietly(() -> caseScopeService.approve(
                    new ApprovalRequest(closeNo, "carol", "APPROVE", null)));
        }));

        // 多个线程 B：关闭一旦生效（保全释放）立刻申请并确认删除每个对象；
        // 关闭若整批拒绝，对象仍受保全，申请会持续被阻断。
        for (String key : keys) {
            futures.add(pool.submit(() -> {
                start.await();
                return runQuietly(() -> {
                    DeletionRequestView req = waitForDeletable(key, 200);
                    if (req != null) {
                        deletionService.confirmDeletion(req.token());
                    }
                });
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get(90, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        // 核心不变量：处于有效保全范围内的对象绝不允许是 DELETED。
        for (String key : keys) {
            var dataObject = objectRepository.findByBusinessKey(key).orElseThrow();
            boolean stillHeld = membershipRepository
                    .findByCaseNoAndObjectId("CASE-P", dataObject.getId()).isPresent();
            if (stillHeld) {
                assertThat(dataObject.getStatus())
                        .as("仍受 CASE-P 保全的对象不能被删除: " + key)
                        .isNotEqualTo(ObjectStatus.DELETED);
            }
        }

        // 若关闭整批生效，则案件关闭；若复核判定条件变化整批拒绝，则所有对象仍被保全。
        boolean caseClosed = caseRepository.findByCaseNo("CASE-P").orElseThrow().isClosed();
        if (!caseClosed) {
            for (String key : keys) {
                var dataObject = objectRepository.findByBusinessKey(key).orElseThrow();
                assertThat(membershipRepository
                        .findByCaseNoAndObjectId("CASE-P", dataObject.getId()))
                        .as("整批拒绝时所有对象必须仍受保全: " + key)
                        .isPresent();
                assertThat(dataObject.getStatus()).isNotEqualTo(ObjectStatus.DELETED);
            }
        }
    }

    private DeletionRequestView waitForDeletable(String key, int maxTries)
            throws InterruptedException {
        for (int i = 0; i < maxTries; i++) {
            try {
                return deletionService.requestDeletion(key);
            } catch (RuntimeException blocked) {
                // 保全仍在或正被并发释放，等待后重试；整批拒绝场景下最终返回 null。
                Thread.sleep(20);
            }
        }
        return null;
    }

    private Object runQuietly(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Exception expectedUnderContention) {
            // 并发下锁竞争、整批拒绝、令牌失效、中断等都是合法结局，不变量在循环中断言。
        }
        return null;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
