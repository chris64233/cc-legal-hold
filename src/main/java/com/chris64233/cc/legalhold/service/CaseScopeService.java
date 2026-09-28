package com.chris64233.cc.legalhold.service;

import com.chris64233.cc.legalhold.domain.CaseScopeMember;
import com.chris64233.cc.legalhold.domain.CaseScopeVersion;
import com.chris64233.cc.legalhold.domain.DataObject;
import com.chris64233.cc.legalhold.domain.HoldEvent;
import com.chris64233.cc.legalhold.domain.HoldEventType;
import com.chris64233.cc.legalhold.domain.HoldMembership;
import com.chris64233.cc.legalhold.domain.LegalCase;
import com.chris64233.cc.legalhold.domain.ScopeApproval;
import com.chris64233.cc.legalhold.domain.ScopeDeltaType;
import com.chris64233.cc.legalhold.domain.ScopeVersionStatus;
import com.chris64233.cc.legalhold.repo.CaseScopeMemberRepository;
import com.chris64233.cc.legalhold.repo.CaseScopeVersionRepository;
import com.chris64233.cc.legalhold.repo.DataObjectRepository;
import com.chris64233.cc.legalhold.repo.HoldEventRepository;
import com.chris64233.cc.legalhold.repo.HoldMembershipRepository;
import com.chris64233.cc.legalhold.repo.LegalCaseRepository;
import com.chris64233.cc.legalhold.repo.ScopeApprovalRepository;
import com.chris64233.cc.legalhold.web.dto.ApprovalEventView;
import com.chris64233.cc.legalhold.web.dto.ApprovalProgressView;
import com.chris64233.cc.legalhold.web.dto.ApprovalRequest;
import com.chris64233.cc.legalhold.web.dto.CloseCaseRequest;
import com.chris64233.cc.legalhold.web.dto.ObjectHoldView;
import com.chris64233.cc.legalhold.web.dto.ReleaseReasonView;
import com.chris64233.cc.legalhold.web.dto.ScopeChangeRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeCriteriaRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeDiffView;
import com.chris64233.cc.legalhold.web.dto.ScopeVersionView;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 案件范围变更编排：对外提供“一个完整生效版本”的语义，内部把大版本计算拆成
 * 多个可独立提交、可安全重试的批次。本类不开长事务——每个批次/投票都是
 * {@link ScopeChangeTxRunner} 中的独立事务，失败或重启后用同一 changeNo 续跑即可。
 */
@Service
public class CaseScopeService {

    static final int REQUIRED_APPROVALS = 2;

    private final ScopeChangeTxRunner runner;
    private final CaseScopeVersionRepository versionRepository;
    private final CaseScopeMemberRepository memberRepository;
    private final DataObjectRepository objectRepository;
    private final HoldMembershipRepository membershipRepository;
    private final HoldEventRepository eventRepository;
    private final ScopeApprovalRepository approvalRepository;
    private final LegalCaseRepository caseRepository;
    private final ObjectMapper objectMapper;
    private final BatchProperties batchProperties;

    public CaseScopeService(ScopeChangeTxRunner runner,
                            CaseScopeVersionRepository versionRepository,
                            CaseScopeMemberRepository memberRepository,
                            DataObjectRepository objectRepository,
                            HoldMembershipRepository membershipRepository,
                            HoldEventRepository eventRepository,
                            ScopeApprovalRepository approvalRepository,
                            LegalCaseRepository caseRepository,
                            ObjectMapper objectMapper,
                            BatchProperties batchProperties) {
        this.runner = runner;
        this.versionRepository = versionRepository;
        this.memberRepository = memberRepository;
        this.objectRepository = objectRepository;
        this.membershipRepository = membershipRepository;
        this.eventRepository = eventRepository;
        this.approvalRepository = approvalRepository;
        this.caseRepository = caseRepository;
        this.objectMapper = objectMapper;
        this.batchProperties = batchProperties;
    }

    /**
     * 提交范围变更（扩围/缩围由物化后的差异自动判定）。changeNo 幂等：
     * 重复提交会续跑未完成的同一版本，绝不会产生第二个版本或重复保全/释放。
     */
    public ScopeVersionView submitChange(ScopeChangeRequest request) {
        ScopeChangeTxRunner.InitResult init = runner.initChange(request);
        boolean replay = !init.newlyCreated();
        ensureSameCase(init.version(), request.caseNo());
        CaseScopeVersion version = driveToFinish(init.version().getId());
        return toView(version, replay);
    }

    /**
     * 关闭案件：目标范围视为空集，物化出全部 REMOVED 后转双人审批。
     */
    public ScopeVersionView closeCase(CloseCaseRequest request) {
        ScopeChangeTxRunner.InitResult init = runner.initClose(request);
        boolean replay = !init.newlyCreated();
        ensureSameCase(init.version(), request.caseNo());
        CaseScopeVersion version = driveToFinish(init.version().getId());
        return toView(version, replay);
    }

    /**
     * 续跑未完成的物化（大批量场景下分批执行/服务重启后恢复）。
     */
    public ScopeVersionView resume(String changeNo) {
        CaseScopeVersion version = requireByChangeNo(changeNo);
        version = driveToFinish(version.getId());
        return toView(version, true);
    }

    /**
     * 记录一次审批事件。事件本身幂等；两名不同人员 APPROVE 触发最终复核与生效，
     * 条件变化则整批拒绝。
     */
    public ApprovalProgressView approve(ApprovalRequest request) {
        com.chris64233.cc.legalhold.domain.ApprovalVoteType voteType;
        try {
            voteType = com.chris64233.cc.legalhold.domain.ApprovalVoteType
                    .valueOf(request.vote().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ConflictException("投票类型非法，只支持 APPROVE/REJECT: " + request.vote());
        }
        CaseScopeVersion version = runner.vote(
                request.changeNo(), request.reviewer(), voteType, request.comment());
        return progress(version.getChangeNo());
    }

    // ---------------- 查询 ----------------

    public ScopeVersionView getVersion(String caseNo, int versionNo) {
        return toView(versionRepository
                .findByCaseNoAndVersionNo(caseNo, versionNo)
                .orElseThrow(() -> new NotFoundException(
                        "范围版本不存在: " + caseNo + " v" + versionNo)), false);
    }

    public ScopeVersionView getCurrentVersion(String caseNo) {
        return toView(versionRepository
                .findByCaseNoAndStatus(caseNo, ScopeVersionStatus.EFFECTIVE)
                .orElseThrow(() -> new NotFoundException("案件暂无生效版本: " + caseNo)), false);
    }

    public List<ScopeVersionView> listVersions(String caseNo) {
        return versionRepository.findByCaseNoOrderByVersionNoAsc(caseNo).stream()
                .map(version -> toView(version, false))
                .toList();
    }

    /**
     * 案件版本差异。toVersionNo 缺省为最新版本，fromVersionNo 缺省为其基准版本。
     */
    public ScopeDiffView diff(String caseNo, Integer fromVersionNo, Integer toVersionNo) {
        List<CaseScopeVersion> versions =
                versionRepository.findByCaseNoOrderByVersionNoAsc(caseNo);
        if (versions.isEmpty()) {
            throw new NotFoundException("案件没有任何范围版本: " + caseNo);
        }
        CaseScopeVersion to = toVersionNo == null
                ? versions.get(versions.size() - 1)
                : versions.stream().filter(v -> v.getVersionNo() == toVersionNo).findFirst()
                        .orElseThrow(() -> new NotFoundException("目标版本不存在: v" + toVersionNo));
        CaseScopeVersion from = fromVersionNo == null
                ? versions.stream().filter(v -> v.getVersionNo() == to.getBasedOnVersionNo())
                        .findFirst().orElse(null)
                : versions.stream().filter(v -> v.getVersionNo() == fromVersionNo).findFirst()
                        .orElseThrow(() -> new NotFoundException("基准版本不存在: v" + fromVersionNo));

        Set<Long> fromIds = from == null ? Set.of() : targetIds(from.getId());
        Set<Long> toIds = targetIds(to.getId());

        Set<Long> added = new HashSet<>(toIds);
        added.removeAll(fromIds);
        Set<Long> removed = new HashSet<>(fromIds);
        removed.removeAll(toIds);
        Set<Long> retained = new HashSet<>(toIds);
        retained.retainAll(fromIds);

        return new ScopeDiffView(caseNo,
                from == null ? 0 : from.getVersionNo(), to.getVersionNo(),
                keysOf(added), keysOf(removed), keysOf(retained));
    }

    /**
     * 对象当前受到的全部保全（跨案件），含建立该保全的范围版本与案件是否已关闭。
     */
    public List<ObjectHoldView> objectHolds(String businessKey) {
        DataObject dataObject = objectRepository.findByBusinessKey(businessKey)
                .orElseThrow(() -> new NotFoundException("对象不存在: " + businessKey));
        Map<String, LegalCase> casesByNo = caseRepository.findAll().stream()
                .collect(Collectors.toMap(LegalCase::getCaseNo, legalCase -> legalCase));
        return membershipRepository.findByObjectIdOrderByCaseNo(dataObject.getId()).stream()
                .sorted(Comparator.comparing(HoldMembership::getCaseNo))
                .map(membership -> new ObjectHoldView(
                        membership.getCaseNo(),
                        membership.getEffectiveVersionNo(),
                        membership.getEstablishedAt(),
                        casesByNo.getOrDefault(membership.getCaseNo(),
                                new LegalCase(membership.getCaseNo())).isClosed()))
                .toList();
    }

    /**
     * 对象被各案件释放的原因（RELEASE 事件，最近在前）。
     */
    public List<ReleaseReasonView> releaseReasons(String businessKey) {
        DataObject dataObject = objectRepository.findByBusinessKey(businessKey)
                .orElseThrow(() -> new NotFoundException("对象不存在: " + businessKey));
        return eventRepository
                .findByObjectIdAndEventTypeOrderByEffectiveAtDesc(
                        dataObject.getId(), HoldEventType.RELEASE).stream()
                .map(this::toReleaseView)
                .toList();
    }

    public ApprovalProgressView progress(String changeNo) {
        CaseScopeVersion version = requireByChangeNo(changeNo);
        List<ScopeApproval> approves = approvalRepository
                .findByChangeNoAndVoteTypeOrderByVotedAtAsc(
                        changeNo, com.chris64233.cc.legalhold.domain.ApprovalVoteType.APPROVE);
        List<ApprovalEventView> events = approvalRepository
                .findByChangeNoOrderByVotedAtAsc(changeNo).stream()
                .map(a -> new ApprovalEventView(
                        a.getReviewer(), a.getVoteType(), a.getComment(), a.getVotedAt()))
                .toList();
        List<String> approvers = approves.stream()
                .map(ScopeApproval::getReviewer).distinct().toList();
        return new ApprovalProgressView(
                version.getCaseNo(), changeNo, version.getVersionNo(), version.getStatus(),
                REQUIRED_APPROVALS, approvers.size(), approvers, events,
                version.getRejectReason());
    }

    // ---------------- 内部 ----------------

    /**
     * 把版本物化推进到完成，然后定稿。每次循环调用一个独立事务批次；
     * 单调用批次数有上限，未完成可由 resume/重复提交继续。
     */
    private CaseScopeVersion driveToFinish(Long versionId) {
        CaseScopeVersion version = versionRepository.findById(versionId).orElseThrow();
        if (version.getStatus() != ScopeVersionStatus.PENDING_MATERIALIZE) {
            return version;
        }
        int budget = Math.max(1, batchProperties.getMaxBatchesPerCall());
        boolean done = false;
        while (budget-- > 0) {
            done = runner.materializeBatch(versionId);
            if (done) {
                break;
            }
        }
        if (done) {
            return runner.finalizeIfNeeded(versionId);
        }
        return versionRepository.findById(versionId).orElseThrow();
    }

    private Set<Long> targetIds(Long versionId) {
        return new HashSet<>(memberRepository.findTargetObjectIds(versionId));
    }

    private List<String> keysOf(Set<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (DataObject dataObject : objectRepository.findAllById(ids)) {
            keys.add(dataObject.getBusinessKey());
        }
        keys.sort(String::compareTo);
        return keys;
    }

    private ReleaseReasonView toReleaseView(HoldEvent event) {
        return new ReleaseReasonView(event.getCaseNo(), event.getEventNo(),
                event.getChangeNo(), event.getVersionNo(),
                event.getReason(), event.getEffectiveAt());
    }

    private CaseScopeVersion requireByChangeNo(String changeNo) {
        return versionRepository.findByChangeNo(changeNo)
                .orElseThrow(() -> new NotFoundException("范围变更不存在: " + changeNo));
    }

    private void ensureSameCase(CaseScopeVersion version, String caseNo) {
        if (!version.getCaseNo().equals(caseNo)) {
            throw new ConflictException("变更业务号已属于其他案件: "
                    + version.getChangeNo() + " -> " + version.getCaseNo());
        }
    }

    private ScopeVersionView toView(CaseScopeVersion version, boolean idempotentReplay) {
        return new ScopeVersionView(
                version.getCaseNo(),
                version.getVersionNo(),
                version.getChangeNo(),
                version.getChangeType(),
                version.getStatus(),
                version.getBasedOnVersionNo(),
                readCriteria(version),
                version.getReason(),
                version.getCreatedBy(),
                version.getCreatedAt(),
                version.getEffectiveAt(),
                version.getAddedCount(),
                version.getRetainedCount(),
                version.getRemovedCount(),
                version.getRejectReason(),
                idempotentReplay);
    }

    private ScopeCriteriaRequest readCriteria(CaseScopeVersion version) {
        try {
            return objectMapper.readValue(version.getCriteriaJson(), ScopeCriteriaRequest.class);
        } catch (Exception ex) {
            throw new IllegalStateException("范围条件快照解析失败: " + version.getChangeNo(), ex);
        }
    }
}
