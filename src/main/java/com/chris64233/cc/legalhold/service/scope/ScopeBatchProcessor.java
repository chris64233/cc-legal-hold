package com.chris64233.cc.legalhold.service.scope;

import com.chris64233.cc.legalhold.domain.CaseScopeVersion;
import com.chris64233.cc.legalhold.domain.DataObject;
import com.chris64233.cc.legalhold.domain.MembershipChange;
import com.chris64233.cc.legalhold.domain.ScopeVersionMember;
import com.chris64233.cc.legalhold.domain.ScopeVersionStatus;
import com.chris64233.cc.legalhold.repo.CaseScopeVersionRepository;
import com.chris64233.cc.legalhold.repo.DataObjectRepository;
import com.chris64233.cc.legalhold.repo.ScopeVersionMemberRepository;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 范围分批计算器。每一批在独立事务内：锁版本行 → 读游标 → 扫描一个 ID 窗口 →
 * 写入命中成员 → 推进游标（或标记完成）。
 *
 * <p>游标与本批成员在同一事务提交：崩溃后从已提交游标继续，已提交批次不会重复写入；
 * 版本行悲观写锁保证同一版本不会有两个续算器并发推进。计算期间版本停留在
 * {@link ScopeVersionStatus#COMPUTING}，外部只能看到上一生效版本。
 */
@Component
public class ScopeBatchProcessor {

    private final CaseScopeVersionRepository versionRepository;
    private final ScopeVersionMemberRepository memberRepository;
    private final DataObjectRepository objectRepository;

    public ScopeBatchProcessor(CaseScopeVersionRepository versionRepository,
                               ScopeVersionMemberRepository memberRepository,
                               DataObjectRepository objectRepository) {
        this.versionRepository = versionRepository;
        this.memberRepository = memberRepository;
        this.objectRepository = objectRepository;
    }

    /**
     * 推进一批。
     *
     * @param baseIds 基线生效版本的对象 ID 集合，用于差异标记
     * @return true 表示全部批次已计算完成
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processBatch(Long versionId, ScopeCriteria criteria, Set<Long> baseIds) {
        CaseScopeVersion version = versionRepository.findByIdForUpdate(versionId).orElseThrow();
        if (version.isComputeDone() || version.getStatus() != ScopeVersionStatus.COMPUTING) {
            return true;
        }

        long cursor = version.getCursorId() == null ? 0L : version.getCursorId();
        List<DataObject> batch = objectRepository.findBatchAfterId(
                cursor, PageRequest.of(0, version.getBatchSize()));

        long maxId = cursor;
        for (DataObject dataObject : batch) {
            maxId = Math.max(maxId, dataObject.getId());
            if (!criteria.matches(dataObject)) {
                continue;
            }
            if (memberRepository.existsByVersionIdAndObjectId(versionId, dataObject.getId())) {
                continue;
            }
            MembershipChange change = baseIds.contains(dataObject.getId())
                    ? MembershipChange.UNCHANGED
                    : MembershipChange.ADDED;
            memberRepository.save(new ScopeVersionMember(
                    versionId, version.getCaseNo(), dataObject.getId(),
                    dataObject.getBusinessKey(), change));
        }

        if (batch.size() < version.getBatchSize()) {
            version.setComputeDone(true);
        } else {
            version.setCursorId(maxId);
        }
        return version.isComputeDone();
    }
}
