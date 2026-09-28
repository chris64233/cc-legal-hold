package com.chris64233.cc.legalhold;

import static org.assertj.core.api.Assertions.assertThat;

import com.chris64233.cc.legalhold.domain.ScopeVersionStatus;
import com.chris64233.cc.legalhold.repo.CaseScopeMemberRepository;
import com.chris64233.cc.legalhold.repo.CaseScopeVersionRepository;
import com.chris64233.cc.legalhold.repo.DataObjectRepository;
import com.chris64233.cc.legalhold.repo.HoldMembershipRepository;
import com.chris64233.cc.legalhold.repo.LegalCaseRepository;
import com.chris64233.cc.legalhold.repo.RetentionRuleRepository;
import com.chris64233.cc.legalhold.repo.ScopeApprovalRepository;
import com.chris64233.cc.legalhold.service.CaseScopeService;
import com.chris64233.cc.legalhold.service.ObjectService;
import com.chris64233.cc.legalhold.support.TestClockConfig;
import com.chris64233.cc.legalhold.web.dto.ApprovalRequest;
import com.chris64233.cc.legalhold.web.dto.RegisterObjectRequest;
import com.chris64233.cc.legalhold.web.dto.RetentionRuleRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeChangeRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeCriteriaRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeVersionView;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 大批量范围计算的分批执行与“崩溃/重启后续跑”测试：
 * 每调用只推进一个批次，模拟服务在中途退出后用同一 changeNo 安全重试。
 */
@SpringBootTest(classes = {CcLegalHoldApplication.class, TestClockConfig.class}, properties = {
        "legalhold.scope.materialize-batch-size=2",
        "legalhold.scope.max-batches-per-call=1"
})
class ScopeBatchResumeTest {

    @Autowired
    private CaseScopeService caseScopeService;
    @Autowired
    private ObjectService objectService;
    @Autowired
    private DataObjectRepository objectRepository;
    @Autowired
    private CaseScopeVersionRepository versionRepository;
    @Autowired
    private CaseScopeMemberRepository memberRepository;
    @Autowired
    private HoldMembershipRepository membershipRepository;
    @Autowired
    private LegalCaseRepository caseRepository;
    @Autowired
    private ScopeApprovalRepository approvalRepository;
    @Autowired
    private RetentionRuleRepository ruleRepository;

    @BeforeEach
    void setUp() {
        approvalRepository.deleteAll();
        membershipRepository.deleteAll();
        memberRepository.deleteAll();
        versionRepository.deleteAll();
        caseRepository.deleteAll();
        objectRepository.deleteAll();
        ruleRepository.deleteAll();
        objectService.saveRule(new RetentionRuleRequest("DOC", 30));
        for (int i = 1; i <= 7; i++) {
            objectService.register(new RegisterObjectRequest("b-" + i, "DOC"));
        }
    }

    @Test
    void materializesAcrossBatchesAndResumeIsSafeAndIdempotent() {
        String changeNo = "BIG-EXPAND";
        ScopeVersionView first = caseScopeService.submitChange(new ScopeChangeRequest(
                "CASE-BIG", changeNo, "大批扩围", "alice",
                new ScopeCriteriaRequest(List.of(), List.of("DOC"), null, null)));

        // 每调用只跑一批：7 个对象 / 批大小 2，需要多次续跑；期间对外没有生效版本
        int batches = 1;
        ScopeVersionView current = first;
        while (current.status() == ScopeVersionStatus.PENDING_MATERIALIZE && batches < 20) {
            current = caseScopeService.resume(changeNo);
            batches++;
        }
        assertThat(current.status()).isEqualTo(ScopeVersionStatus.EFFECTIVE);
        assertThat(batches).isGreaterThan(1);

        // 成员行恰好 7 行，重试验没有重复
        Long versionId = versionRepository.findByChangeNo(changeNo).orElseThrow().getId();
        assertThat(memberRepository.findByVersionId(versionId)).hasSize(7);

        // 完成后重复 resume 不产生任何副作用
        ScopeVersionView again = caseScopeService.resume(changeNo);
        assertThat(again.status()).isEqualTo(ScopeVersionStatus.EFFECTIVE);
        assertThat(memberRepository.findByVersionId(versionId)).hasSize(7);

        for (int i = 1; i <= 7; i++) {
            assertThat(membershipRepository
                    .findByCaseNoAndObjectId("CASE-BIG",
                            objectRepository.findByBusinessKey("b-" + i).orElseThrow().getId()))
                    .as("扩围完成后每个对象都受保全: b-" + i)
                    .isPresent();
        }
    }

    @Test
    void partialBatchesNeverReleaseObjectsAndShrinkFinishesAfterResumeAndApprovals() {
        // 先建立 7 个对象的有效范围（分批多次续跑完成）
        String expand = "BIG-E";
        ScopeVersionView v = caseScopeService.submitChange(categoryChange("CASE-S", expand));
        while (v.status() == ScopeVersionStatus.PENDING_MATERIALIZE) {
            v = caseScopeService.resume(expand);
        }
        assertThat(v.status()).isEqualTo(ScopeVersionStatus.EFFECTIVE);

        // 缩围到只剩 b-1,b-2：待释放 5 个，分批物化
        String shrink = "BIG-S";
        ScopeVersionView s = caseScopeService.submitChange(new ScopeChangeRequest(
                "CASE-S", shrink, "大批缩围", "alice",
                new ScopeCriteriaRequest(List.of("b-1", "b-2"), List.of(), null, null)));
        int guard = 0;
        while (s.status() == ScopeVersionStatus.PENDING_MATERIALIZE && guard++ < 20) {
            s = caseScopeService.resume(shrink);
        }
        assertThat(s.status()).isEqualTo(ScopeVersionStatus.PENDING_APPROVAL);
        assertThat(s.removedCount()).isEqualTo(5);

        // 物化分批期间绝无部分释放
        for (int i = 3; i <= 7; i++) {
            assertThat(membershipRepository.findByCaseNoAndObjectId("CASE-S",
                    objectRepository.findByBusinessKey("b-" + i).orElseThrow().getId()))
                    .as("审批完成前不能释放: b-" + i).isPresent();
        }

        caseScopeService.approve(new ApprovalRequest(shrink, "bob", "APPROVE", null));
        caseScopeService.approve(new ApprovalRequest(shrink, "carol", "APPROVE", null));

        assertThat(caseScopeService.getVersion("CASE-S", 2).status())
                .isEqualTo(ScopeVersionStatus.EFFECTIVE);
        for (int i = 3; i <= 7; i++) {
            assertThat(membershipRepository.findByCaseNoAndObjectId("CASE-S",
                    objectRepository.findByBusinessKey("b-" + i).orElseThrow().getId()))
                    .as("双人批准后整批释放: b-" + i).isEmpty();
        }
        for (int i = 1; i <= 2; i++) {
            assertThat(membershipRepository.findByCaseNoAndObjectId("CASE-S",
                    objectRepository.findByBusinessKey("b-" + i).orElseThrow().getId()))
                    .as("保留对象继续受保全: b-" + i).isPresent();
        }
        assertThat(caseScopeService.resume(shrink).status())
                .isEqualTo(ScopeVersionStatus.EFFECTIVE);
    }

    private ScopeChangeRequest categoryChange(String caseNo, String changeNo) {
        return new ScopeChangeRequest(caseNo, changeNo, "批处理", "alice",
                new ScopeCriteriaRequest(List.of(), List.of("DOC"), null, null));
    }
}
