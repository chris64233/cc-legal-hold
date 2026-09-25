package com.chris64233.cc.legalhold;

import static org.assertj.core.api.Assertions.assertThat;

import com.chris64233.cc.legalhold.service.ObjectService;
import com.chris64233.cc.legalhold.support.MutableTestClock;
import com.chris64233.cc.legalhold.support.TestClockConfig;
import com.chris64233.cc.legalhold.web.dto.EligibilityView;
import com.chris64233.cc.legalhold.web.dto.RegisterObjectRequest;
import com.chris64233.cc.legalhold.web.dto.RetentionRuleRequest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = {CcLegalHoldApplication.class, TestClockConfig.class})
@Transactional
class RetentionEligibilityTest {

    @Autowired
    private ObjectService objectService;

    @Autowired
    private MutableTestClock clock;

    @BeforeEach
    void resetClock() {
        clock.setTime(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void eligibilityIsComputedFromCreationTimeAndCurrentRule() {
        objectService.saveRule(new RetentionRuleRequest("DOC", 30));
        objectService.register(new RegisterObjectRequest("doc-1", "DOC"));

        EligibilityView early = objectService.eligibility("doc-1");
        assertThat(early.eligible()).isFalse();
        assertThat(early.retentionSatisfied()).isFalse();
        assertThat(early.retentionDeadline())
                .isEqualTo(Instant.parse("2026-01-31T00:00:00Z"));
        assertThat(early.blockingReasons()).anyMatch(reason -> reason.contains("保留期未满"));

        clock.setTime(Instant.parse("2026-01-31T00:00:00Z"));
        EligibilityView atDeadline = objectService.eligibility("doc-1");
        assertThat(atDeadline.retentionSatisfied()).isTrue();
        assertThat(atDeadline.eligible()).isTrue();
        assertThat(atDeadline.blockingReasons()).isEmpty();
    }

    @Test
    void updatedRuleIsUsedForEligibilityAndRequestsCannotDeclareIt() {
        objectService.saveRule(new RetentionRuleRequest("DOC", 10));
        objectService.register(new RegisterObjectRequest("doc-2", "DOC"));
        clock.advanceDays(15);
        assertThat(objectService.eligibility("doc-2").eligible()).isTrue();

        objectService.saveRule(new RetentionRuleRequest("DOC", 30));
        EligibilityView afterRuleChange = objectService.eligibility("doc-2");
        assertThat(afterRuleChange.eligible()).isFalse();
        assertThat(afterRuleChange.minRetentionDays()).isEqualTo(30);
    }

    @Test
    void missingRuleBlocksDeletionAndIsExplained() {
        objectService.register(new RegisterObjectRequest("doc-3", "UNKNOWN"));
        EligibilityView view = objectService.eligibility("doc-3");
        assertThat(view.eligible()).isFalse();
        assertThat(view.blockingReasons())
                .anyMatch(reason -> reason.contains("缺少有效保留规则"));
    }
}
