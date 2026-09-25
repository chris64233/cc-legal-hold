package com.chris64233.cc.legalhold.service;

import com.chris64233.cc.legalhold.domain.AuditEvent;
import com.chris64233.cc.legalhold.domain.DataObject;
import com.chris64233.cc.legalhold.domain.DataObjectStatus;
import com.chris64233.cc.legalhold.domain.DeletionToken;
import com.chris64233.cc.legalhold.repo.AuditEventRepository;
import com.chris64233.cc.legalhold.repo.DataObjectRepository;
import com.chris64233.cc.legalhold.repo.DeletionTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeletionService {

    private final DataObjectRepository objectRepository;
    private final DeletionTokenRepository tokenRepository;
    private final AuditEventRepository auditRepository;
    private final EligibilityService eligibilityService;
    private final Clock clock;
    private final Duration tokenTtl;

    public DeletionService(DataObjectRepository objectRepository,
                           DeletionTokenRepository tokenRepository,
                           AuditEventRepository auditRepository,
                           EligibilityService eligibilityService,
                           Clock clock,
                           @Value("${legalhold.deletion-token-ttl:PT5M}") Duration tokenTtl) {
        this.objectRepository = objectRepository;
        this.tokenRepository = tokenRepository;
        this.auditRepository = auditRepository;
        this.eligibilityService = eligibilityService;
        this.clock = clock;
        this.tokenTtl = tokenTtl;
    }

    @Transactional(readOnly = true)
    public EligibilityResult checkEligibility(String businessKey) {
        DataObject object = objectRepository.findByBusinessKey(businessKey)
                .orElseThrow(() -> new NotFoundException("数据对象不存在: " + businessKey));
        return eligibilityService.evaluate(object);
    }

    @Transactional
    public DeletionToken requestDeletion(String businessKey) {
        DataObject object = objectRepository.findByBusinessKey(businessKey)
                .orElseThrow(() -> new NotFoundException("数据对象不存在: " + businessKey));
        EligibilityResult eligibility = eligibilityService.evaluate(object);
        if (!eligibility.eligible()) {
            throw new BusinessException("对象不满足删除条件: " + String.join("; ", eligibility.blockingReasons()));
        }
        Instant expiresAt = clock.instant().plus(tokenTtl);
        return tokenRepository.save(new DeletionToken(UUID.randomUUID().toString(), object.getId(), expiresAt));
    }

    @Transactional
    public ConfirmationResult confirmDeletion(String tokenValue) {
        DeletionToken token = tokenRepository.findByTokenValueForUpdate(tokenValue)
                .orElseThrow(() -> new NotFoundException("确认令牌不存在"));

        if (!token.isPending()) {
            return ConfirmationResult.of(token, token.getOutcome() == DeletionToken.Outcome.CONFIRMED);
        }

        Instant now = clock.instant();
        if (!now.isBefore(token.getExpiresAt())) {
            token.resolve(DeletionToken.Outcome.EXPIRED, "确认令牌已过期", now);
            return ConfirmationResult.of(token, false);
        }

        DataObject object = objectRepository.findByIdForUpdate(token.getObjectId())
                .orElseThrow(() -> new BusinessException("令牌对应的数据对象不存在"));
        EligibilityResult eligibility = eligibilityService.evaluate(object);
        if (!eligibility.eligible()) {
            token.resolve(DeletionToken.Outcome.REJECTED,
                    "确认时条件已变化: " + String.join("; ", eligibility.blockingReasons()), now);
            return ConfirmationResult.of(token, false);
        }

        object.markDeleted(now);
        auditRepository.save(new AuditEvent("OBJECT_DELETED", object.getId(), object.getBusinessKey(), now,
                "删除确认令牌 " + tokenValue + " 执行不可逆删除"));
        token.resolve(DeletionToken.Outcome.CONFIRMED, "删除确认成功", now);
        return ConfirmationResult.of(token, true);
    }
}
