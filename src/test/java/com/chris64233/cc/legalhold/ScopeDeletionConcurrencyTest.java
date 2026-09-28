package com.chris64233.cc.legalhold;

import static org.assertj.core.api.Assertions.assertThat;

import com.chris64233.cc.legalhold.domain.DataObject;
import com.chris64233.cc.legalhold.domain.DeletionToken;
import com.chris64233.cc.legalhold.domain.ObjectStatus;
import com.chris64233.cc.legalhold.repo.CaseScopeRepository;
import com.chris64233.cc.legalhold.repo.CaseScopeVersionRepository;
import com.chris64233.cc.legalhold.repo.DataObjectRepository;
import com.chris64233.cc.legalhold.repo.DeletionTokenRepository;
import com.chris64233.cc.legalhold.repo.HoldEventRepository;
import com.chris64233.cc.legalhold.repo.HoldMembershipRepository;
import com.chris64233.cc.legalhold.repo.ScopeApprovalRepository;
import com.chris64233.cc.legalhold.repo.ScopeVersionMemberRepository;
import com.chris64233.cc.legalhold.service.DeletionService;
import com.chris64233.cc.legalhold.service.ObjectService;
import com.chris64233.cc.legalhold.service.scope.CaseScopeService;
import com.chris64233.cc.legalhold.support.MutableTestClock;
import com.chris64233.cc.legalhold.support.TestClockConfig;
import com.chris64233.cc.legalhold.web.dto.RegisterObjectRequest;
import com.chris64233.cc.legalhold.web.dto.RetentionRuleRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeApprovalRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeChangeRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeCriteriaRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 范围缩围释放 / 扩围生效 与删除确认并发时，绝不允许“对象已 DELETED 却仍处于
 * 有效保全范围内”。两条路径都在事务内对对象行加同顺序悲观写锁，严格串行化。
 */
@SpringBootTest(classes = {CcLegalHoldApplication.class, TestClockConfig.class})
class ScopeDeletionConcurrencyTest {

    private static final int PAIRS = 16;

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
    private DeletionTokenRepository tokenRepository;
    @Autowired
    private CaseScopeRepository caseRepository;
    @Autowired
    private CaseScopeVersionRepository versionRepository;
    @Autowired
    private ScopeVersionMemberRepository memberRepository;
    @Autowired
    private ScopeApprovalRepository approvalRepository;
    @Autowired
    private HoldEventRepository eventRepository;
    @Autowired
    private com.chris64233.cc.legalhold.repo.AuditEventRepository auditRepository;
    @Autowired
    private MutableTestClock clock;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private final List<String> shrinkTargets = new ArrayList<>();
    private final List<String> expandTargets = new ArrayList<>();
    private final Map<String, String> tokenByKey = new HashMap<>();

    @BeforeEach
    void setUp() {
        approvalRepository.deleteAll();
        memberRepository.deleteAll();
        versionRepository.deleteAll();
        caseRepository.deleteAll();
        membershipRepository.deleteAll();
        eventRepository.deleteAll();
        tokenRepository.deleteAll();
        auditRepository.deleteAll();
        objectRepository.deleteAll();
        clock.setTime(Instant.parse("2026-01-01T00:00:00Z"));
        objectService.saveRule(new RetentionRuleRequest("DOC", 0));

        for (int i = 0; i < PAIRS; i++) {
            // 缩围组：案件 v1 仅保全 target，关闭变更已获第一批准，target 持有效删除令牌
            String sTarget = "s-" + i;
            String sCase = "CASE-S-" + i;
            objectService.register(new RegisterObjectRequest(sTarget, "DOC"));
            caseScopeService.submit(sCase, keys("S-V1-" + i, sTarget));
            caseScopeService.submit(sCase, closeChange("S-CLOSE-" + i));
            caseScopeService.approve(sCase, "S-CLOSE-" + i,
                    new ScopeApprovalRequest("boss-a", "第一批准"));
            stagePendingToken(sTarget);
            shrinkTargets.add(sTarget);

            // 扩围组：案件 v1 保全 anchor，v2 扩围加入 target；target 持有效删除令牌
            String eTarget = "e-" + i;
            String anchor = "a-" + i;
            String eCase = "CASE-E-" + i;
            objectService.register(new RegisterObjectRequest(eTarget, "DOC"));
            objectService.register(new RegisterObjectRequest(anchor, "DOC"));
            caseScopeService.submit(eCase, keys("E-V1-" + i, anchor));
            stagePendingToken(eTarget);
            expandTargets.add(eTarget);
        }
        clock.advanceSeconds(1);
    }

    @AfterEach
    void cleanUp() {
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    tokenByKey.clear();
                    approvalRepository.deleteAll();
                    memberRepository.deleteAll();
                    versionRepository.deleteAll();
                    caseRepository.deleteAll();
                    eventRepository.deleteAll();
                    membershipRepository.deleteAll();
                    tokenRepository.deleteAll();
                    auditRepository.deleteAll();
                    objectRepository.deleteAll();
                });
    }

    private ScopeChangeRequest keys(String changeNo, String... businessKeys) {
        return new ScopeChangeRequest(changeNo,
                new ScopeCriteriaRequest(List.of(businessKeys), List.of(), null, null),
                false, "alice", "并发测试", 8);
    }

    private ScopeChangeRequest closeChange(String changeNo) {
        return new ScopeChangeRequest(changeNo,
                new ScopeCriteriaRequest(List.of(), List.of(), null, null),
                true, "alice", "关闭案件", 8);
    }

    /** 直接落一枚 PENDING 令牌并置 PENDING_DELETION，模拟令牌签发后才出现/变更保全。 */
    private void stagePendingToken(String businessKey) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            DataObject dataObject = objectRepository.findByBusinessKey(businessKey).orElseThrow();
            dataObject.setStatus(ObjectStatus.PENDING_DELETION);
            String token = UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", "");
            tokenRepository.save(new DeletionToken(
                    token, dataObject.getId(), clock.now().plus(10, ChronoUnit.MINUTES)));
            tokenByKey.put(businessKey, token);
        });
    }

    private String tokenFor(String businessKey) {
        return tokenByKey.get(businessKey);
    }

    @Test
    void scopeReleaseOrExpandAndDeletionConfirmAreSerializable() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String target : shrinkTargets) {
                String caseNo = "CASE-S-" + target.substring(2);
                CyclicBarrier barrier = new CyclicBarrier(2);
                futures.add(pool.submit(() -> {
                    barrier.await();
                    try {
                        caseScopeService.approve(caseNo, "S-CLOSE-" + target.substring(2),
                                new ScopeApprovalRequest("boss-b", "第二批准"));
                    } catch (RuntimeException ignored) {
                        // 与删除确认竞争时按串行化结果二选一
                    }
                    return null;
                }));
                futures.add(pool.submit(() -> {
                    barrier.await();
                    try {
                        deletionService.confirmDeletion(tokenFor(target));
                    } catch (RuntimeException ignored) {
                        // 保全先持锁则确认被拒绝并失效令牌
                    }
                    return null;
                }));
            }
            for (String target : expandTargets) {
                String caseNo = "CASE-E-" + target.substring(2);
                String anchor = "a-" + target.substring(2);
                CyclicBarrier barrier = new CyclicBarrier(2);
                futures.add(pool.submit(() -> {
                    barrier.await();
                    try {
                        caseScopeService.submit(caseNo, keys("E-V2-" + target.substring(2),
                                anchor, target));
                    } catch (RuntimeException ignored) {
                        // 与删除确认竞争时按串行化结果二选一
                    }
                    return null;
                }));
                futures.add(pool.submit(() -> {
                    barrier.await();
                    try {
                        deletionService.confirmDeletion(tokenFor(target));
                    } catch (RuntimeException ignored) {
                        // 扩围先生效则确认被拒绝并失效令牌
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(90, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        for (String target : shrinkTargets) {
            assertNoDeletedWhileHeld(target, "CASE-S-" + target.substring(2));
        }
        for (String target : expandTargets) {
            assertNoDeletedWhileHeld(target, "CASE-E-" + target.substring(2));
        }
    }

    private void assertNoDeletedWhileHeld(String businessKey, String caseNo) {
        DataObject dataObject = objectRepository.findByBusinessKey(businessKey).orElseThrow();
        boolean held = membershipRepository
                .findByCaseNoAndObjectId(caseNo, dataObject.getId()).isPresent();
        if (held) {
            assertThat(dataObject.getStatus())
                    .as("处于有效保全范围内的对象不能被删除: " + businessKey)
                    .isNotEqualTo(ObjectStatus.DELETED);
        }
    }
}
