package com.chris64233.cc.legalhold.service.scope;

import com.chris64233.cc.legalhold.domain.CaseScope;
import com.chris64233.cc.legalhold.domain.CaseScopeVersion;
import com.chris64233.cc.legalhold.domain.CaseStatus;
import com.chris64233.cc.legalhold.domain.DataObject;
import com.chris64233.cc.legalhold.domain.HoldEvent;
import com.chris64233.cc.legalhold.domain.HoldEventType;
import com.chris64233.cc.legalhold.domain.HoldMembership;
import com.chris64233.cc.legalhold.domain.MembershipChange;
import com.chris64233.cc.legalhold.domain.ObjectStatus;
import com.chris64233.cc.legalhold.domain.RetentionRule;
import com.chris64233.cc.legalhold.domain.ScopeApproval;
import com.chris64233.cc.legalhold.domain.ScopeChangeType;
import com.chris64233.cc.legalhold.domain.ScopeVersionMember;
import com.chris64233.cc.legalhold.domain.ScopeVersionStatus;
import com.chris64233.cc.legalhold.repo.CaseScopeRepository;
import com.chris64233.cc.legalhold.repo.CaseScopeVersionRepository;
import com.chris64233.cc.legalhold.repo.DataObjectRepository;
import com.chris64233.cc.legalhold.repo.HoldEventRepository;
import com.chris64233.cc.legalhold.repo.HoldMembershipRepository;
import com.chris64233.cc.legalhold.repo.RetentionRuleRepository;
import com.chris64233.cc.legalhold.repo.ScopeApprovalRepository;
import com.chris64233.cc.legalhold.repo.ScopeVersionMemberRepository;
import com.chris64233.cc.legalhold.service.ConflictException;
import com.chris64233.cc.legalhold.service.NotFoundException;
import com.chris64233.cc.legalhold.time.DomainClock;
import com.chris64233.cc.legalhold.web.dto.ApprovalProgressView;
import com.chris64233.cc.legalhold.web.dto.ApprovalView;
import com.chris64233.cc.legalhold.web.dto.CaseScopeView;
import com.chris64233.cc.legalhold.web.dto.ObjectHoldView;
import com.chris64233.cc.legalhold.web.dto.ObjectHoldsView;
import com.chris64233.cc.legalhold.web.dto.ReleaseReasonView;
import com.chris64233.cc.legalhold.web.dto.ScopeApprovalRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeChangeRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeChangeView;
import com.chris64233.cc.legalhold.web.dto.ScopeDiffView;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 案件范围变更与批量释放审核的应用服务。
 *
 * <p>核心保证：
 * <ul>
 *   <li>范围版本不可变：扩大/缩小一律新建版本，旧版本保留为 SUPERSEDED；</li>
 *   <li>分批计算：每批独立事务推进游标，崩溃后从游标续算，计算中版本对外不可见；</li>
 *   <li>原子生效：成员投影只在版本生效的单个事务内切换，绝不出现部分保全/释放；</li>
 *   <li>双批准：缩围/关闭需两名不同且非申请人的人员批准，第二批准事务内重核验，
 *       约束变化整批拒绝；</li>
 *   <li>幂等：变更业务号唯一，审批 (changeNo, approver) 唯一，重复提交返回既有结果；</li>
 *   <li>并发安全：生效前对全部受影响对象按 ID 升序加悲观写锁，与删除确认串行化。</li>
 * </ul>
 */
@Service
public class CaseScopeService {

    private static final Logger log = LoggerFactory.getLogger(CaseScopeService.class);
    private static final int REQUIRED_APPROVALS = 2;
    private static final int MAX_REJECT_REASON_CHARS = 3900;

    private final CaseScopeRepository caseRepository;
    private final CaseScopeVersionRepository versionRepository;
    private final ScopeVersionMemberRepository memberRepository;
    private final ScopeApprovalRepository approvalRepository;
    private final DataObjectRepository objectRepository;
    private final HoldMembershipRepository membershipRepository;
    private final HoldEventRepository eventRepository;
    private final RetentionRuleRepository ruleRepository;
    private final ScopeBatchProcessor batchProcessor;
    private final ScopeJsonCodec codec;
    private final DomainClock clock;
    private final CaseScopeService self;
    private final int defaultBatchSize;

    public CaseScopeService(CaseScopeRepository caseRepository,
                            CaseScopeVersionRepository versionRepository,
                            ScopeVersionMemberRepository memberRepository,
                            ScopeApprovalRepository approvalRepository,
                            DataObjectRepository objectRepository,
                            HoldMembershipRepository membershipRepository,
                            HoldEventRepository eventRepository,
                            RetentionRuleRepository ruleRepository,
                            ScopeBatchProcessor batchProcessor,
                            ScopeJsonCodec codec,
                            DomainClock clock,
                            @Lazy CaseScopeService self,
                            @Value("${legalhold.scope.batch-size:200}") int defaultBatchSize) {
        this.caseRepository = caseRepository;
        this.versionRepository = versionRepository;
        this.memberRepository = memberRepository;
        this.approvalRepository = approvalRepository;
        this.objectRepository = objectRepository;
        this.membershipRepository = membershipRepository;
        this.eventRepository = eventRepository;
        this.ruleRepository = ruleRepository;
        this.batchProcessor = batchProcessor;
        this.codec = codec;
        this.clock = clock;
        this.self = self;
        this.defaultBatchSize = defaultBatchSize;
    }

    // ============================ 提交变更 ============================

    /**
     * 提交范围变更（变更业务号幂等）。新事务内分配版本号，随后分批计算；
     * 扩围/新建算完直接生效，缩围/关闭算完进入待双批准。重复业务号返回既有版本。
     */
    public ScopeChangeView submit(String caseNo, ScopeChangeRequest request) {
        CaseScopeVersion existing = versionRepository.findByChangeNo(request.changeNo()).orElse(null);
        if (existing != null) {
            if (!existing.getCaseNo().equals(caseNo)) {
                throw new ConflictException("变更业务号已用于其他案件: " + request.changeNo());
            }
            return resumeAndView(existing);
        }

        ScopeCriteria criteria = request.isCloseCase()
                ? ScopeCriteria.empty()
                : ScopeCriteria.from(request.criteria());
        // 显式对象标识必须已登记，避免静默漏保全。
        for (String key : criteria.businessKeyList()) {
            objectRepository.findIdByBusinessKey(key)
                    .orElseThrow(() -> new NotFoundException("对象不存在: " + key));
        }

        int batchSize = request.batchSize() == null || request.batchSize() <= 0
                ? defaultBatchSize : request.batchSize();
        Long versionId = self.allocateVersion(caseNo, request, codec.writeCriteria(criteria),
                batchSize);

        CaseScopeVersion allocated = versionRepository.findById(versionId).orElseThrow();
        runBatches(allocated, criteria,
                loadBaseIds(caseNo, allocated.getBaseVersionNo()));

        // 收尾事务提交后直接返回其中构建的视图，避免外层持久化上下文读到陈旧版本实体。
        ScopeChangeView finalized = self.finalizeComputation(versionId);
        return finalized != null ? finalized : self.getChange(caseNo, request.changeNo());
    }

    /**
     * 新事务分配版本号并落版本行。独立事务先提交，使后续 REQUIRES_NEW 分批事务可见该行，
     * 同时案件行悲观锁串行化同案件并发提交。
     */
    @Transactional
    public Long allocateVersion(String caseNo, ScopeChangeRequest request, String criteriaJson,
                                int batchSize) {
        CaseScope caseScope = caseRepository.findByCaseNoForUpdate(caseNo)
                .orElseGet(() -> caseRepository.save(new CaseScope(caseNo)));
        if (caseScope.getStatus() == CaseStatus.CLOSED) {
            throw new ConflictException("案件已关闭，不能变更范围: " + caseNo);
        }
        boolean inFlight = versionRepository
                .findByCaseNoAndStatusIn(caseNo, List.of(
                        ScopeVersionStatus.COMPUTING, ScopeVersionStatus.PENDING_APPROVAL))
                .stream().findAny().isPresent();
        if (inFlight) {
            throw new ConflictException("案件存在进行中的范围变更，请先完成或等待: " + caseNo);
        }

        Integer baseVersionNo = caseScope.getCurrentVersionNo() == 0
                ? null : caseScope.getCurrentVersionNo();
        int versionNo = caseScope.getCurrentVersionNo() + 1;
        CaseScopeVersion version = new CaseScopeVersion(
                caseNo, versionNo, request.changeNo(),
                request.isCloseCase() ? ScopeChangeType.SHRINK : ScopeChangeType.CREATE,
                criteriaJson, baseVersionNo, request.isCloseCase(),
                request.requestedBy(), request.reason(), batchSize, clock.now());
        versionRepository.save(version);
        return version.getId();
    }

    private void runBatches(CaseScopeVersion version, ScopeCriteria criteria, Set<Long> baseIds) {
        long guard = 0;
        long maxBatches = 1_000_000L;
        boolean done = false;
        while (!done) {
            done = batchProcessor.processBatch(version.getId(), criteria, baseIds);
            if (++guard > maxBatches) {
                throw new ConflictException("范围计算批次数超过上限，疑似游标未推进");
            }
        }
    }

    /**
     * 计算完成后的收尾事务：标记成员差异（ADDED/REMOVED/UNCHANGED）、固化释放约束快照、
     * 判定变更方向。扩围/新建直接原子生效；缩围/关闭转待批准。
     */
    @Transactional(noRollbackFor = ScopeRejectedException.class)
    public ScopeChangeView finalizeComputation(Long versionId) {
        // 先无锁读取以确定案件，再按“案件行 → 版本行”固定顺序加锁，与 approve 保持一致，
        // 避免跨事务反向加锁导致死锁。
        CaseScopeVersion unlocked = versionRepository.findById(versionId).orElseThrow();
        CaseScope caseScope = caseRepository.findByCaseNoForUpdate(unlocked.getCaseNo())
                .orElseThrow();
        CaseScopeVersion version = versionRepository.findByIdForUpdate(versionId).orElseThrow();
        if (version.getStatus() != ScopeVersionStatus.COMPUTING) {
            // 幂等重入：已收尾（待批准/已生效/被拒），直接返回当前视图。
            return toChangeView(version, caseScope);
        }
        if (!version.isComputeDone()) {
            return null;
        }

        Set<Long> baseIds = loadBaseIds(version.getCaseNo(), version.getBaseVersionNo());
        // 分批计算时成员行已按基线标记为 ADDED/UNCHANGED；收尾只需补齐 REMOVED 行。
        Set<Long> targetIds = memberRepository.findObjectIdsByVersionId(versionId).stream()
                .collect(Collectors.toSet());
        int added = (int) memberRepository.countByVersionIdAndMembershipChange(
                versionId, MembershipChange.ADDED);

        List<Long> removedIds = new ArrayList<>(baseIds);
        removedIds.removeIf(targetIds::contains);
        removedIds.sort(Long::compareTo);
        int removed = 0;
        for (Long removedId : removedIds) {
            DataObject dataObject = objectRepository.findById(removedId).orElseThrow();
            memberRepository.save(new ScopeVersionMember(
                    versionId, version.getCaseNo(), removedId,
                    dataObject.getBusinessKey(), MembershipChange.REMOVED));
            removed++;
        }

        ScopeChangeType type;
        if (version.getBaseVersionNo() == null) {
            type = ScopeChangeType.CREATE;
        } else if (removed > 0 || version.isCloseCase()) {
            type = ScopeChangeType.SHRINK;
        } else {
            type = ScopeChangeType.EXPAND;
        }
        version.setChangeType(type);
        version.setMemberCount(targetIds.size());
        version.setAddedCount(added);
        version.setRemovedCount(removed);

        if (type == ScopeChangeType.SHRINK) {
            // 固化待释放对象在提交时刻的约束快照，终确认逐项重算比对。
            List<ReleaseConstraintSnapshot> snapshots = buildSnapshots(version, removedIds);
            version.setReleaseSnapshotJson(codec.writeSnapshots(snapshots));
            version.setStatus(ScopeVersionStatus.PENDING_APPROVAL);
            return toChangeView(version, caseScope);
        }
        // 扩围/新建：无释放，直接原子生效。
        activate(version, caseScope);
        return toChangeView(version, caseScope);
    }

    private List<ReleaseConstraintSnapshot> buildSnapshots(CaseScopeVersion version,
                                                           List<Long> removedIds) {
        List<ReleaseConstraintSnapshot> snapshots = new ArrayList<>();
        for (Long objectId : removedIds) {
            DataObject dataObject = objectRepository.findById(objectId).orElseThrow();
            List<String> otherCases = membershipRepository
                    .findByObjectIdOrderByCaseNo(objectId).stream()
                    .map(HoldMembership::getCaseNo)
                    .filter(caseNo -> !caseNo.equals(version.getCaseNo()))
                    .sorted().toList();
            RetentionRule rule = ruleRepository.findByCategory(dataObject.getCategory())
                    .orElse(null);
            Instant deadline = rule == null ? null
                    : dataObject.getCreatedAt().plus(rule.getMinRetentionDays(), ChronoUnit.DAYS);
            snapshots.add(new ReleaseConstraintSnapshot(
                    objectId, dataObject.getBusinessKey(), otherCases, deadline,
                    rule == null, dataObject.getCategory()));
        }
        return snapshots;
    }

    // ============================ 双批准与生效 ============================

    /**
     * 提交一个批准。同一人重复批准幂等返回；申请人不能批准自己的变更。
     * 第二名不同人员批准时在同事务内重核验并原子生效，约束变化整批拒绝。
     */
    @Transactional(noRollbackFor = ScopeRejectedException.class)
    public ApprovalProgressView approve(String caseNo, String changeNo,
                                        ScopeApprovalRequest request) {
        requireVersion(caseNo, changeNo);
        CaseScope caseScope = caseRepository.findByCaseNoForUpdate(caseNo)
                .orElseThrow(() -> new NotFoundException("案件不存在: " + caseNo));
        // 取得案件锁后锁定重读版本，避免与并发审批/生效竞争读到陈旧状态。
        CaseScopeVersion version = versionRepository
                .findByChangeNo(changeNo).map(v ->
                        versionRepository.findByIdForUpdate(v.getId()).orElseThrow())
                .orElseThrow();

        if (version.getStatus() == ScopeVersionStatus.EFFECTIVE) {
            return buildProgress(version, caseScope);
        }
        if (version.getStatus() == ScopeVersionStatus.REJECTED) {
            throw new ScopeRejectedException("变更已被整批拒绝", rejectReasons(version));
        }
        if (version.getStatus() != ScopeVersionStatus.PENDING_APPROVAL) {
            throw new ConflictException("变更当前状态不允许批准: " + version.getStatus());
        }
        if (caseScope.getStatus() == CaseStatus.CLOSED) {
            throw new ConflictException("案件已关闭: " + caseNo);
        }
        if (request.approver().equals(version.getRequestedBy())) {
            throw new ConflictException("申请人不能批准自己提交的范围变更");
        }

        ScopeApproval existing = approvalRepository
                .findByChangeNoAndApprover(changeNo, request.approver()).orElse(null);
        if (existing == null) {
            approvalRepository.save(new ScopeApproval(changeNo, caseNo, request.approver(),
                    request.comment(), clock.now()));
        }

        long count = approvalRepository.countByChangeNo(changeNo);
        if (count >= REQUIRED_APPROVALS) {
            activate(version, caseScope);
        }
        return buildProgress(
                versionRepository.findById(version.getId()).orElseThrow(), caseScope);
    }

    /**
     * 版本生效：重核验待释放对象约束 → 锁定全部受影响对象 → 切换成员投影与事件。
     * 全部在调用方事务内完成，成员投影对外只表现为一次完整切换。
     */
    private void activate(CaseScopeVersion version, CaseScope caseScope) {
        if (version.getStatus() == ScopeVersionStatus.EFFECTIVE) {
            return;
        }
        if (caseScope.getCurrentVersionNo() != versionNumberOrZero(version.getBaseVersionNo())) {
            rejectVersion(version, List.of("案件当前生效版本已变化，基线版本不再有效，整批拒绝"));
            throw new ScopeRejectedException("案件当前版本已变化", rejectReasons(version));
        }

        List<ScopeVersionMember> addedMembers = memberRepository
                .findByVersionIdAndMembershipChangeOrderByObjectId(
                        version.getId(), MembershipChange.ADDED);
        List<ScopeVersionMember> removedMembers = memberRepository
                .findByVersionIdAndMembershipChangeOrderByObjectId(
                        version.getId(), MembershipChange.REMOVED);

        // 先对全部受影响对象按 ID 升序加悲观写锁。保全纳入与删除确认同样先锁对象行，
        // 因此锁内重核验读到的其他案件保全/保留规则是稳定的，杜绝“核验后条件被并发改写”。
        List<Long> affectedIds = new ArrayList<>();
        addedMembers.forEach(m -> affectedIds.add(m.getObjectId()));
        removedMembers.forEach(m -> affectedIds.add(m.getObjectId()));
        List<Long> lockedIds = affectedIds.stream().distinct().sorted().toList();
        if (!lockedIds.isEmpty()) {
            objectRepository.findByIdsForUpdateOrderById(lockedIds);
        }

        // 缩围/关闭：持锁终确认重新核验，任一约束变化整批拒绝。
        if (version.getChangeType() == ScopeChangeType.SHRINK) {
            RecheckReport report = recheck(version, removedMembers);
            if (!report.allMatch()) {
                List<String> reasons = new ArrayList<>();
                reasons.add("终确认时待释放对象约束发生变化，整批拒绝：");
                report.items().stream().filter(item -> !item.matched())
                        .forEach(item -> reasons.add(item.businessKey() + ": " + item.change()));
                rejectVersion(version, reasons);
                throw new ScopeRejectedException("终确认条件变化，整批拒绝", reasons);
            }
        }

        Instant now = clock.now();
        String eventNo = "EVT-" + UUID.randomUUID();
        int membershipChanges = 0;
        int droppedDeleted = 0;

        // 扩围：新增成员投影。计算窗口内对象已被删除的，不能纳入保全，也不写纳入事件；
        // 同时从本版本成员中剔除，保证后续版本差异不把已删对象算作当前范围成员。
        for (ScopeVersionMember member : addedMembers) {
            DataObject dataObject = objectRepository.findById(member.getObjectId()).orElseThrow();
            if (dataObject.getStatus() == ObjectStatus.DELETED) {
                memberRepository.delete(member);
                droppedDeleted++;
                continue;
            }
            boolean absent = membershipRepository
                    .findByCaseNoAndObjectId(version.getCaseNo(), member.getObjectId())
                    .isEmpty();
            boolean noEvent = !eventRepository
                    .existsByChangeNoAndObjectId(version.getChangeNo(), member.getObjectId());
            if (absent) {
                membershipRepository.save(
                        new HoldMembership(version.getCaseNo(), member.getObjectId()));
                membershipChanges++;
            }
            if (noEvent) {
                eventRepository.save(new HoldEvent(eventNo, version.getChangeNo(),
                        version.getCaseNo(), HoldEventType.APPLY, member.getObjectId(),
                        member.getBusinessKey(), version.getReason(), now));
            }
        }
        if (droppedDeleted > 0 && version.getMemberCount() != null) {
            version.setMemberCount(Math.max(0, version.getMemberCount() - droppedDeleted));
            version.setAddedCount(Math.max(0,
                    (version.getAddedCount() == null ? 0 : version.getAddedCount())
                            - droppedDeleted));
        }

        // 缩围/关闭：删除成员投影并写释放事件（幂等重放安全）。
        if (!removedMembers.isEmpty()) {
            List<Long> removedObjectIds = removedMembers.stream()
                    .map(ScopeVersionMember::getObjectId).toList();
            membershipRepository.deleteByCaseNoAndObjectIdIn(
                    version.getCaseNo(), removedObjectIds);
            for (ScopeVersionMember member : removedMembers) {
                if (!eventRepository.existsByChangeNoAndObjectId(
                        version.getChangeNo(), member.getObjectId())) {
                    eventRepository.save(new HoldEvent(eventNo, version.getChangeNo(),
                            version.getCaseNo(), HoldEventType.RELEASE, member.getObjectId(),
                            member.getBusinessKey(),
                            releaseReason(version), now));
                }
            }
        }

        if (version.getBaseVersionNo() != null) {
            versionRepository.findByCaseNoAndVersionNo(
                            version.getCaseNo(), version.getBaseVersionNo())
                    .ifPresent(base -> base.setStatus(ScopeVersionStatus.SUPERSEDED));
        }
        version.setStatus(ScopeVersionStatus.EFFECTIVE);
        version.setEffectiveAt(now);
        caseScope.setCurrentVersionNo(version.getVersionNo());
        if (version.isCloseCase()) {
            caseScope.setStatus(CaseStatus.CLOSED);
        }
        log.info("范围版本生效 case={} version={} type={} added={} removed={} membershipChanges={}",
                version.getCaseNo(), version.getVersionNo(), version.getChangeType(),
                addedMembers.size(), removedMembers.size(), membershipChanges);
    }

    private String releaseReason(CaseScopeVersion version) {
        return version.isCloseCase()
                ? "案件关闭，按批准版本整批释放：" + version.getReason()
                : "范围缩小，按批准版本整批释放：" + version.getReason();
    }

    private void rejectVersion(CaseScopeVersion version, List<String> reasons) {
        version.setStatus(ScopeVersionStatus.REJECTED);
        version.setEffectiveAt(null);
        String joined = String.join("\n", reasons);
        if (joined.length() > MAX_REJECT_REASON_CHARS) {
            joined = joined.substring(0, MAX_REJECT_REASON_CHARS);
        }
        version.setRejectReason(joined);
    }

    /**
     * 终确认重核验：比较提交快照与当前的其他案件保全、保留规则截止时间，
     * 并确认对象未被删除、仍在更新后的当前版本语义下。
     */
    private RecheckReport recheck(CaseScopeVersion version,
                                  List<ScopeVersionMember> removedMembers) {
        List<ReleaseConstraintSnapshot> snapshots =
                codec.readSnapshots(version.getReleaseSnapshotJson());
        Map<Long, ReleaseConstraintSnapshot> snapshotById = snapshots.stream()
                .collect(Collectors.toMap(ReleaseConstraintSnapshot::objectId, s -> s));

        List<RecheckReport.Item> items = new ArrayList<>();
        boolean allMatch = true;
        for (ScopeVersionMember member : removedMembers) {
            DataObject dataObject = objectRepository.findById(member.getObjectId()).orElseThrow();
            ReleaseConstraintSnapshot snapshot = snapshotById.get(member.getObjectId());
            List<String> currentOtherCases = membershipRepository
                    .findByObjectIdOrderByCaseNo(member.getObjectId()).stream()
                    .map(HoldMembership::getCaseNo)
                    .filter(caseNo -> !caseNo.equals(version.getCaseNo()))
                    .sorted().toList();

            RetentionRule rule = ruleRepository.findByCategory(dataObject.getCategory())
                    .orElse(null);
            String currentDeadline = rule == null ? null
                    : dataObject.getCreatedAt().plus(rule.getMinRetentionDays(), ChronoUnit.DAYS)
                            .toString();

            String change = null;
            if (dataObject.getStatus() == ObjectStatus.DELETED) {
                change = "对象已被删除";
            } else if (!membershipRepository.existsByCaseNoAndObjectId(
                    version.getCaseNo(), member.getObjectId())) {
                change = "本案对该对象的有效保全已不存在（审批窗口期被解除），当前版本不再约束该对象";
            } else if (!currentOtherCases.equals(snapshot.otherCases())) {
                change = "其他案件保全变化: 提交时=" + snapshot.otherCases()
                        + " 当前=" + currentOtherCases;
            } else if ((rule == null) != snapshot.retentionRuleMissing()) {
                change = "保留规则存在性变化: 提交时缺失=" + snapshot.retentionRuleMissing();
            } else {
                String snapshotDeadline = snapshot.retentionDeadline() == null
                        ? null : snapshot.retentionDeadline().toString();
                boolean deadlineChanged = java.util.Objects.equals(
                        snapshotDeadline, currentDeadline) == false;
                if (deadlineChanged) {
                    change = "保留规则变化导致截止时间变化: 提交时=" + snapshotDeadline
                            + " 当前=" + currentDeadline;
                }
            }
            if (change != null) {
                allMatch = false;
                items.add(RecheckReport.Item.changed(
                        dataObject, snapshot, currentOtherCases, currentDeadline, change));
            } else {
                items.add(RecheckReport.Item.unchanged(
                        dataObject, snapshot, currentOtherCases, currentDeadline));
            }
        }
        return allMatch ? RecheckReport.ok(items) : RecheckReport.failed(items);
    }

    // ============================ 重启恢复与续算 ============================

    /**
     * 服务启动时恢复所有计算中断的版本：续算 → 收尾 → 扩围直接生效。
     * 缩围版本停留在待批准，等待两名批准（其约束快照在 finalize 时固化）。
     */
    public void recoverInterruptedComputations() {
        List<CaseScopeVersion> computing =
                versionRepository.findByStatus(ScopeVersionStatus.COMPUTING);
        for (CaseScopeVersion version : computing) {
            try {
                ScopeCriteria criteria = codec.readCriteria(version.getCriteriaJson());
                Set<Long> baseIds = loadBaseIds(version.getCaseNo(), version.getBaseVersionNo());
                runBatches(version, criteria, baseIds);
                ScopeChangeView view = self.finalizeComputation(version.getId());
                log.info("恢复范围计算完成 case={} version={} status={}",
                        version.getCaseNo(), version.getVersionNo(),
                        view == null ? "仍在计算" : view.status());
            } catch (RuntimeException e) {
                log.error("恢复范围计算失败 case={} version={}, 保留计算中状态等待人工/重试",
                        version.getCaseNo(), version.getVersionNo(), e);
            }
        }
    }

    private ScopeChangeView resumeAndView(CaseScopeVersion version) {
        if (version.getStatus() == ScopeVersionStatus.COMPUTING) {
            ScopeCriteria criteria = codec.readCriteria(version.getCriteriaJson());
            Set<Long> baseIds = loadBaseIds(version.getCaseNo(), version.getBaseVersionNo());
            runBatches(version, criteria, baseIds);
            ScopeChangeView view = self.finalizeComputation(version.getId());
            if (view != null) {
                return view;
            }
        }
        CaseScope caseScope = caseRepository.findByCaseNo(version.getCaseNo()).orElseThrow();
        return toChangeView(
                versionRepository.findById(version.getId()).orElseThrow(), caseScope);
    }

    // ============================ 查询 ============================

    @Transactional(readOnly = true)
    public CaseScopeView getCase(String caseNo) {
        CaseScope caseScope = caseRepository.findByCaseNo(caseNo)
                .orElseThrow(() -> new NotFoundException("案件不存在: " + caseNo));
        List<ScopeChangeView> versions = versionRepository
                .findByCaseNoOrderByVersionNoAsc(caseNo).stream()
                .map(v -> toChangeView(v, caseScope)).toList();
        return new CaseScopeView(caseNo, caseScope.getStatus(),
                caseScope.getCurrentVersionNo() == 0 ? null : caseScope.getCurrentVersionNo(),
                versions);
    }

    /**
     * 查询单个变更；若版本计算中断（如服务重启），先续算到完成再返回，因此不是只读事务。
     */
    @Transactional
    public ScopeChangeView getChange(String caseNo, String changeNo) {
        CaseScopeVersion version = requireVersion(caseNo, changeNo);
        return resumeAndView(version);
    }

    @Transactional(readOnly = true)
    public ApprovalProgressView getApprovalProgress(String caseNo, String changeNo) {
        CaseScopeVersion version = requireVersion(caseNo, changeNo);
        CaseScope caseScope = caseRepository.findByCaseNo(caseNo).orElseThrow();
        return buildProgress(version, caseScope);
    }

    /**
     * 任意两个版本的成员差异（from 省略时与空版本比较）。
     */
    @Transactional(readOnly = true)
    public ScopeDiffView diff(String caseNo, Integer fromVersionNo, int toVersionNo) {
        CaseScopeVersion to = versionRepository.findByCaseNoAndVersionNo(caseNo, toVersionNo)
                .orElseThrow(() -> new NotFoundException("版本不存在: " + toVersionNo));
        Map<Long, String> fromKeys;
        int fromNumber;
        if (fromVersionNo == null) {
            fromKeys = Map.of();
            fromNumber = 0;
        } else {
            CaseScopeVersion from =
                    versionRepository.findByCaseNoAndVersionNo(caseNo, fromVersionNo)
                            .orElseThrow(() -> new NotFoundException("版本不存在: " + fromVersionNo));
            fromKeys = memberRepository.findByVersionIdOrderByObjectId(from.getId()).stream()
                    // REMOVED 行只描述差异，不是该版本实际成员
                    .filter(m -> m.getMembershipChange() != MembershipChange.REMOVED)
                    .collect(Collectors.toMap(ScopeVersionMember::getObjectId,
                            ScopeVersionMember::getBusinessKey, (a, b) -> a));
            fromNumber = fromVersionNo;
        }
        Map<Long, String> toKeys = memberRepository.findByVersionIdOrderByObjectId(to.getId())
                .stream()
                .filter(m -> m.getMembershipChange() != MembershipChange.REMOVED)
                .collect(Collectors.toMap(ScopeVersionMember::getObjectId,
                        ScopeVersionMember::getBusinessKey, (a, b) -> a));

        List<String> added = toKeys.entrySet().stream()
                .filter(e -> !fromKeys.containsKey(e.getKey()))
                .map(Map.Entry::getValue).sorted().toList();
        List<String> removed = fromKeys.entrySet().stream()
                .filter(e -> !toKeys.containsKey(e.getKey()))
                .map(Map.Entry::getValue).sorted().toList();
        List<String> unchanged = toKeys.entrySet().stream()
                .filter(e -> fromKeys.containsKey(e.getKey()))
                .map(Map.Entry::getValue).sorted().toList();
        return new ScopeDiffView(caseNo, fromVersionNo, toVersionNo,
                fromKeys.size(), toKeys.size(), added, removed, unchanged);
    }

    /**
     * 对象当前受到的全部有效保全（含手工保全与范围版本来源）。
     */
    @Transactional(readOnly = true)
    public ObjectHoldsView objectHolds(String businessKey) {
        DataObject dataObject = objectRepository.findByBusinessKey(businessKey)
                .orElseThrow(() -> new NotFoundException("对象不存在: " + businessKey));
        List<HoldMembership> memberships =
                membershipRepository.findByObjectIdOrderByCaseNo(dataObject.getId());
        List<ObjectHoldView> holds = new ArrayList<>();
        for (HoldMembership membership : memberships) {
            CaseScope caseScope = caseRepository.findByCaseNo(membership.getCaseNo()).orElse(null);
            Integer effectiveVersionNo = null;
            String effectiveChangeNo = null;
            boolean scopeManaged = caseScope != null && caseScope.getCurrentVersionNo() > 0;
            if (scopeManaged) {
                effectiveVersionNo = caseScope.getCurrentVersionNo();
                CaseScopeVersion effective = versionRepository
                        .findByCaseNoAndVersionNo(membership.getCaseNo(), effectiveVersionNo)
                        .orElse(null);
                if (effective != null) {
                    effectiveChangeNo = effective.getChangeNo();
                }
            }
            holds.add(new ObjectHoldView(
                    membership.getCaseNo(), scopeManaged, effectiveVersionNo,
                    effectiveChangeNo,
                    caseScope == null ? null : caseScope.getStatus().name()));
        }
        return new ObjectHoldsView(businessKey, dataObject.getCategory(), dataObject.getStatus(),
                dataObject.getCreatedAt(), holds.size(), holds);
    }

    /**
     * 对象的保全/释放事件流，RELEASE 事件给出释放原因与生效时间。
     */
    @Transactional(readOnly = true)
    public List<ReleaseReasonView> releaseReasons(String businessKey) {
        DataObject dataObject = objectRepository.findByBusinessKey(businessKey)
                .orElseThrow(() -> new NotFoundException("对象不存在: " + businessKey));
        return eventRepository.findByObjectIdOrderByEffectiveAtAsc(dataObject.getId()).stream()
                .map(e -> new ReleaseReasonView(e.getEventNo(), e.getChangeNo(), e.getCaseNo(),
                        e.getEventType(), e.getReason(), e.getEffectiveAt()))
                .toList();
    }

    // ============================ 视图与辅助 ============================

    private CaseScopeVersion requireVersion(String caseNo, String changeNo) {
        CaseScopeVersion version = versionRepository.findByChangeNo(changeNo)
                .orElseThrow(() -> new NotFoundException("范围变更不存在: " + changeNo));
        if (!version.getCaseNo().equals(caseNo)) {
            throw new NotFoundException("变更不属于该案件: " + changeNo);
        }
        return version;
    }

    private Set<Long> loadBaseIds(String caseNo, Integer baseVersionNo) {
        if (baseVersionNo == null) {
            return Set.of();
        }
        CaseScopeVersion base = versionRepository
                .findByCaseNoAndVersionNo(caseNo, baseVersionNo)
                .orElseThrow(() -> new NotFoundException(
                        "基线版本不存在: " + caseNo + "#" + baseVersionNo));
        return memberRepository.findObjectIdsByVersionId(base.getId()).stream()
                .collect(Collectors.toUnmodifiableSet());
    }

    private int versionNumberOrZero(Integer versionNo) {
        return versionNo == null ? 0 : versionNo;
    }

    private ScopeChangeView toChangeView(CaseScopeVersion version, CaseScope caseScope) {
        ScopeCriteria criteria = codec.readCriteria(version.getCriteriaJson());
        return new ScopeChangeView(
                version.getChangeNo(), version.getCaseNo(), version.getVersionNo(),
                version.getBaseVersionNo(), version.getChangeType(), version.getStatus(),
                new com.chris64233.cc.legalhold.web.dto.ScopeCriteriaRequest(
                        criteria.businessKeyList(),
                        criteria.categories().stream().sorted().toList(),
                        criteria.createdAfter(), criteria.createdBefore()),
                version.isCloseCase(), version.isComputeDone(), version.getCursorId(),
                version.getMemberCount(), version.getAddedCount(), version.getRemovedCount(),
                version.getStatus() == ScopeVersionStatus.EFFECTIVE,
                caseScope.getCurrentVersionNo() == 0 ? null : caseScope.getCurrentVersionNo(),
                version.getCreatedAt(), version.getEffectiveAt(),
                version.getRejectReason(), rejectReasons(version));
    }

    private ApprovalProgressView buildProgress(CaseScopeVersion version, CaseScope caseScope) {
        List<ScopeApproval> approvals =
                approvalRepository.findByChangeNoOrderByApprovedAtAsc(version.getChangeNo());
        long count = approvals.size();
        List<ApprovalView> views = approvals.stream()
                .map(a -> new ApprovalView(a.getChangeNo(), a.getCaseNo(), a.getApprover(),
                        a.getComment(), a.getApprovedAt()))
                .toList();
        return new ApprovalProgressView(
                version.getChangeNo(), version.getCaseNo(), version.getVersionNo(),
                version.getRequestedBy(), version.getStatus(), REQUIRED_APPROVALS, (int) count,
                views, count >= REQUIRED_APPROVALS,
                version.getStatus() == ScopeVersionStatus.EFFECTIVE,
                version.getStatus() == ScopeVersionStatus.REJECTED,
                version.getRejectReason(), rejectReasons(version), version.getEffectiveAt());
    }

    private List<String> rejectReasons(CaseScopeVersion version) {
        if (version.getRejectReason() == null || version.getRejectReason().isBlank()) {
            return List.of();
        }
        return List.of(version.getRejectReason().split("\n"));
    }
}
