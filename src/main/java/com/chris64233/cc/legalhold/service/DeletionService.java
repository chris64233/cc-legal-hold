package com.chris64233.cc.legalhold.service;

import com.chris64233.cc.legalhold.domain.AuditEvent;
import com.chris64233.cc.legalhold.domain.DataObject;
import com.chris64233.cc.legalhold.domain.DeletionToken;
import com.chris64233.cc.legalhold.domain.DeletionTokenStatus;
import com.chris64233.cc.legalhold.domain.HoldMembership;
import com.chris64233.cc.legalhold.domain.ObjectStatus;
import com.chris64233.cc.legalhold.domain.RetentionRule;
import com.chris64233.cc.legalhold.repo.AuditEventRepository;
import com.chris64233.cc.legalhold.repo.DataObjectRepository;
import com.chris64233.cc.legalhold.repo.DeletionTokenRepository;
import com.chris64233.cc.legalhold.repo.HoldMembershipRepository;
import com.chris64233.cc.legalhold.repo.RetentionRuleRepository;
import com.chris64233.cc.legalhold.time.DomainClock;
import com.chris64233.cc.legalhold.web.dto.DeletionConfirmView;
import com.chris64233.cc.legalhold.web.dto.DeletionRequestView;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeletionService {

    static final String AUDIT_DELETE_CONFIRMED = "DELETE_CONFIRMED";

    private final DataObjectRepository objectRepository;
    private final RetentionRuleRepository ruleRepository;
    private final HoldMembershipRepository membershipRepository;
    private final DeletionTokenRepository tokenRepository;
    private final AuditEventRepository auditEventRepository;
    private final DomainClock clock;
    private final SecureRandom secureRandom = new SecureRandom();
    private final long tokenTtlMinutes;

    public DeletionService(DataObjectRepository objectRepository,
                           RetentionRuleRepository ruleRepository,
                           HoldMembershipRepository membershipRepository,
                           DeletionTokenRepository tokenRepository,
                           AuditEventRepository auditEventRepository,
                           DomainClock clock,
                           @Value("${legalhold.deletion-token.ttl-minutes:10}")
                           long tokenTtlMinutes) {
        this.objectRepository = objectRepository;
        this.ruleRepository = ruleRepository;
        this.membershipRepository = membershipRepository;
        this.tokenRepository = tokenRepository;
        this.auditEventRepository = auditEventRepository;
        this.clock = clock;
        this.tokenTtlMinutes = tokenTtlMinutes;
    }

    /**
     * 阶段一：申请删除。服务端按当前保留规则与有效保全计算资格，
     * 通过后作废旧令牌并签发短期一次性确认令牌。
     */
    @Transactional
    public DeletionRequestView requestDeletion(String businessKey) {
        Long objectId = objectRepository.findIdByBusinessKey(businessKey)
                .orElseThrow(() -> new NotFoundException("对象不存在: " + businessKey));
        DataObject dataObject = objectRepository.findByIdForUpdate(objectId).orElseThrow();

        if (dataObject.getStatus() == ObjectStatus.DELETED) {
            throw new ConflictException("对象已删除: " + businessKey);
        }
        ensureDeletable(dataObject);

        for (DeletionToken old : tokenRepository.findByObjectIdAndStatus(
                dataObject.getId(), DeletionTokenStatus.PENDING)) {
            old.setStatus(DeletionTokenStatus.REJECTED);
            old.setRejectReason("已被新的删除申请取代");
        }
        dataObject.setStatus(ObjectStatus.PENDING_DELETION);

        Instant now = clock.now();
        String tokenValue = newTokenValue();
        tokenRepository.save(new DeletionToken(tokenValue, dataObject.getId(),
                now.plus(tokenTtlMinutes, ChronoUnit.MINUTES)));
        return new DeletionRequestView(tokenValue, now.plus(tokenTtlMinutes, ChronoUnit.MINUTES));
    }

    /**
     * 阶段二：确认删除。锁令牌行与对象行后重新校验保留规则与法律保全；
     * 条件变化则拒绝并使令牌失效。重复确认返回原结果，不再次执行。
     */
    @Transactional(noRollbackFor = {DeletionBlockedException.class, ConflictException.class})
    public DeletionConfirmView confirmDeletion(String tokenValue) {
        DeletionToken token = tokenRepository.findByTokenValueForUpdate(tokenValue)
                .orElseThrow(() -> new NotFoundException("确认令牌不存在: " + tokenValue));
        DataObject dataObject = objectRepository.findByIdForUpdate(token.getObjectId())
                .orElseThrow();

        if (token.getStatus() == DeletionTokenStatus.CONFIRMED) {
            return new DeletionConfirmView(tokenValue, DeletionTokenStatus.CONFIRMED,
                    dataObject.getBusinessKey(),
                    dataObject.getStatus() == ObjectStatus.DELETED, true, null);
        }
        if (token.getStatus() == DeletionTokenStatus.REJECTED) {
            throw new DeletionBlockedException(List.of(
                    "令牌已失效: " + token.getRejectReason()));
        }
        if (token.getStatus() == DeletionTokenStatus.EXPIRED) {
            throw new ConflictException("确认令牌已过期");
        }

        Instant now = clock.now();
        if (now.isAfter(token.getExpiresAt())) {
            token.setStatus(DeletionTokenStatus.EXPIRED);
            dataObject.setStatus(ObjectStatus.ACTIVE);
            throw new ConflictException("确认令牌已过期");
        }

        List<String> blockers = deletionBlockers(dataObject);
        if (!blockers.isEmpty()) {
            token.setStatus(DeletionTokenStatus.REJECTED);
            token.setRejectReason(String.join("; ", blockers));
            dataObject.setStatus(ObjectStatus.ACTIVE);
            throw new DeletionBlockedException(blockers);
        }

        token.setStatus(DeletionTokenStatus.CONFIRMED);
        token.setConfirmedAt(now);
        dataObject.setStatus(ObjectStatus.DELETED);
        auditEventRepository.save(new AuditEvent(AUDIT_DELETE_CONFIRMED,
                dataObject.getId(), dataObject.getBusinessKey(),
                "token=" + tokenValue, now));

        return new DeletionConfirmView(tokenValue, DeletionTokenStatus.CONFIRMED,
                dataObject.getBusinessKey(), true, false, null);
    }

    private void ensureDeletable(DataObject dataObject) {
        List<String> blockers = deletionBlockers(dataObject);
        if (!blockers.isEmpty()) {
            throw new DeletionBlockedException(blockers);
        }
    }

    private List<String> deletionBlockers(DataObject dataObject) {
        List<String> blockers = new ArrayList<>();
        Instant now = clock.now();
        RetentionRule rule = ruleRepository.findByCategory(dataObject.getCategory()).orElse(null);
        if (rule == null) {
            blockers.add("类别缺少有效保留规则: " + dataObject.getCategory());
        } else {
            Instant deadline = dataObject.getCreatedAt()
                    .plus(rule.getMinRetentionDays(), ChronoUnit.DAYS);
            if (now.isBefore(deadline)) {
                blockers.add("保留期未满，保留截止时间: " + deadline);
            }
        }
        List<String> activeCases = membershipRepository
                .findByObjectIdOrderByCaseNo(dataObject.getId())
                .stream()
                .map(HoldMembership::getCaseNo)
                .toList();
        if (!activeCases.isEmpty()) {
            blockers.add("存在有效法律保全案件: " + String.join(", ", activeCases));
        }
        return blockers;
    }

    private String newTokenValue() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
