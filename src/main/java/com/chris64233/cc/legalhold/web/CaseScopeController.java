package com.chris64233.cc.legalhold.web;

import com.chris64233.cc.legalhold.service.scope.CaseScopeService;
import com.chris64233.cc.legalhold.web.dto.ApprovalProgressView;
import com.chris64233.cc.legalhold.web.dto.CaseScopeView;
import com.chris64233.cc.legalhold.web.dto.ObjectHoldsView;
import com.chris64233.cc.legalhold.web.dto.ReleaseReasonView;
import com.chris64233.cc.legalhold.web.dto.ScopeApprovalRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeChangeRequest;
import com.chris64233.cc.legalhold.web.dto.ScopeChangeView;
import com.chris64233.cc.legalhold.web.dto.ScopeDiffView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 案件范围变更、双批准与查询接口。
 */
@RestController
@RequestMapping("/api/cases")
@Validated
public class CaseScopeController {

    private final CaseScopeService caseScopeService;

    public CaseScopeController(CaseScopeService caseScopeService) {
        this.caseScopeService = caseScopeService;
    }

    /** 提交范围变更（扩大/缩小/关闭），按条件生成不可变版本；同 changeNo 幂等。 */
    @PostMapping("/{caseNo}/scope-changes")
    public ScopeChangeView submit(@PathVariable @NotBlank String caseNo,
                                  @Valid @RequestBody ScopeChangeRequest request) {
        return caseScopeService.submit(caseNo, request);
    }

    /** 查询单个变更（计算中版本会先续算到完成）。 */
    @GetMapping("/{caseNo}/scope-changes/{changeNo}")
    public ScopeChangeView getChange(@PathVariable String caseNo,
                                     @PathVariable String changeNo) {
        return caseScopeService.getChange(caseNo, changeNo);
    }

    /** 审批进度（已批准人、所需人数、是否生效/拒绝）。 */
    @GetMapping("/{caseNo}/scope-changes/{changeNo}/approvals")
    public ApprovalProgressView approvals(@PathVariable String caseNo,
                                          @PathVariable String changeNo) {
        return caseScopeService.getApprovalProgress(caseNo, changeNo);
    }

    /** 提交一个批准；第二名不同人员批准触发终确认重核验与生效。 */
    @PostMapping("/{caseNo}/scope-changes/{changeNo}/approvals")
    public ApprovalProgressView approve(@PathVariable String caseNo,
                                        @PathVariable String changeNo,
                                        @Valid @RequestBody ScopeApprovalRequest request) {
        return caseScopeService.approve(caseNo, changeNo, request);
    }

    /** 案件状态与全部版本历史。 */
    @GetMapping("/{caseNo}")
    public CaseScopeView getCase(@PathVariable String caseNo) {
        return caseScopeService.getCase(caseNo);
    }

    /** 任意两版本差异；from 省略时与空版本比较。 */
    @GetMapping("/{caseNo}/versions/{toVersionNo}/diff")
    public ScopeDiffView diff(@PathVariable String caseNo,
                              @PathVariable int toVersionNo,
                              @RequestParam(required = false) Integer from) {
        return caseScopeService.diff(caseNo, from, toVersionNo);
    }

    /** 对象当前受到的全部保全及来源版本。 */
    @GetMapping("/holds/objects/{businessKey}")
    public ObjectHoldsView objectHolds(@PathVariable String businessKey) {
        return caseScopeService.objectHolds(businessKey);
    }

    /** 对象的保全/释放事件，RELEASE 给出释放原因。 */
    @GetMapping("/holds/objects/{businessKey}/release-reasons")
    public List<ReleaseReasonView> releaseReasons(@PathVariable String businessKey) {
        return caseScopeService.releaseReasons(businessKey);
    }
}
