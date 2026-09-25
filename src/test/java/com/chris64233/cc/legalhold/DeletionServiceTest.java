package com.chris64233.cc.legalhold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chris64233.cc.legalhold.domain.DataObject;
import com.chris64233.cc.legalhold.domain.DeletionToken;
import com.chris64233.cc.legalhold.domain.DeletionTokenStatus;
import com.chris64233.cc.legalhold.domain.ObjectStatus;
import com.chris64233.cc.legalhold.repo.AuditEventRepository;
import com.chris64233.cc.legalhold.repo.DataObjectRepository;
import com.chris64233.cc.legalhold.repo.DeletionTokenRepository;
import com.chris64233.cc.legalhold.service.ConflictException;
import com.chris64233.cc.legalhold.service.DeletionBlockedException;
import com.chris64233.cc.legalhold.service.DeletionService;
import com.chris64233.cc.legalhold.service.LegalHoldService;
import com.chris64233.cc.legalhold.service.ObjectService;
import com.chris64233.cc.legalhold.support.MutableTestClock;
import com.chris64233.cc.legalhold.support.TestClockConfig;
import com.chris64233.cc.legalhold.web.dto.DeletionConfirmView;
import com.chris64233.cc.legalhold.web.dto.DeletionRequestView;
import com.chris64233.cc.legalhold.web.dto.HoldRequest;
import com.chris64233.cc.legalhold.web.dto.RegisterObjectRequest;
import com.chris64233.cc.legalhold.web.dto.RetentionRuleRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = {CcLegalHoldApplication.class, TestClockConfig.class})
@Transactional
class DeletionServiceTest {

    @Autowired
    private DeletionService deletionService;
    @Autowired
    private LegalHoldService legalHoldService;
    @Autowired
    private ObjectService objectService;
    @Autowired
    private DataObjectRepository objectRepository;
    @Autowired
    private DeletionTokenRepository tokenRepository;
    @Autowired
    private AuditEventRepository auditEventRepository;
    @Autowired
    private MutableTestClock clock;

    @BeforeEach
    void setUp() {
        clock.setTime(Instant.parse("2026-01-01T00:00:00Z"));
        objectService.saveRule(new RetentionRuleRequest("DOC", 30));
        objectService.register(new RegisterObjectRequest("d-1", "DOC"));
    }

    @Test
    void requestBeforeRetentionDeadlineIsRejected() {
        assertThatThrownBy(() -> deletionService.requestDeletion("d-1"))
                .isInstanceOf(DeletionBlockedException.class)
                .hasMessageContaining("保留期未满");
        assertThat(statusOf("d-1")).isEqualTo(ObjectStatus.ACTIVE);
    }

    @Test
    void requestWithActiveHoldIsRejected() {
        legalHoldService.apply(new HoldRequest("CASE-A", List.of("d-1"), "保全"));
        clock.advanceDays(40);
        assertThatThrownBy(() -> deletionService.requestDeletion("d-1"))
                .isInstanceOf(DeletionBlockedException.class)
                .hasMessageContaining("CASE-A");
    }

    @Test
    void confirmDeletesIrrecoverablyAndWritesAuditOnce() {
        clock.advanceDays(31);
        DeletionRequestView request = deletionService.requestDeletion("d-1");
        assertThat(request.expiresAt()).isEqualTo(Instant.parse("2026-02-01T00:10:00Z"));
        assertThat(statusOf("d-1")).isEqualTo(ObjectStatus.PENDING_DELETION);

        DeletionConfirmView first = deletionService.confirmDeletion(request.token());
        assertThat(first.deleted()).isTrue();
        assertThat(first.alreadyConfirmed()).isFalse();
        assertThat(statusOf("d-1")).isEqualTo(ObjectStatus.DELETED);
        assertThat(auditEventRepository.findAll())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getEventType()).isEqualTo("DELETE_CONFIRMED");
                    assertThat(event.getBusinessKey()).isEqualTo("d-1");
                });

        DeletionConfirmView repeat = deletionService.confirmDeletion(request.token());
        assertThat(repeat.alreadyConfirmed()).isTrue();
        assertThat(repeat.deleted()).isTrue();
        assertThat(auditEventRepository.count()).isEqualTo(1);
    }

    @Test
    void newHoldBetweenRequestAndConfirmRejectsAndInvalidatesToken() {
        clock.advanceDays(31);
        DeletionRequestView request = deletionService.requestDeletion("d-1");

        legalHoldService.apply(new HoldRequest("CASE-A", List.of("d-1"), "新增保全"));

        assertThatThrownBy(() -> deletionService.confirmDeletion(request.token()))
                .isInstanceOf(DeletionBlockedException.class)
                .hasMessageContaining("CASE-A");
        assertThat(statusOf("d-1")).isEqualTo(ObjectStatus.ACTIVE);

        DeletionToken token = tokenRepository.findAll().getFirst();
        assertThat(token.getStatus()).isEqualTo(DeletionTokenStatus.REJECTED);

        assertThatThrownBy(() -> deletionService.confirmDeletion(request.token()))
                .isInstanceOf(DeletionBlockedException.class)
                .hasMessageContaining("令牌已失效");
        assertThat(auditEventRepository.count()).isZero();
    }

    @Test
    void ruleExtensionBetweenRequestAndConfirmRejectsToken() {
        clock.advanceDays(31);
        DeletionRequestView request = deletionService.requestDeletion("d-1");

        objectService.saveRule(new RetentionRuleRequest("DOC", 60));

        assertThatThrownBy(() -> deletionService.confirmDeletion(request.token()))
                .isInstanceOf(DeletionBlockedException.class)
                .hasMessageContaining("保留期未满");
        assertThat(tokenRepository.findAll().getFirst().getStatus())
                .isEqualTo(DeletionTokenStatus.REJECTED);
        assertThat(statusOf("d-1")).isEqualTo(ObjectStatus.ACTIVE);
    }

    @Test
    void expiredTokenIsRejectedAndCannotDelete() {
        clock.advanceDays(31);
        DeletionRequestView request = deletionService.requestDeletion("d-1");

        clock.advanceSeconds(11 * 60);
        assertThatThrownBy(() -> deletionService.confirmDeletion(request.token()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已过期");
        assertThat(tokenRepository.findAll().getFirst().getStatus())
                .isEqualTo(DeletionTokenStatus.EXPIRED);
        assertThat(statusOf("d-1")).isEqualTo(ObjectStatus.ACTIVE);
    }

    @Test
    void secondRequestInvalidatesPreviousPendingToken() {
        clock.advanceDays(31);
        DeletionRequestView first = deletionService.requestDeletion("d-1");
        DeletionRequestView second = deletionService.requestDeletion("d-1");

        assertThatThrownBy(() -> deletionService.confirmDeletion(first.token()))
                .isInstanceOf(DeletionBlockedException.class)
                .hasMessageContaining("令牌已失效");

        DeletionConfirmView confirmed = deletionService.confirmDeletion(second.token());
        assertThat(confirmed.deleted()).isTrue();
        assertThat(statusOf("d-1")).isEqualTo(ObjectStatus.DELETED);
    }

    private ObjectStatus statusOf(String businessKey) {
        DataObject dataObject = objectRepository.findByBusinessKey(businessKey).orElseThrow();
        return dataObject.getStatus();
    }
}
