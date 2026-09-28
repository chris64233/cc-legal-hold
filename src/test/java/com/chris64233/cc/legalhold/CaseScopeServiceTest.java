package com.chris64233.cc.legalhold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chris64233.cc.legalhold.domain.CaseStatus;
import com.chris64233.cc.legalhold.domain.HoldMembership;
import com.chris64233.cc.legalhold.domain.ScopeVersionStatus;
import com.chris64233.cc.legalhold.repo.CaseScopeRepository;
import com.chris64233.cc.legalhold.repo.CaseScopeVersionRepository;
import com.chris64233.cc.legalhold.repo.DataObjectRepository;
import com.chris64233.cc.legalhold.repo.HoldMembershipRepository;
import com.chris64233.cc.legalhold.repo.ScopeApprovalRepository;
import com.chris64233.cc.legalhold.repo.ScopeVersionMemberRepository;
import com.chris64233.cc.legalhold.service.ConflictException;
import com.chris64233.cc.legalhold.service.ObjectService;
import com.chris64233.cc.legalhold.service.scope.CaseScopeService;
import com.chris64233.cc.legalhold.service.scope.ScopeRejectedException;
import com.chris64233.cc.legalhold.support.MutableTestClock;
import com.chris64233.cc.legalhold.support.TestClockConfig;
import com.chris64233.cc.legalhold.web.dto.ApprovalProgressView;
import com.chris64233.cc.legalhold.web.dto.ObjectHoldsView;
import com.chris64233.cc.legalhold.web.dto.ReleaseReasonView;
import com.chris64233.cc.legalhold.web.dto.RetentionRuleRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeApprovalRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeChangeRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeChangeView;
import com.chris64233.cc.legalhold.web.dto.ScopeCriteriaRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeDiffView;
import com.chris64233.cc.legalhold.web.dto.RegisterObjectRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 案件范围版本：条件生成不可变版本、扩围直接生效、缩围/关闭双批准、
 * 终确认重核验整批拒绝、版本差异与各类查询。
 */
@SpringBootTest(classes = {CcLegalHoldApplication.class, TestClockConfig.class})
class CaseScopeServiceTest {

    private static final String CASE = "CASE-1";

    @Autowired
    private CaseScopeService caseScopeService;
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
    private com.chris64233.cc.legalhold.repo.HoldEventRepository eventRepository;
    @Autowired
    private com.chris64233.cc.legalhold.repo.AuditEventRepository auditRepository;
    @Autowired
    private com.chris64233.cc.legalhold.repo.DeletionTokenRepository tokenRepository;
    @Autowired
    private MutableTestClock clock;

    @AfterEach
    void cleanUp() {
        approvalRepository.deleteAll();
        memberRepository.deleteAll();
        versionRepository.deleteAll();
        caseRepository.deleteAll();
        eventRepository.deleteAll();
        membershipRepository.deleteAll();
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
        eventRepository.deleteAll();
        membershipRepository.deleteAll();
        objectRepository.deleteAll();
        clock.setTime(Instant.parse("2026-01-01T00:00:00Z"));
        objectService.saveRule(new RetentionRuleRequest("DOC", 30));
        objectService.saveRule(new RetentionRuleRequest("MAIL", 5));
        register("o1", "DOC");
        register("o2", "DOC");
        register("o3", "DOC");
        register("m1", "MAIL");
    }

    private void register(String key, String category) {
        objectService.register(new RegisterObjectRequest(key, category));
    }

    private ScopeChangeRequest change(String changeNo, List<String> keys,
                                      List<String> categories, boolean close,
                                      String requestedBy, Integer batchSize) {
        return new ScopeChangeRequest(changeNo,
                new ScopeCriteriaRequest(keys, categories, null, null),
                close, requestedBy, "范围变更:" + changeNo, batchSize);
    }

    private ScopeChangeRequest keys(String changeNo, String requestedBy, String... keys) {
        return change(changeNo, List.of(keys), List.of(), false, requestedBy, 2);
    }

    private void twoApprovals(String changeNo) {
        ApprovalProgressView first = caseScopeService.approve(
                CASE, changeNo, new ScopeApprovalRequest("boss-a", "同意"));
        assertThat(first.approvalCount()).isEqualTo(1);
        assertThat(first.effective()).isFalse();
        ApprovalProgressView second = caseScopeService.approve(
                CASE, changeNo, new ScopeApprovalRequest("boss-b", "同意"));
        assertThat(second.approvalCount()).isEqualTo(2);
        assertThat(second.effective()).isTrue();
    }

    @Test
    void createVersionByCriteriaIsImmutableAndTakesEffectImmediately() {
        ScopeChangeView v1 = caseScopeService.submit(
                CASE, change("CHG-1", List.of("o1", "o2"), List.of(), false, "alice", 2));

        assertThat(v1.versionNo()).isEqualTo(1);
        assertThat(v1.status()).isEqualTo(ScopeVersionStatus.EFFECTIVE);
        assertThat(v1.changeType().name()).isEqualTo("CREATE");
        assertThat(v1.memberCount()).isEqualTo(2);
        assertThat(v1.addedCount()).isEqualTo(2);
        assertThat(v1.removedCount()).isZero();
        assertThat(membershipOf("o1")).isTrue();
        assertThat(membershipOf("o2")).isTrue();
        assertThat(membershipOf("o3")).isFalse();

        // 当前生效版本号记录在案件上
        assertThat(caseRepository.findByCaseNo(CASE).orElseThrow().getCurrentVersionNo())
                .isEqualTo(1);
    }

    @Test
    void expandCreatesNewVersionAndOldVersionIsSupersededNotOverwritten() {
        caseScopeService.submit(CASE, keys("CHG-1", "alice", "o1"));
        ScopeChangeView v2 = caseScopeService.submit(CASE, keys("CHG-2", "alice", "o1", "o2"));

        assertThat(v2.versionNo()).isEqualTo(2);
        assertThat(v2.status()).isEqualTo(ScopeVersionStatus.EFFECTIVE);
        assertThat(v2.addedCount()).isEqualTo(1);
        assertThat(versionRepository.findByCaseNoAndVersionNo(CASE, 1).orElseThrow().getStatus())
                .isEqualTo(ScopeVersionStatus.SUPERSEDED);
        // 旧版本成员与条件仍可查，未被覆盖
        ScopeDiffView v1Members = caseScopeService.diff(CASE, null, 1);
        assertThat(v1Members.addedBusinessKeys()).containsExactly("o1");
        assertThat(membershipOf("o2")).isTrue();
    }

    @Test
    void shrinkRequiresTwoDistinctNonRequesterApprovals() {
        caseScopeService.submit(CASE, keys("CHG-1", "alice", "o1", "o2", "o3"));
        ScopeChangeView shrink = caseScopeService.submit(
                CASE, keys("CHG-2", "alice", "o1"));

        assertThat(shrink.status()).isEqualTo(ScopeVersionStatus.PENDING_APPROVAL);
        assertThat(shrink.removedCount()).isEqualTo(2);
        // 待批准期间旧范围仍然全部有效，未释放任何对象
        assertThat(membershipOf("o2")).isTrue();
        assertThat(membershipOf("o3")).isTrue();

        // 申请人不能自批
        assertThatThrownBy(() -> caseScopeService.approve(
                CASE, "CHG-2", new ScopeApprovalRequest("alice", "自批")))
                .isInstanceOf(ConflictException.class);

        // 同一人重复批准只计一次
        ApprovalProgressView dup = caseScopeService.approve(
                CASE, "CHG-2", new ScopeApprovalRequest("boss-a", "同意"));
        caseScopeService.approve(CASE, "CHG-2", new ScopeApprovalRequest("boss-a", "再同意"));
        ApprovalProgressView progress = caseScopeService.getApprovalProgress(CASE, "CHG-2");
        assertThat(progress.approvalCount()).isEqualTo(1);
        assertThat(dup.complete()).isFalse();

        // 第二名不同人员批准后释放
        ApprovalProgressView done = caseScopeService.approve(
                CASE, "CHG-2", new ScopeApprovalRequest("boss-b", "同意"));
        assertThat(done.complete()).isTrue();
        assertThat(done.effective()).isTrue();
        assertThat(membershipOf("o1")).isTrue();
        assertThat(membershipOf("o2")).isFalse();
        assertThat(membershipOf("o3")).isFalse();
    }

    @Test
    void duplicateApprovalAfterEffectIsIdempotent() {
        caseScopeService.submit(CASE, keys("CHG-1", "alice", "o1", "o2"));
        caseScopeService.submit(CASE, keys("CHG-2", "alice", "o1"));
        twoApprovals("CHG-2");

        ApprovalProgressView again = caseScopeService.approve(
                CASE, "CHG-2", new ScopeApprovalRequest("boss-a", "重复"));
        assertThat(again.effective()).isTrue();
        assertThat(again.approvalCount()).isEqualTo(2);
        // 只有一条释放事件/对象，未重复释放
        long releaseEvents = caseScopeService.releaseReasons("o2").stream()
                .filter(r -> r.eventType().name().equals("RELEASE")).count();
        assertThat(releaseEvents).isEqualTo(1);
    }

    @Test
    void sameChangeNoIsIdempotentAndReturnsSameVersion() {
        ScopeChangeView first = caseScopeService.submit(
                CASE, keys("CHG-DUP", "alice", "o1"));
        ScopeChangeView again = caseScopeService.submit(
                CASE, keys("CHG-DUP", "alice", "o999"));
        assertThat(again.versionNo()).isEqualTo(first.versionNo());
        assertThat(again.changeNo()).isEqualTo("CHG-DUP");
        assertThat(versionRepository.count()).isEqualTo(1);
    }

    @Test
    void secondApprovalRejectsWholeBatchWhenAnotherCaseHoldAppears() {
        caseScopeService.submit(CASE, keys("CHG-1", "alice", "o1", "o2"));
        caseScopeService.submit(CASE, keys("CHG-2", "alice", "o1"));
        caseScopeService.approve(CASE, "CHG-2", new ScopeApprovalRequest("boss-a", "同意"));

        // 第二批准之前，o2 被另一案件保全：终确认条件变化，整批拒绝
        caseScopeService.submit("CASE-2", keys("CHG-X", "carol", "o2"));

        assertThatThrownBy(() -> caseScopeService.approve(
                CASE, "CHG-2", new ScopeApprovalRequest("boss-b", "同意")))
                .isInstanceOf(ScopeRejectedException.class);

        ScopeChangeView rejected = caseScopeService.getChange(CASE, "CHG-2");
        assertThat(rejected.status()).isEqualTo(ScopeVersionStatus.REJECTED);
        assertThat(rejected.rejectReasons()).anyMatch(r -> r.contains("其他案件保全变化"));
        // 整批拒绝：o1 保留、o2 也未释放，当前版本仍是 v1
        assertThat(membershipOf("o1")).isTrue();
        assertThat(membershipOf("o2")).isTrue();
        assertThat(caseRepository.findByCaseNo(CASE).orElseThrow().getCurrentVersionNo())
                .isEqualTo(1);
        // 被拒版本不能再批准
        assertThatThrownBy(() -> caseScopeService.approve(
                CASE, "CHG-2", new ScopeApprovalRequest("boss-b", "再试")))
                .isInstanceOf(ScopeRejectedException.class);
    }

    @Test
    void secondApprovalRejectsWholeBatchWhenRetentionRuleExtends() {
        caseScopeService.submit(CASE, keys("CHG-1", "alice", "o1", "o2"));
        caseScopeService.submit(CASE, keys("CHG-2", "alice", "o1"));
        caseScopeService.approve(CASE, "CHG-2", new ScopeApprovalRequest("boss-a", "同意"));

        // 审批窗口期保留规则延长，o2 截止时间变化
        objectService.saveRule(new RetentionRuleRequest("DOC", 365));

        assertThatThrownBy(() -> caseScopeService.approve(
                CASE, "CHG-2", new ScopeApprovalRequest("boss-b", "同意")))
                .isInstanceOf(ScopeRejectedException.class)
                .hasMessageContaining("整批拒绝");
        assertThat(membershipOf("o2")).isTrue();
    }

    @Test
    void unchangedConstraintsAllowReleaseAndRecordReleaseReason() {
        caseScopeService.submit(CASE, keys("CHG-1", "alice", "o1", "o2"));
        caseScopeService.submit(CASE, keys("CHG-2", "alice", "o1"));
        twoApprovals("CHG-2");

        List<ReleaseReasonView> reasons = caseScopeService.releaseReasons("o2");
        assertThat(reasons).anySatisfy(r -> {
            assertThat(r.eventType().name()).isEqualTo("RELEASE");
            assertThat(r.changeNo()).isEqualTo("CHG-2");
            assertThat(r.reason()).contains("范围缩小");
        });
    }

    @Test
    void closeCaseRequiresTwoApprovalsAndReleasesAll() {
        caseScopeService.submit(CASE, change(
                "CHG-1", List.of("o1", "o2"), List.of(), false, "alice", 2));
        ScopeChangeView close = caseScopeService.submit(CASE, change(
                "CHG-CLOSE", List.of(), List.of(), true, "alice", 2));
        assertThat(close.removedCount()).isEqualTo(2);
        twoApprovals("CHG-CLOSE");

        assertThat(membershipOf("o1")).isFalse();
        assertThat(membershipOf("o2")).isFalse();
        assertThat(caseRepository.findByCaseNo(CASE).orElseThrow().getStatus())
                .isEqualTo(CaseStatus.CLOSED);
        // 关闭后不能再变更
        assertThatThrownBy(() -> caseScopeService.submit(
                CASE, keys("CHG-3", "alice", "o3")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已关闭");
    }

    @Test
    void categoryCriteriaComputesByBatchAndProducesOneEffectiveVersion() {
        ScopeChangeView view = caseScopeService.submit(CASE, change(
                "CHG-CAT", List.of(), List.of("DOC"), false, "alice", 2));
        assertThat(view.status()).isEqualTo(ScopeVersionStatus.EFFECTIVE);
        assertThat(view.memberCount()).isEqualTo(3);
        assertThat(membershipOf("o1")).isTrue();
        assertThat(membershipOf("m1")).isFalse();
    }

    @Test
    void createdTimeWindowCriteriaSelectsOnlyObjectsInRange() {
        // 新建一个晚于初始对象的对象，再用创建时间下界筛选
        clock.setTime(Instant.parse("2026-03-01T00:00:00Z"));
        register("late-1", "DOC");
        clock.setTime(Instant.parse("2026-01-01T00:00:00Z"));

        ScopeChangeRequest window = new ScopeChangeRequest("CHG-TIME",
                new ScopeCriteriaRequest(List.of(), List.of("DOC"),
                        Instant.parse("2026-02-01T00:00:00Z"), null),
                false, "alice", "时间窗口", 2);
        ScopeChangeView view = caseScopeService.submit(CASE, window);
        assertThat(view.memberCount()).isEqualTo(1);
        assertThat(membershipOf("late-1")).isTrue();
        assertThat(membershipOf("o1")).isFalse();
    }

    @Test
    void diffBetweenVersionsReportsAddedAndRemoved() {
        caseScopeService.submit(CASE, keys("CHG-1", "alice", "o1", "o2"));
        caseScopeService.submit(CASE, keys("CHG-2", "alice", "o2", "o3"));

        ScopeDiffView diff = caseScopeService.diff(CASE, 1, 2);
        assertThat(diff.addedBusinessKeys()).containsExactly("o3");
        assertThat(diff.removedBusinessKeys()).containsExactly("o1");
        assertThat(diff.unchangedBusinessKeys()).containsExactly("o2");
        assertThat(diff.fromCount()).isEqualTo(2);
        assertThat(diff.toCount()).isEqualTo(2);
    }

    @Test
    void objectHoldsReportsAllHoldsWithScopeSource() {
        caseScopeService.submit(CASE, keys("CHG-1", "alice", "o1"));
        caseScopeService.submit("CASE-MANUAL", keys("CHG-M", "erin", "o1"));

        ObjectHoldsView holds = caseScopeService.objectHolds("o1");
        assertThat(holds.holdCount()).isEqualTo(2);
        assertThat(holds.holds()).extracting("caseNo")
                .containsExactly("CASE-1", "CASE-MANUAL");
        assertThat(holds.holds()).allSatisfy(h -> {
            assertThat(h.scopeManaged()).isTrue();
            assertThat(h.effectiveVersionNo()).isGreaterThanOrEqualTo(1);
        });
    }

    @Test
    void cannotStartNewChangeWhileOneIsPendingApproval() {
        caseScopeService.submit(CASE, keys("CHG-1", "alice", "o1", "o2"));
        caseScopeService.submit(CASE, keys("CHG-2", "alice", "o1"));
        assertThatThrownBy(() -> caseScopeService.submit(
                CASE, keys("CHG-3", "alice", "o3")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("进行中");
    }

    private boolean membershipOf(String businessKey) {
        Long objectId = objectRepository.findByBusinessKey(businessKey).orElseThrow().getId();
        return membershipRepository.findByObjectIdOrderByCaseNo(objectId).stream()
                .map(HoldMembership::getCaseNo).anyMatch(CASE::equals);
    }
}
