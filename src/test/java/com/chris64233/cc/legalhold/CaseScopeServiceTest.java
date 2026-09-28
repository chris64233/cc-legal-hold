package com.chris64233.cc.legalhold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chris64233.cc.legalhold.domain.ObjectStatus;
import com.chris64233.cc.legalhold.domain.ScopeVersionStatus;
import com.chris64233.cc.legalhold.repo.CaseScopeVersionRepository;
import com.chris64233.cc.legalhold.repo.DataObjectRepository;
import com.chris64233.cc.legalhold.repo.HoldMembershipRepository;
import com.chris64233.cc.legalhold.repo.LegalCaseRepository;
import com.chris64233.cc.legalhold.repo.RetentionRuleRepository;
import com.chris64233.cc.legalhold.repo.ScopeApprovalRepository;
import com.chris64233.cc.legalhold.service.CaseScopeService;
import com.chris64233.cc.legalhold.service.ConflictException;
import com.chris64233.cc.legalhold.service.LegalHoldService;
import com.chris64233.cc.legalhold.service.ObjectService;
import com.chris64233.cc.legalhold.support.MutableTestClock;
import com.chris64233.cc.legalhold.support.TestClockConfig;
import com.chris64233.cc.legalhold.web.dto.ApprovalRequest;
import com.chris64233.cc.legalhold.web.dto.HoldRequest;
import com.chris64233.cc.legalhold.web.dto.RegisterObjectRequest;
import com.chris64233.cc.legalhold.web.dto.RetentionRuleRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeChangeRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeCriteriaRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeDiffView;
import com.chris64233.cc.legalhold.web.dto.ScopeVersionView;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 案件范围版本化、双人审批、整批拒绝、版本不可变、差异与查询的服务层测试。
 * 物化批大小设为 2，强制走分批路径。
 */
@SpringBootTest(classes = {CcLegalHoldApplication.class, TestClockConfig.class}, properties = {
        "legalhold.scope.materialize-batch-size=2",
        "legalhold.scope.max-batches-per-call=100"
})
class CaseScopeServiceTest {

    @Autowired
    private CaseScopeService caseScopeService;
    @Autowired
    private ObjectService objectService;
    @Autowired
    private LegalHoldService legalHoldService;
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
    private RetentionRuleRepository ruleRepository;
    @Autowired
    private MutableTestClock clock;

    @BeforeEach
    void setUp() {
        approvalRepository.deleteAll();
        membershipRepository.deleteAll();
        versionRepository.deleteAll();
        caseRepository.deleteAll();
        objectRepository.deleteAll();
        ruleRepository.deleteAll();
        clock.setTime(Instant.parse("2026-01-01T00:00:00Z"));
        objectService.saveRule(new RetentionRuleRequest("DOC", 30));
        for (int i = 1; i <= 6; i++) {
            objectService.register(new RegisterObjectRequest("k-" + i, "DOC"));
        }
        objectService.register(new RegisterObjectRequest("mail-1", "MAIL"));
    }

    private ScopeChangeRequest change(String caseNo, String changeNo, List<String> keys,
                                      List<String> categories, String createdBy) {
        return new ScopeChangeRequest(caseNo, changeNo, "范围调整", createdBy,
                new ScopeCriteriaRequest(keys, categories, null, null));
    }

    private void approve(String changeNo, String reviewer) {
        caseScopeService.approve(new ApprovalRequest(changeNo, reviewer, "APPROVE", "同意"));
    }

    // ------------------------------------------------------------------

    @Test
    void expandByCategoryIsEffectiveImmediatelyAndHoldsObjects() {
        ScopeVersionView v1 = caseScopeService.submitChange(
                change("CASE-1", "CHG-1", List.of(), List.of("DOC"), "alice"));

        assertThat(v1.versionNo()).isEqualTo(1);
        assertThat(v1.status()).isEqualTo(ScopeVersionStatus.EFFECTIVE);
        assertThat(v1.changeType().name()).isEqualTo("EXPAND");
        assertThat(v1.addedCount()).isEqualTo(6);
        assertThat(v1.removedCount()).isZero();
        assertThat(caseScopeService.objectHolds("k-1"))
                .singleElement().extracting("caseNo").isEqualTo("CASE-1");
        assertThat(caseScopeService.getCurrentVersion("CASE-1").versionNo()).isEqualTo(1);
    }

    @Test
    void explicitKeysAndTimeWindowIntersectIntoOneVersion() {
        clock.advanceDays(10);
        objectService.register(new RegisterObjectRequest("late-1", "DOC"));

        // 显式 k-1 + 创建时间早于 2026-01-05 的 DOC（k-1..k-4 在该窗口前创建）
        ScopeVersionView v = caseScopeService.submitChange(new ScopeChangeRequest(
                "CASE-T", "CHG-T", "混合条件", "alice",
                new ScopeCriteriaRequest(List.of("k-6"), List.of("DOC"),
                        null, Instant.parse("2026-01-05T00:00:00Z"))));

        assertThat(v.status()).isEqualTo(ScopeVersionStatus.EFFECTIVE);
        // k-1..k-6 均在窗口前创建并命中时间窗口，k-6 同时命中显式集合（并集去重），
        // late-1 创建于窗口之后，不进入范围
        ScopeDiffView diff = caseScopeService.diff("CASE-T", null, null);
        assertThat(diff.addedBusinessKeys())
                .containsExactlyInAnyOrder("k-1", "k-2", "k-3", "k-4", "k-5", "k-6");
        assertThat(caseScopeService.objectHolds("late-1")).isEmpty();
    }

    @Test
    void shrinkCreatesNewImmutableVersionAndKeepsOldOne() {
        caseScopeService.submitChange(
                change("CASE-2", "CHG-2A", List.of("k-1", "k-2", "k-3"), List.of(), "alice"));

        ScopeVersionView v2 = caseScopeService.submitChange(
                change("CASE-2", "CHG-2B", List.of("k-1"), List.of(), "alice"));

        assertThat(v2.versionNo()).isEqualTo(2);
        assertThat(v2.status()).isEqualTo(ScopeVersionStatus.PENDING_APPROVAL);
        assertThat(v2.changeType().name()).isEqualTo("SHRINK");
        assertThat(v2.addedCount()).isZero();
        assertThat(v2.retainedCount()).isEqualTo(1);
        assertThat(v2.removedCount()).isEqualTo(2);

        // 旧版本仍在且仍为可查询的不可变记录（被新版本取代后标记 SUPERSEDED）
        assertThat(caseScopeService.listVersions("CASE-2")).hasSize(2);
        ScopeDiffView diff = caseScopeService.diff("CASE-2", 1, 2);
        assertThat(diff.removedBusinessKeys()).containsExactly("k-2", "k-3");
        assertThat(diff.addedBusinessKeys()).isEmpty();
        assertThat(diff.retainedBusinessKeys()).containsExactly("k-1");

        // 审批完成前不得释放任何对象
        assertThat(caseScopeService.objectHolds("k-2")).isNotEmpty();
    }

    @Test
    void shrinkRequiresTwoDistinctApproversAndReleasesOnSecond() {
        caseScopeService.submitChange(
                change("CASE-3", "CHG-3A", List.of("k-1", "k-2"), List.of(), "alice"));
        ScopeVersionView shrink = caseScopeService.submitChange(
                change("CASE-3", "CHG-3B", List.of("k-1"), List.of(), "alice"));

        approve("CHG-3B", "bob");
        assertThat(caseScopeService.progress("CHG-3B").status())
                .isEqualTo(ScopeVersionStatus.PENDING_APPROVAL);
        assertThat(caseScopeService.objectHolds("k-2")).isNotEmpty();

        // 同一人再次批准不算第二名
        approve("CHG-3B", "bob");
        assertThat(caseScopeService.progress("CHG-3B").approveCount()).isEqualTo(1);
        assertThat(caseScopeService.objectHolds("k-2")).isNotEmpty();

        approve("CHG-3B", "carol");
        assertThat(caseScopeService.progress("CHG-3B").status())
                .isEqualTo(ScopeVersionStatus.EFFECTIVE);
        assertThat(caseScopeService.objectHolds("k-2")).isEmpty();
        assertThat(caseScopeService.objectHolds("k-1")).isNotEmpty();
        // 释放原因可查询
        assertThat(caseScopeService.releaseReasons("k-2"))
                .singleElement().extracting("caseNo").isEqualTo("CASE-3");
        // 当前生效版本推进到 v2，v1 不可变保留
        assertThat(caseScopeService.getCurrentVersion("CASE-3").versionNo()).isEqualTo(2);
        assertThat(caseScopeService.getVersion("CASE-3", 1).versionNo()).isEqualTo(1);
    }

    @Test
    void creatorCannotApproveOwnChange() {
        caseScopeService.submitChange(
                change("CASE-4", "CHG-4A", List.of("k-1", "k-2"), List.of(), "alice"));
        caseScopeService.submitChange(
                change("CASE-4", "CHG-4B", List.of("k-1"), List.of(), "alice"));
        assertThatThrownBy(() -> approve("CHG-4B", "alice"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void singleRejectRejectsWholeBatchAndKeepsAllHolds() {
        caseScopeService.submitChange(
                change("CASE-5", "CHG-5A", List.of("k-1", "k-2", "k-3"), List.of(), "alice"));
        caseScopeService.submitChange(
                change("CASE-5", "CHG-5B", List.of("k-1"), List.of(), "alice"));

        caseScopeService.approve(new ApprovalRequest("CHG-5B", "bob", "REJECT", "有异议"));

        ScopeVersionView rejected = caseScopeService.getVersion("CASE-5", 2);
        assertThat(rejected.status()).isEqualTo(ScopeVersionStatus.REJECTED);
        assertThat(rejected.rejectReason()).contains("bob");
        for (String key : new String[]{"k-1", "k-2", "k-3"}) {
            assertThat(caseScopeService.objectHolds(key)).isNotEmpty();
        }
    }

    @Test
    void changedRetentionRuleDuringApprovalRejectsWholeBatchOnFinalRecheck() {
        caseScopeService.submitChange(
                change("CASE-6", "CHG-6A", List.of("k-1", "k-2"), List.of(), "alice"));
        caseScopeService.submitChange(
                change("CASE-6", "CHG-6B", List.of("k-1"), List.of(), "alice"));
        approve("CHG-6B", "bob");

        // 审批期间延长保留规则：与物化时约束快照不一致
        clock.advanceDays(40);
        objectService.saveRule(new RetentionRuleRequest("DOC", 365));
        approve("CHG-6B", "carol");

        ScopeVersionView rejected = caseScopeService.getVersion("CASE-6", 2);
        assertThat(rejected.status()).isEqualTo(ScopeVersionStatus.REJECTED);
        assertThat(rejected.rejectReason()).contains("整批拒绝");
        // 任何对象都没有被释放
        assertThat(caseScopeService.objectHolds("k-2")).isNotEmpty();
        assertThat(caseScopeService.objectHolds("k-1")).isNotEmpty();
        assertThat(caseRepository.findByCaseNo("CASE-6").orElseThrow().getCurrentVersionNo())
                .isEqualTo(1);
    }

    @Test
    void otherCaseAppearingDuringApprovalRejectsReleaseBatch() {
        caseScopeService.submitChange(
                change("CASE-7", "CHG-7A", List.of("k-1", "k-2"), List.of(), "alice"));
        caseScopeService.submitChange(
                change("CASE-7", "CHG-7B", List.of("k-1"), List.of(), "alice"));
        approve("CHG-7B", "bob");

        // k-2 在审批期间被另一案件保全
        legalHoldService.apply(new HoldRequest("CASE-OTHER", List.of("k-2"), "新增保全"));
        approve("CHG-7B", "carol");

        assertThat(caseScopeService.getVersion("CASE-7", 2).status())
                .isEqualTo(ScopeVersionStatus.REJECTED);
        assertThat(caseScopeService.objectHolds("k-2"))
                .extracting("caseNo").containsExactlyInAnyOrder("CASE-7", "CASE-OTHER");
    }

    @Test
    void changeNumberIsIdempotentAndApprovalEventIsIdempotent() {
        ScopeChangeRequest req = change("CASE-8", "DUP-1", List.of("k-1"), List.of(), "alice");
        ScopeVersionView first = caseScopeService.submitChange(req);
        ScopeVersionView replay = caseScopeService.submitChange(req);

        assertThat(replay.versionNo()).isEqualTo(first.versionNo());
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(caseScopeService.listVersions("CASE-8")).hasSize(1);
        assertThat(caseScopeService.objectHolds("k-1")).hasSize(1);

        // 同一 changeNo 不能用于别的案件
        assertThatThrownBy(() -> caseScopeService.submitChange(
                change("CASE-9", "DUP-1", List.of("k-2"), List.of(), "alice")))
                .isInstanceOf(ConflictException.class);

        // 缩围到空集（用早于任何对象创建时间的 createdTo，使条件命中为空）
        caseScopeService.submitChange(new ScopeChangeRequest(
                "CASE-8", "DUP-2", "全部移出", "alice",
                new ScopeCriteriaRequest(List.of(), List.of(),
                        null, Instant.parse("2025-01-01T00:00:00Z"))));
        approve("DUP-2", "bob");
        approve("DUP-2", "bob");
        assertThat(approvalRepository.findByChangeNoOrderByVotedAtAsc("DUP-2")).hasSize(1);
    }

    @Test
    void concurrentChangeOnSameCaseIsBlockedWhileApprovalPending() {
        caseScopeService.submitChange(
                change("CASE-10", "CHG-10A", List.of("k-1", "k-2"), List.of(), "alice"));
        caseScopeService.submitChange(
                change("CASE-10", "CHG-10B", List.of("k-1"), List.of(), "alice"));
        assertThatThrownBy(() -> caseScopeService.submitChange(
                change("CASE-10", "CHG-10C", List.of("k-3"), List.of(), "alice")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("进行中");
    }

    @Test
    void noOpScopeIsRejectedWithoutDisturbingHolds() {
        caseScopeService.submitChange(
                change("CASE-11", "CHG-11A", List.of("k-1"), List.of(), "alice"));
        ScopeVersionView noop = caseScopeService.submitChange(
                change("CASE-11", "CHG-11B", List.of("k-1"), List.of(), "alice"));
        assertThat(noop.status()).isEqualTo(ScopeVersionStatus.REJECTED);
        assertThat(noop.rejectReason()).contains("一致");
        assertThat(caseScopeService.objectHolds("k-1")).isNotEmpty();
        // 无变更占用后可以发起新变更
        assertThat(caseRepository.findByCaseNo("CASE-11").orElseThrow().getActiveChangeNo())
                .isNull();
    }

    @Test
    void closeCaseReleasesEverythingAfterTwoApprovalsAndMarksClosed() {
        caseScopeService.submitChange(
                change("CASE-12", "CHG-12A", List.of("k-1", "k-2"), List.of(), "alice"));
        ScopeVersionView close = caseScopeService.closeCase(
                new com.chris64233.cc.legalhold.web.dto.CloseCaseRequest(
                        "CASE-12", "CHG-12CLOSE", "诉讼终结", "alice"));
        assertThat(close.changeType().name()).isEqualTo("CLOSE");
        assertThat(close.removedCount()).isEqualTo(2);

        approve("CHG-12CLOSE", "bob");
        approve("CHG-12CLOSE", "carol");

        assertThat(caseRepository.findByCaseNo("CASE-12").orElseThrow().isClosed()).isTrue();
        assertThat(caseScopeService.objectHolds("k-1")).isEmpty();
        assertThat(caseScopeService.objectHolds("k-2")).isEmpty();
        // 关闭后不能再变更
        assertThatThrownBy(() -> caseScopeService.submitChange(
                change("CASE-12", "CHG-12X", List.of("k-3"), List.of(), "alice")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void objectHoldsAcrossCasesAndReleaseReasonsAreQueryable() {
        caseScopeService.submitChange(
                change("CASE-A", "CHG-A", List.of("k-1"), List.of(), "alice"));
        legalHoldService.apply(new HoldRequest("CASE-B", List.of("k-1"), "其他案件"));
        assertThat(caseScopeService.objectHolds("k-1"))
                .extracting("caseNo").containsExactly("CASE-A", "CASE-B");

        legalHoldService.release(new HoldRequest("CASE-B", List.of("k-1"), "案件B撤销"));
        assertThat(caseScopeService.releaseReasons("k-1"))
                .singleElement().satisfies(view -> {
                    assertThat(view.caseNo()).isEqualTo("CASE-B");
                    assertThat(view.reason()).isEqualTo("案件B撤销");
                });
    }

    @Test
    void deletedObjectIsNotScannedIntoScopeAndExplicitDeletedKeyConflicts() {
        var deleted = objectRepository.findByBusinessKey("k-5").orElseThrow();
        deleted.setStatus(ObjectStatus.DELETED);
        objectRepository.save(deleted);

        ScopeVersionView v = caseScopeService.submitChange(
                change("CASE-13", "CHG-13", List.of(), List.of("DOC"), "alice"));
        // 已删除对象不进入范围
        assertThat(v.addedCount()).isEqualTo(5);

        assertThatThrownBy(() -> caseScopeService.submitChange(
                change("CASE-14", "CHG-14", List.of("k-5"), List.of(), "alice")))
                .isInstanceOf(ConflictException.class);
    }
}
