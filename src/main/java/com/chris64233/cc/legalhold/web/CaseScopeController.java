package com.chris64233.cc.legalhold.web;

import com.chris64233.cc.legalhold.service.CaseScopeService;
import com.chris64233.cc.legalhold.web.dto.ApprovalProgressView;
import com.chris64233.cc.legalhold.web.dto.ApprovalRequest;
import com.chris64233.cc.legalhold.web.dto.CloseCaseRequest;
import com.chris64233.cc.legalhold.web.dto.ObjectHoldView;
import com.chris64233.cc.legalhold.web.dto.ReleaseReasonView;
import com.chris64233.cc.legalhold.web.dto.ScopeChangeRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeDiffView;
import com.chris64233.cc.legalhold.web.dto.ScopeVersionView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/case-scopes")
public class CaseScopeController {

    private final CaseScopeService caseScopeService;

    public CaseScopeController(CaseScopeService caseScopeService) {
        this.caseScopeService = caseScopeService;
    }

    /** 提交范围变更（扩围/缩围自动判定）。changeNo 幂等。 */
    @PostMapping("/changes")
    @ResponseStatus(HttpStatus.CREATED)
    public ScopeVersionView submitChange(@Valid @RequestBody ScopeChangeRequest request) {
        return caseScopeService.submitChange(request);
    }

    /** 关闭案件：整案释放，双人审批。 */
    @PostMapping("/close")
    @ResponseStatus(HttpStatus.CREATED)
    public ScopeVersionView close(@Valid @RequestBody CloseCaseRequest request) {
        return caseScopeService.closeCase(request);
    }

    /** 续跑大批量物化（分批执行/重启恢复）。 */
    @PostMapping("/changes/{changeNo}/resume")
    public ScopeVersionView resume(@PathVariable String changeNo) {
        return caseScopeService.resume(changeNo);
    }

    /** 记录审批事件（APPROVE/REJECT）。事件幂等。 */
    @PostMapping("/approvals")
    public ApprovalProgressView approve(@Valid @RequestBody ApprovalRequest request) {
        return caseScopeService.approve(request);
    }

    @GetMapping("/{caseNo}/versions")
    public List<ScopeVersionView> listVersions(@PathVariable String caseNo) {
        return caseScopeService.listVersions(caseNo);
    }

    @GetMapping("/{caseNo}/versions/current")
    public ScopeVersionView currentVersion(@PathVariable String caseNo) {
        return caseScopeService.getCurrentVersion(caseNo);
    }

    @GetMapping("/{caseNo}/versions/{versionNo}")
    public ScopeVersionView version(@PathVariable String caseNo,
                                    @PathVariable int versionNo) {
        return caseScopeService.getVersion(caseNo, versionNo);
    }

    /** 版本差异：可传 from/to，缺省为相邻版本。 */
    @GetMapping("/{caseNo}/diff")
    public ScopeDiffView diff(@PathVariable String caseNo,
                              @RequestParam(required = false) Integer from,
                              @RequestParam(required = false) Integer to) {
        return caseScopeService.diff(caseNo, from, to);
    }

    @GetMapping("/changes/{changeNo}/approval-progress")
    public ApprovalProgressView approvalProgress(@PathVariable String changeNo) {
        return caseScopeService.progress(changeNo);
    }

    /** 对象当前受到的全部保全。 */
    @GetMapping("/objects/{businessKey}/holds")
    public List<ObjectHoldView> objectHolds(@PathVariable String businessKey) {
        return caseScopeService.objectHolds(businessKey);
    }

    /** 对象被各案件释放的原因。 */
    @GetMapping("/objects/{businessKey}/release-reasons")
    public List<ReleaseReasonView> releaseReasons(@PathVariable String businessKey) {
        return caseScopeService.releaseReasons(businessKey);
    }
}
