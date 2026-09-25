package com.chris64233.cc.legalhold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chris64233.cc.legalhold.domain.HoldEvent;
import com.chris64233.cc.legalhold.repo.HoldEventRepository;
import com.chris64233.cc.legalhold.service.ConflictException;
import com.chris64233.cc.legalhold.service.LegalHoldService;
import com.chris64233.cc.legalhold.service.ObjectService;
import com.chris64233.cc.legalhold.support.MutableTestClock;
import com.chris64233.cc.legalhold.support.TestClockConfig;
import com.chris64233.cc.legalhold.web.dto.HoldRequest;
import com.chris64233.cc.legalhold.web.dto.HoldResultView;
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
class LegalHoldServiceTest {

    @Autowired
    private LegalHoldService legalHoldService;
    @Autowired
    private ObjectService objectService;
    @Autowired
    private HoldEventRepository eventRepository;
    @Autowired
    private MutableTestClock clock;

    @BeforeEach
    void setUp() {
        clock.setTime(Instant.parse("2026-01-01T00:00:00Z"));
        objectService.saveRule(new RetentionRuleRequest("DOC", 30));
        objectService.register(new RegisterObjectRequest("h-1", "DOC"));
        objectService.register(new RegisterObjectRequest("h-2", "DOC"));
    }

    @Test
    void applyCreatesImmutableEventsAndBlocksEligibility() {
        HoldResultView result = legalHoldService.apply(new HoldRequest(
                "CASE-A", List.of("h-1", "h-2"), "诉讼保全"));

        assertThat(result.eventNo()).startsWith("EVT-");
        assertThat(result.businessKeys()).containsExactlyInAnyOrder("h-1", "h-2");
        assertThat(result.effectiveAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));

        List<HoldEvent> events = eventRepository.findAll();
        assertThat(events).hasSize(2);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.getEventNo()).isEqualTo(result.eventNo());
            assertThat(event.getReason()).isEqualTo("诉讼保全");
        });

        assertThat(objectService.eligibility("h-1").eligible()).isFalse();
        assertThat(objectService.eligibility("h-1").activeHoldCases()).containsExactly("CASE-A");
    }

    @Test
    void sameCaseApplyIsIdempotent() {
        legalHoldService.apply(new HoldRequest("CASE-A", List.of("h-1"), "第一次"));
        HoldResultView second = legalHoldService.apply(
                new HoldRequest("CASE-A", List.of("h-1"), "重复申请"));
        assertThat(second.businessKeys()).isEmpty();
        assertThat(eventRepository.count()).isEqualTo(1);
    }

    @Test
    void objectCanBelongToMultipleCasesAndIsEligibleOnlyAfterAllReleased() {
        legalHoldService.apply(new HoldRequest("CASE-A", List.of("h-1"), "案件A"));
        legalHoldService.apply(new HoldRequest("CASE-B", List.of("h-1"), "案件B"));
        clock.advanceDays(40);

        legalHoldService.release(new HoldRequest("CASE-A", List.of("h-1"), "案件A解除"));
        assertThat(objectService.eligibility("h-1").eligible()).isFalse();
        assertThat(objectService.eligibility("h-1").activeHoldCases()).containsExactly("CASE-B");

        legalHoldService.release(new HoldRequest("CASE-B", List.of("h-1"), "案件B解除"));
        assertThat(objectService.eligibility("h-1").eligible()).isTrue();
        assertThat(objectService.eligibility("h-1").activeHoldCases()).isEmpty();
    }

    @Test
    void releaseWithoutActiveHoldFails() {
        assertThatThrownBy(() -> legalHoldService.release(
                new HoldRequest("CASE-X", List.of("h-1"), "无保全解除")))
                .isInstanceOf(ConflictException.class);
    }
}
