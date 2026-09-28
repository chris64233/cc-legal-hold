package com.chris64233.cc.legalhold.service;

import com.chris64233.cc.legalhold.domain.CaseScopeMember;
import com.chris64233.cc.legalhold.domain.CaseScopeVersion;
import com.chris64233.cc.legalhold.domain.HoldEvent;
import com.chris64233.cc.legalhold.domain.HoldEventType;
import com.chris64233.cc.legalhold.domain.HoldMembership;
import com.chris64233.cc.legalhold.domain.LegalCase;
import com.chris64233.cc.legalhold.domain.ObjectStatus;
import com.chris64233.cc.legalhold.domain.RetentionRule;
import com.chris64233.cc.legalhold.domain.ScopeApproval;
import com.chris64233.cc.legalhold.domain.ScopeDeltaType;
import com.chris64233.cc.legalhold.domain.ScopeVersionStatus;
import com.chris64233.cc.legalhold.repo.CaseScopeMemberRepository;
import com.chris64233.cc.legalhold.repo.CaseScopeVersionRepository;
import com.chris64233.cc.legalhold.repo.DataObjectRepository;
import com.chris64233.cc.legalhold.repo.HoldEventRepository;
import com.chris64233.cc.legalhold.repo.HoldMembershipRepository;
import com.chris64233.cc.legalhold.repo.LegalCaseRepository;
import com.chris64233.cc.legalhold.repo.RetentionRuleRepository;
import com.chris64233.cc.legalhold.repo.ScopeApprovalRepository;
import com.chris64233.cc.legalhold.time.DomainClock;
import com.chris64233.cc.legalhold.web.dto.CloseCaseRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeChangeRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeCriteriaRequest;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 范围变更的数据库事务步骤集合。每个公共方法都是独立短事务：
 * 初始化版本、物化一批、定稿、投审批票。分批步骤可被外层安全重试，
 * 而“对外只有一个完整版本”由版本状态与案件行锁保证。
 *
 * <p>所有步骤都遵循同一加锁顺序：先锁案件行，再锁版本行，生效时再按
 * 对象 ID 升序锁对象行，杜绝死锁。</p>
 */
@Component
public class ScopeChangeTxRunner {

    private final LegalCaseRepository caseRepository;
    private final CaseScopeVersionRepository versionRepository;
    private final CaseScopeMemberRepository memberRepository;
    private final DataObjectRepository objectRepository;
    private final HoldMembershipRepository membershipRepository;
    private final HoldEventRepository eventRepository;
    private final ScopeApprovalRepository approvalRepository;
    private final RetentionRuleRepository ruleRepository;
    private final ObjectMapper objectMapper;
    private final DomainClock clock;
    private final int batchSize;

    public ScopeChangeTxRunner(LegalCaseRepository caseRepository,
                               CaseScopeVersionRepository versionRepository,
                               CaseScopeMemberRepository memberRepository,
                               DataObjectRepository objectRepository,
                               HoldMembershipRepository membershipRepository,
                               HoldEventRepository eventRepository,
                               ScopeApprovalRepository approvalRepository,
                               RetentionRuleRepository ruleRepository,
                               ObjectMapper objectMapper,
                               DomainClock clock,
                               BatchProperties batchProperties) {
        this.caseRepository = caseRepository;
        this.versionRepository = versionRepository;
        this.memberRepository = memberRepository;
        this.objectRepository = objectRepository;
        this.membershipRepository = membershipRepository;
        this.eventRepository = eventRepository;
        this.approvalRepository = approvalRepository;
        this.ruleRepository = ruleRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.batchSize = batchProperties.getMaterializeBatchSize();
    }

    /** 初始化结果；newlyCreated=false 表示命中变更业务号幂等重放。 */
    public record InitResult(CaseScopeVersion version, boolean newlyCreated) {
    }

    /**
     * 初始化变更：锁案件、占用变更业务号、固化条件与对象高水位、建出版本行。
     */
    @Transactional
    public InitResult initChange(ScopeChangeRequest request) {
        CaseScopeVersion existing = versionRepository.findByChangeNo(request.changeNo()).orElse(null);
        if (existing != null) {
            return new InitResult(existing, false);
        }
        LegalCase legalCase = lockOrCreateCase(request.caseNo());
        // 案件行锁内复查：并发提交同一 changeNo 时，后到者在此命中幂等重放。
        existing = versionRepository.findByChangeNo(request.changeNo()).orElse(null);
        if (existing != null) {
            return new InitResult(existing, false);
        }
        if (legalCase.isClosed()) {
            throw new ConflictException("案件已关闭，不能再变更范围: " + request.caseNo());
        }
        if (legalCase.getActiveChangeNo() != null
                && !legalCase.getActiveChangeNo().equals(request.changeNo())) {
            throw new ConflictException("案件存在进行中的范围变更: " + legalCase.getActiveChangeNo());
        }
        validateCriteria(request.criteria());

        int basedOn = legalCase.getCurrentVersionNo();
        CaseScopeVersion version = new CaseScopeVersion(
                request.caseNo(), basedOn + 1, request.changeNo(),
                com.chris64233.cc.legalhold.domain.ChangeType.EXPAND,
                ScopeVersionStatus.PENDING_MATERIALIZE, basedOn,
                writeJson(request.criteria()), request.reason(), request.createdBy(), clock.now());
        version.setMaterializeMaxId(objectRepository.findMaxId());
        version.setMaterializeAfterId(0L);
        version.setRemovedAfterId(0L);
        version = versionRepository.saveAndFlush(version);
        legalCase.setActiveChangeNo(request.changeNo());
        return new InitResult(version, true);
    }

    /**
     * 关闭案件：目标范围为空集，整案释放，同样物化 + 双人审批。
     */
    @Transactional
    public InitResult initClose(CloseCaseRequest request) {
        CaseScopeVersion existing = versionRepository.findByChangeNo(request.changeNo()).orElse(null);
        if (existing != null) {
            return new InitResult(existing, false);
        }
        LegalCase legalCase = lockOrCreateCase(request.caseNo());
        existing = versionRepository.findByChangeNo(request.changeNo()).orElse(null);
        if (existing != null) {
            return new InitResult(existing, false);
        }
        if (legalCase.isClosed()) {
            throw new ConflictException("案件已关闭: " + request.caseNo());
        }
        if (legalCase.getActiveChangeNo() != null
                && !legalCase.getActiveChangeNo().equals(request.changeNo())) {
            throw new ConflictException("案件存在进行中的范围变更: " + legalCase.getActiveChangeNo());
        }

        int basedOn = legalCase.getCurrentVersionNo();
        ScopeCriteriaRequest empty = new ScopeCriteriaRequest(List.of(), List.of(), null, null);
        CaseScopeVersion version = new CaseScopeVersion(
                request.caseNo(), basedOn + 1, request.changeNo(),
                com.chris64233.cc.legalhold.domain.ChangeType.CLOSE,
                ScopeVersionStatus.PENDING_MATERIALIZE, basedOn,
                writeJson(empty), request.reason(), request.createdBy(), clock.now());
        version.setMaterializeMaxId(objectRepository.findMaxId());
        version.setMaterializeAfterId(0L);
        version.setRemovedAfterId(0L);
        version.setExplicitDone(true);
        version = versionRepository.saveAndFlush(version);
        legalCase.setActiveChangeNo(request.changeNo());
        return new InitResult(version, true);
    }

    /**
     * 物化一批目标成员与 REMOVED 差异行。返回 true 表示全部物化完成。
     * 每批独立事务提交，游标落在版本行上；失败/重启后用同一版本续跑，
     * 已插入成员靠 (版本, 对象) 唯一约束跳过，绝不重复。
     */
    @Transactional
    public boolean materializeBatch(Long versionId) {
        CaseScopeVersion version = lockCaseAndVersion(versionId);
        if (version.isMaterialized()
                || version.getStatus() != ScopeVersionStatus.PENDING_MATERIALIZE) {
            return true;
        }

        if (!version.isExplicitDone()) {
            materializeExplicitKeys(version);
            version.setExplicitDone(true);
        }

        boolean targetScanDone = scanTargetPage(version);
        if (targetScanDone && scanRemovedPage(version)) {
            finishMaterialized(version);
        }
        return version.isMaterialized();
    }

    /**
     * 物化完成后定稿：判定扩围/缩围；扩围立即生效，缩围/关案转待审批并写约束快照。
     * 已是终态时原样返回（幂等）。
     */
    @Transactional
    public CaseScopeVersion finalizeIfNeeded(Long versionId) {
        CaseScopeVersion version = lockCaseAndVersion(versionId);
        if (version.getStatus() != ScopeVersionStatus.PENDING_MATERIALIZE) {
            return version;
        }
        if (!version.isMaterialized()) {
            return version;
        }
        LegalCase legalCase = requireCase(version.getCaseNo());

        if (version.getAddedCount() == 0 && version.getRemovedCount() == 0) {
            return rejectVersion(legalCase, version,
                    "目标范围与当前生效版本一致，无新增也无移除对象");
        }

        if (version.getChangeType() == com.chris64233.cc.legalhold.domain.ChangeType.CLOSE) {
            version.setConstraintSnapshotJson(writeJson(buildSnapshots(version)));
            version.setStatus(ScopeVersionStatus.PENDING_APPROVAL);
            return version;
        }

        if (version.getRemovedCount() > 0) {
            version.setChangeType(com.chris64233.cc.legalhold.domain.ChangeType.SHRINK);
            version.setConstraintSnapshotJson(writeJson(buildSnapshots(version)));
            version.setStatus(ScopeVersionStatus.PENDING_APPROVAL);
            return version;
        }

        // 纯扩围：立即生效。
        return effectiveExpand(legalCase, version);
    }

    /**
     * 投审批票。事件幂等；两名不同人员 APPROVE 后立即最终复核并生效，
     * 任一对象条件变化则整批拒绝；任一 REJECT 直接整批拒绝。
     */
    @Transactional
    public CaseScopeVersion vote(String changeNo, String reviewer,
                                 com.chris64233.cc.legalhold.domain.ApprovalVoteType voteType,
                                 String comment) {
        CaseScopeVersion version = versionRepository.findByChangeNo(changeNo)
                .orElseThrow(() -> new NotFoundException("范围变更不存在: " + changeNo));
        final String caseNo = version.getCaseNo();
        LegalCase legalCase = caseRepository.findByCaseNoForUpdate(caseNo)
                .orElseThrow(() -> new NotFoundException("案件不存在: " + caseNo));
        version = versionRepository.findByIdForUpdate(version.getId()).orElseThrow();

        if (version.getCreatedBy() != null
                && version.getCreatedBy().equalsIgnoreCase(reviewer)) {
            throw new ConflictException("审批人不能是变更申请人: " + reviewer);
        }

        boolean alreadyVoted = approvalRepository.existsByChangeNoAndReviewerAndVoteType(
                changeNo, reviewer, voteType);

        if (version.getStatus() == ScopeVersionStatus.EFFECTIVE
                || version.getStatus() == ScopeVersionStatus.SUPERSEDED
                || version.getStatus() == ScopeVersionStatus.REJECTED) {
            if (alreadyVoted) {
                return version; // 终态后重放同一审批事件：幂等无效果
            }
            throw new ConflictException("范围变更已终结: " + version.getStatus());
        }
        if (version.getStatus() != ScopeVersionStatus.PENDING_APPROVAL) {
            throw new ConflictException("范围变更当前不可审批: " + version.getStatus());
        }

        if (!alreadyVoted) {
            approvalRepository.save(new ScopeApproval(changeNo, version.getCaseNo(),
                    version.getId(), voteType, reviewer, comment, clock.now()));
        }

        if (voteType == com.chris64233.cc.legalhold.domain.ApprovalVoteType.REJECT) {
            return rejectVersion(legalCase, version,
                    "被审批人 " + reviewer + " 拒绝" + (comment == null ? "" : ": " + comment));
        }

        List<ScopeApproval> approves = approvalRepository
                .findByChangeNoAndVoteTypeOrderByVotedAtAsc(
                        changeNo, com.chris64233.cc.legalhold.domain.ApprovalVoteType.APPROVE);
        long distinctApprovers = approves.stream().map(ScopeApproval::getReviewer)
                .map(String::toLowerCase).distinct().count();
        if (distinctApprovers < 2) {
            return version; // 等待第二名不同审批人
        }
        return recheckAndEffective(legalCase, version);
    }

    // ------------------------------------------------------------------
    // 内部步骤
    // ------------------------------------------------------------------

    private void materializeExplicitKeys(CaseScopeVersion version) {
        ScopeCriteriaRequest criteria = readCriteria(version);
        List<String> keys = criteria.businessKeys() == null
                ? List.of()
                : criteria.businessKeys().stream().distinct().toList();
        List<Long> distinctIds = new ArrayList<>();
        for (List<String> chunk : partition(keys, batchSize)) {
            List<com.chris64233.cc.legalhold.domain.DataObject> refs =
                    objectRepository.findByBusinessKeyIn(chunk);
            for (com.chris64233.cc.legalhold.domain.DataObject ref : refs) {
                if (ref.getStatus() == ObjectStatus.DELETED) {
                    throw new ConflictException("对象已删除，无法纳入保全: " + ref.getBusinessKey());
                }
                if (!distinctIds.contains(ref.getId())) {
                    distinctIds.add(ref.getId());
                }
            }
        }
        if (distinctIds.size() != keys.size()) {
            throw new NotFoundException("部分显式对象不存在");
        }
        insertTargetCandidates(version, distinctIds);
    }

    private boolean scanTargetPage(CaseScopeVersion version) {
        ScopeCriteriaRequest criteria = readCriteria(version);
        List<String> categories = criteria.categories() == null ? List.of() : criteria.categories();
        boolean hasScanPredicate = !categories.isEmpty()
                || criteria.createdFrom() != null
                || criteria.createdTo() != null;
        if (!hasScanPredicate) {
            return true; // 只有显式对象标识（或关闭案件），无需条件扫描
        }
        boolean categoryOff = categories.isEmpty();
        long afterId = version.getMaterializeAfterId() == null
                ? 0L : version.getMaterializeAfterId();
        long maxId = version.getMaterializeMaxId() == null
                ? Long.MAX_VALUE : version.getMaterializeMaxId();
        List<com.chris64233.cc.legalhold.domain.DataObject> page = categoryOff
                ? objectRepository.scanScopePage(afterId, maxId,
                        criteria.createdFrom(), criteria.createdTo(),
                        PageRequest.of(0, Math.max(batchSize, 1)))
                : objectRepository.scanScopePageWithCategories(afterId, maxId,
                        categories, criteria.createdFrom(), criteria.createdTo(),
                        PageRequest.of(0, Math.max(batchSize, 1)));
        if (page.isEmpty()) {
            return true;
        }
        insertTargetCandidates(version, page.stream()
                .map(com.chris64233.cc.legalhold.domain.DataObject::getId).toList());
        long lastId = page.get(page.size() - 1).getId();
        version.setMaterializeAfterId(lastId);
        return page.size() < batchSize;
    }

    private boolean scanRemovedPage(CaseScopeVersion version) {
        // 以本案“当前实际生效保全集合”为基准：缩围和关案都适用，
        // 也兼容直接 apply 建立的保全。
        List<Long> activePage = membershipRepository.scanActiveObjectIds(
                version.getCaseNo(),
                version.getRemovedAfterId() == null ? 0L : version.getRemovedAfterId(),
                PageRequest.of(0, Math.max(batchSize, 1)));
        if (activePage.isEmpty()) {
            return true;
        }
        for (Long objectId : activePage) {
            if (!memberRepository.existsByVersionIdAndObjectId(version.getId(), objectId)) {
                memberRepository.save(new CaseScopeMember(version.getId(), version.getCaseNo(),
                        objectId, ScopeDeltaType.REMOVED));
                version.setRemovedCount(version.getRemovedCount() + 1);
            }
        }
        version.setRemovedAfterId(activePage.get(activePage.size() - 1));
        return activePage.size() < batchSize;
    }

    private void finishMaterialized(CaseScopeVersion version) {
        version.setMaterialized(true);
        version.setTargetCount(version.getAddedCount() + version.getRetainedCount());
    }

    /**
     * 将一批候选对象写入目标成员行：已在本案保全中的为 RETAINED，否则 ADDED。
     * 显式集合与条件集合重叠、批次重跑时靠存在性检查去重。
     */
    private void insertTargetCandidates(CaseScopeVersion version, List<Long> candidateIds) {
        for (List<Long> chunk : partition(candidateIds, batchSize)) {
            Map<Long, HoldMembership> existing = new HashMap<>();
            for (HoldMembership membership
                    : membershipRepository.findByCaseNoAndObjectIdInOrderByObjectId(
                            version.getCaseNo(), chunk)) {
                existing.put(membership.getObjectId(), membership);
            }
            for (Long objectId : chunk) {
                if (memberRepository.existsByVersionIdAndObjectId(version.getId(), objectId)) {
                    continue;
                }
                ScopeDeltaType delta = existing.containsKey(objectId)
                        ? ScopeDeltaType.RETAINED : ScopeDeltaType.ADDED;
                memberRepository.save(new CaseScopeMember(
                        version.getId(), version.getCaseNo(), objectId, delta));
                if (delta == ScopeDeltaType.ADDED) {
                    version.setAddedCount(version.getAddedCount() + 1);
                } else {
                    version.setRetainedCount(version.getRetainedCount() + 1);
                }
            }
        }
    }

    private CaseScopeVersion effectiveExpand(LegalCase legalCase, CaseScopeVersion version) {
        List<Long> addedIds = memberRepository
                .findByVersionIdAndDeltaType(version.getId(), ScopeDeltaType.ADDED).stream()
                .map(CaseScopeMember::getObjectId).sorted().toList();

        List<String> blockers = new ArrayList<>();
        List<com.chris64233.cc.legalhold.domain.DataObject> locked = new ArrayList<>();
        for (List<Long> chunk : partition(addedIds, batchSize)) {
            locked.addAll(objectRepository.findByIdsForUpdateOrderById(chunk));
        }
        for (com.chris64233.cc.legalhold.domain.DataObject dataObject : locked) {
            if (dataObject.getStatus() == ObjectStatus.DELETED) {
                blockers.add("对象已删除，无法纳入保全: " + dataObject.getBusinessKey());
            }
        }
        if (!blockers.isEmpty()) {
            return rejectVersion(legalCase, version, String.join("; ", blockers));
        }

        Instant now = clock.now();
        String eventNo = newEventNo();
        for (com.chris64233.cc.legalhold.domain.DataObject dataObject : locked) {
            if (membershipRepository.findByCaseNoAndObjectId(
                    version.getCaseNo(), dataObject.getId()).isPresent()) {
                continue;
            }
            membershipRepository.save(new HoldMembership(version.getCaseNo(), dataObject.getId(),
                    version.getVersionNo(), now));
            eventRepository.save(new HoldEvent(eventNo, version.getCaseNo(), HoldEventType.APPLY,
                    dataObject.getId(), version.getReason(), now,
                    version.getChangeNo(), version.getVersionNo()));
        }
        return markEffective(legalCase, version, now);
    }

    /**
     * 双人批准后的最终复核：对每个待释放对象重新核验其他案件保全、保留规则、
     * 当前生效版本及本案保全是否仍在。任一变化整批拒绝，不触碰任何成员。
     */
    private CaseScopeVersion recheckAndEffective(LegalCase legalCase, CaseScopeVersion version) {
        List<ConstraintSnapshot> snapshots = readSnapshots(version);
        Map<Long, ConstraintSnapshot> snapshotById = new HashMap<>();
        for (ConstraintSnapshot snapshot : snapshots) {
            snapshotById.put(snapshot.objectId(), snapshot);
        }

        List<Long> removedIds = snapshots.stream().map(ConstraintSnapshot::objectId)
                .sorted().toList();
        List<com.chris64233.cc.legalhold.domain.DataObject> locked = new ArrayList<>();
        for (List<Long> chunk : partition(removedIds, batchSize)) {
            locked.addAll(objectRepository.findByIdsForUpdateOrderById(chunk));
        }

        List<String> blockers = new ArrayList<>();
        for (com.chris64233.cc.legalhold.domain.DataObject dataObject : locked) {
            ConstraintSnapshot snapshot = snapshotById.get(dataObject.getId());
            if (snapshot == null) {
                blockers.add("复核数据缺失: " + dataObject.getBusinessKey());
                continue;
            }
            blockers.addAll(recheckOne(legalCase, version, dataObject, snapshot));
        }
        if (!blockers.isEmpty()) {
            return rejectVersion(legalCase, version,
                    "最终复核条件变化，整批拒绝: " + String.join("; ", blockers));
        }

        Instant now = clock.now();
        String eventNo = newEventNo();
        String releaseReason = (version.getChangeType()
                == com.chris64233.cc.legalhold.domain.ChangeType.CLOSE
                ? "关闭案件释放保全" : "范围缩小释放保全")
                + "，变更 " + version.getChangeNo() + " 经双人批准";
        for (com.chris64233.cc.legalhold.domain.DataObject dataObject : locked) {
            membershipRepository.findByCaseNoAndObjectId(version.getCaseNo(), dataObject.getId())
                    .ifPresent(membership -> {
                        membershipRepository.delete(membership);
                        eventRepository.save(new HoldEvent(eventNo, version.getCaseNo(),
                                HoldEventType.RELEASE, dataObject.getId(), releaseReason, now,
                                version.getChangeNo(), version.getVersionNo()));
                    });
        }
        if (version.getChangeType() == com.chris64233.cc.legalhold.domain.ChangeType.CLOSE) {
            legalCase.setClosed(true);
        }
        return markEffective(legalCase, version, now);
    }

    private List<String> recheckOne(LegalCase legalCase, CaseScopeVersion version,
                                    com.chris64233.cc.legalhold.domain.DataObject dataObject,
                                    ConstraintSnapshot snapshot) {
        List<String> blockers = new ArrayList<>();
        String key = dataObject.getBusinessKey();

        if (dataObject.getStatus() == ObjectStatus.DELETED) {
            blockers.add("对象已删除: " + key);
        }
        if (membershipRepository.findByCaseNoAndObjectId(
                version.getCaseNo(), dataObject.getId()).isEmpty()) {
            blockers.add("本案保全关系已不存在: " + key);
        }

        List<String> otherCases = membershipRepository
                .findByObjectIdOrderByCaseNo(dataObject.getId()).stream()
                .map(HoldMembership::getCaseNo)
                .filter(caseNo -> !caseNo.equals(version.getCaseNo()))
                .sorted().toList();
        if (!otherCases.equals(snapshot.otherCases())) {
            blockers.add("其他案件保全发生变化: " + key
                    + "，快照=" + snapshot.otherCases() + "，当前=" + otherCases);
        }

        RetentionRule rule = ruleRepository.findByCategoryForUpdate(dataObject.getCategory())
                .orElse(null);
        if (rule == null) {
            if (snapshot.minRetentionDays() != null) {
                blockers.add("保留规则被删除: " + key);
            }
        } else {
            Instant deadline = dataObject.getCreatedAt()
                    .plus(rule.getMinRetentionDays(), ChronoUnit.DAYS);
            boolean satisfied = !clock.now().isBefore(deadline);
            if (snapshot.minRetentionDays() == null
                    || rule.getMinRetentionDays() != snapshot.minRetentionDays()) {
                blockers.add("保留规则天数发生变化: " + key
                        + "，快照=" + snapshot.minRetentionDays()
                        + "，当前=" + rule.getMinRetentionDays());
            }
            if (satisfied != snapshot.retentionSatisfied()
                    || !deadline.toString().equals(snapshot.retentionDeadline())) {
                blockers.add("保留届满状态发生变化: " + key);
            }
        }

        if (legalCase.getCurrentVersionNo() != snapshot.currentVersionNo()) {
            blockers.add("案件当前生效版本发生变化: " + key
                    + "，快照=v" + snapshot.currentVersionNo()
                    + "，当前=v" + legalCase.getCurrentVersionNo());
        }
        return blockers;
    }

    private CaseScopeVersion markEffective(LegalCase legalCase, CaseScopeVersion version,
                                           Instant now) {
        versionRepository.findByCaseNoAndStatus(
                        version.getCaseNo(), ScopeVersionStatus.EFFECTIVE)
                .ifPresent(previous -> previous.setStatus(ScopeVersionStatus.SUPERSEDED));
        version.setStatus(ScopeVersionStatus.EFFECTIVE);
        version.setEffectiveAt(now);
        legalCase.setCurrentVersionNo(version.getVersionNo());
        legalCase.setActiveChangeNo(null);
        return version;
    }

    private CaseScopeVersion rejectVersion(LegalCase legalCase, CaseScopeVersion version,
                                           String reason) {
        version.setStatus(ScopeVersionStatus.REJECTED);
        version.setRejectedAt(clock.now());
        version.setRejectReason(truncate(reason, 1000));
        legalCase.setActiveChangeNo(null);
        return version;
    }

    private List<ConstraintSnapshot> buildSnapshots(CaseScopeVersion version) {
        List<Long> removedIds = memberRepository
                .findByVersionIdAndDeltaType(version.getId(), ScopeDeltaType.REMOVED).stream()
                .map(CaseScopeMember::getObjectId).sorted().toList();
        List<ConstraintSnapshot> snapshots = new ArrayList<>();
        for (List<Long> chunk : partition(removedIds, batchSize)) {
            for (com.chris64233.cc.legalhold.domain.DataObject dataObject
                    : objectRepository.findAllById(chunk)) {
                RetentionRule rule = ruleRepository.findByCategory(dataObject.getCategory())
                        .orElse(null);
                Integer days = rule == null ? null : rule.getMinRetentionDays();
                Instant deadline = rule == null ? null
                        : dataObject.getCreatedAt().plus(rule.getMinRetentionDays(), ChronoUnit.DAYS);
                boolean satisfied = deadline != null && !clock.now().isBefore(deadline);
                List<String> otherCases = membershipRepository
                        .findByObjectIdOrderByCaseNo(dataObject.getId()).stream()
                        .map(HoldMembership::getCaseNo)
                        .filter(caseNo -> !caseNo.equals(version.getCaseNo()))
                        .sorted().toList();
                snapshots.add(new ConstraintSnapshot(
                        dataObject.getId(), dataObject.getBusinessKey(), dataObject.getCategory(),
                        days, deadline == null ? null : deadline.toString(), satisfied,
                        otherCases, version.getBasedOnVersionNo(), true));
            }
        }
        snapshots.sort(java.util.Comparator.comparingLong(ConstraintSnapshot::objectId));
        return snapshots;
    }

    private LegalCase lockOrCreateCase(String caseNo) {
        LegalCase legalCase = caseRepository.findByCaseNoForUpdate(caseNo).orElse(null);
        if (legalCase != null) {
            return legalCase;
        }
        legalCase = new LegalCase(caseNo);
        return caseRepository.saveAndFlush(legalCase);
    }

    private LegalCase requireCase(String caseNo) {
        return caseRepository.findByCaseNoForUpdate(caseNo)
                .orElseThrow(() -> new NotFoundException("案件不存在: " + caseNo));
    }

    private CaseScopeVersion lockCaseAndVersion(Long versionId) {
        CaseScopeVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new NotFoundException("范围版本不存在: " + versionId));
        requireCase(version.getCaseNo());
        return versionRepository.findByIdForUpdate(versionId).orElseThrow();
    }

    private void validateCriteria(ScopeCriteriaRequest criteria) {
        if (criteria == null || criteria.isEmpty()) {
            throw new ConflictException("范围条件不能为空：至少提供对象标识、类别或创建时间条件");
        }
        if (criteria.createdFrom() != null && criteria.createdTo() != null
                && !criteria.createdFrom().isBefore(criteria.createdTo())) {
            throw new ConflictException("创建时间区间非法：createdFrom 必须早于 createdTo");
        }
    }

    private static <T> List<List<T>> partition(List<T> items, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            chunks.add(items.subList(i, Math.min(i + size, items.size())));
        }
        return chunks;
    }

    private ScopeCriteriaRequest readCriteria(CaseScopeVersion version) {
        try {
            return objectMapper.readValue(version.getCriteriaJson(), ScopeCriteriaRequest.class);
        } catch (Exception ex) {
            throw new IllegalStateException("范围条件快照解析失败: " + version.getChangeNo(), ex);
        }
    }

    private List<ConstraintSnapshot> readSnapshots(CaseScopeVersion version) {
        if (version.getConstraintSnapshotJson() == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(version.getConstraintSnapshotJson(),
                    objectMapper.getTypeFactory().constructCollectionType(
                            List.class, ConstraintSnapshot.class));
        } catch (Exception ex) {
            throw new IllegalStateException("约束快照解析失败: " + version.getChangeNo(), ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("序列化范围数据失败", ex);
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private String newEventNo() {
        return "EVT-" + UUID.randomUUID();
    }
}
