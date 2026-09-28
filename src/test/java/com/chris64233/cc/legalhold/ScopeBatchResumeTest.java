package com.chris64233.cc.legalhold;

import static org.assertj.core.api.Assertions.assertThat;

import com.chris64233.cc.legalhold.domain.ScopeVersionStatus;
import com.chris64233.cc.legalhold.repo.CaseScopeRepository;
import com.chris64233.cc.legalhold.repo.CaseScopeVersionRepository;
import com.chris64233.cc.legalhold.repo.DataObjectRepository;
import com.chris64233.cc.legalhold.repo.HoldEventRepository;
import com.chris64233.cc.legalhold.repo.HoldMembershipRepository;
import com.chris64233.cc.legalhold.repo.ScopeApprovalRepository;
import com.chris64233.cc.legalhold.repo.ScopeVersionMemberRepository;
import com.chris64233.cc.legalhold.service.ObjectService;
import com.chris64233.cc.legalhold.service.scope.CaseScopeService;
import com.chris64233.cc.legalhold.service.scope.ScopeBatchProcessor;
import com.chris64233.cc.legalhold.service.scope.ScopeCriteria;
import com.chris64233.cc.legalhold.service.scope.ScopeJsonCodec;
import com.chris64233.cc.legalhold.support.MutableTestClock;
import com.chris64233.cc.legalhold.support.TestClockConfig;
import com.chris64233.cc.legalhold.web.dto.RegisterObjectRequest;
import com.chris64233.cc.legalhold.web.dto.RetentionRuleRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeChangeRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeCriteriaRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeChangeView;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 大范围分批计算：批间崩溃/服务重启后从游标续算，计算期间对外不出现部分保全，
 * 恢复后只有一个完整生效版本，且重放恢复幂等。
 */
@SpringBootTest(classes = {CcLegalHoldApplication.class, TestClockConfig.class})
class ScopeBatchResumeTest {

    private static final String CASE = "CASE-BATCH";
    private static final int OBJECTS = 25;
    private static final int BATCH = 4;

    @Autowired
    private CaseScopeService caseScopeService;
    @Autowired
    private ScopeBatchProcessor batchProcessor;
    @Autowired
    private ScopeJsonCodec codec;
    @Autowired
    private ObjectService objectService;
    @Autowired
    private DataObjectRepository objectRepository;
    @Autowired
    private HoldMembershipRepository membershipRepository;
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
    private com.chris64233.cc.legalhold.repo.DeletionTokenRepository tokenRepository;
    @Autowired
    private com.chris64233.cc.legalhold.repo.AuditEventRepository auditRepository;
    @Autowired
    private com.chris64233.cc.legalhold.service.DeletionService deletionService;
    @Autowired
    private MutableTestClock clock;

    @AfterEach
    void cleanUp() {
        approvalRepository.deleteAll();
        memberRepository.deleteAll();
        versionRepository.deleteAll();
        caseRepository.deleteAll();
        membershipRepository.deleteAll();
        eventRepository.deleteAll();
        tokenRepository.deleteAll();
        auditRepository.deleteAll();
        objectRepository.deleteAll();
    }

    @BeforeEach
    void setUp() {
        approvalRepository.deleteAll();
        memberRepository.deleteAll();
        versionRepository.deleteAll();
        caseRepository.deleteAll();
        membershipRepository.deleteAll();
        eventRepository.deleteAll();
        objectRepository.deleteAll();
        clock.setTime(Instant.parse("2026-01-01T00:00:00Z"));
        objectService.saveRule(new RetentionRuleRequest("DOC", 0));
        for (int i = 0; i < OBJECTS; i++) {
            objectService.register(new RegisterObjectRequest("b-" + i, "DOC"));
        }
    }

    private ScopeChangeRequest categoryChange(String changeNo) {
        return new ScopeChangeRequest(changeNo,
                new ScopeCriteriaRequest(List.of(), List.of("DOC"), null, null),
                false, "alice", "分批计算", BATCH);
    }

    @Test
    void crashMidComputationIsResumedOnRecoveryWithoutPartialHold() {
        ScopeChangeRequest request = categoryChange("CHG-BATCH-1");
        ScopeCriteria criteria = ScopeCriteria.from(request.criteria());
        String criteriaJson = codec.writeCriteria(criteria);

        // 分配版本后只跑两批（模拟第 2 批提交后服务崩溃）。
        Long versionId = caseScopeService.allocateVersion(CASE, request, criteriaJson, BATCH);
        batchProcessor.processBatch(versionId, criteria, Set.of());
        batchProcessor.processBatch(versionId, criteria, Set.of());

        // 计算期间：版本仍 COMPUTING，对外有效保全投影一个都没有（无部分保全）。
        assertThat(versionRepository.findById(versionId).orElseThrow().getStatus())
                .isEqualTo(ScopeVersionStatus.COMPUTING);
        assertThat(membershipRepository.countByCaseNo(CASE)).isZero();
        long staged = memberRepository.countByVersionIdAndMembershipChange(
                versionId, com.chris64233.cc.legalhold.domain.MembershipChange.ADDED);
        assertThat(staged).isEqualTo(2L * BATCH);

        // “重启”后自动恢复：续算剩余批次并一次性生效。
        caseScopeService.recoverInterruptedComputations();

        ScopeChangeView view = caseScopeService.getChange(CASE, "CHG-BATCH-1");
        assertThat(view.status()).isEqualTo(ScopeVersionStatus.EFFECTIVE);
        assertThat(view.computeDone()).isTrue();
        assertThat(view.memberCount()).isEqualTo(OBJECTS);
        assertThat(membershipRepository.countByCaseNo(CASE)).isEqualTo(OBJECTS);
        assertThat(caseRepository.findByCaseNo(CASE).orElseThrow().getCurrentVersionNo())
                .isEqualTo(1);

        // 恢复幂等：再次恢复不产生重复成员/事件，对外仍是同一个完整版本。
        caseScopeService.recoverInterruptedComputations();
        assertThat(membershipRepository.countByCaseNo(CASE)).isEqualTo(OBJECTS);
        assertThat(versionRepository.findByCaseNoOrderByVersionNoAsc(CASE)).hasSize(1);
    }

    @Test
    void objectDeletedDuringComputationIsDroppedFromEffectiveVersion() {
        ScopeChangeRequest request = categoryChange("CHG-BATCH-3");
        ScopeCriteria criteria = ScopeCriteria.from(request.criteria());
        String criteriaJson = codec.writeCriteria(criteria);

        Long versionId = caseScopeService.allocateVersion(CASE, request, criteriaJson, BATCH);
        batchProcessor.processBatch(versionId, criteria, Set.of());

        // 计算窗口内删除一个已扫描到的对象（此时尚无有效保全）。
        var deletionRequest = deletionService.requestDeletion("b-0");
        deletionService.confirmDeletion(deletionRequest.token());

        caseScopeService.recoverInterruptedComputations();

        ScopeChangeView view = caseScopeService.getChange(CASE, "CHG-BATCH-3");
        assertThat(view.status())
                .isEqualTo(com.chris64233.cc.legalhold.domain.ScopeVersionStatus.EFFECTIVE);
        // 已删除对象不纳入保全，也不计入有效版本成员
        assertThat(view.memberCount()).isEqualTo(OBJECTS - 1);
        assertThat(membershipRepository.countByCaseNo(CASE)).isEqualTo(OBJECTS - 1);
        com.chris64233.cc.legalhold.web.dto.ScopeDiffView diff =
                caseScopeService.diff(CASE, null, view.versionNo());
        assertThat(diff.addedBusinessKeys()).doesNotContain("b-0");
    }

    @Test
    void largeScopeAcrossManyBatchesEventuallyAppliesAtomically() {
        ScopeChangeView view = caseScopeService.submit(CASE, categoryChange("CHG-BATCH-2"));
        assertThat(view.status()).isEqualTo(ScopeVersionStatus.EFFECTIVE);
        assertThat(view.memberCount()).isEqualTo(OBJECTS);
        // ceil(25/4)=7 批：游标推进与成员分 7 个事务提交，但生效只有一个版本
        assertThat(versionRepository.findByCaseNoOrderByVersionNoAsc(CASE)).hasSize(1);
        assertThat(membershipRepository.countByCaseNo(CASE)).isEqualTo(OBJECTS);
    }
}
