package com.chris64233.cc.legalhold.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chris64233.cc.legalhold.CcLegalHoldApplication;
import com.chris64233.cc.legalhold.repo.AuditEventRepository;
import com.chris64233.cc.legalhold.repo.CaseScopeRepository;
import com.chris64233.cc.legalhold.repo.CaseScopeVersionRepository;
import com.chris64233.cc.legalhold.repo.DataObjectRepository;
import com.chris64233.cc.legalhold.repo.DeletionTokenRepository;
import com.chris64233.cc.legalhold.repo.HoldEventRepository;
import com.chris64233.cc.legalhold.repo.HoldMembershipRepository;
import com.chris64233.cc.legalhold.repo.ScopeApprovalRepository;
import com.chris64233.cc.legalhold.repo.ScopeVersionMemberRepository;
import com.chris64233.cc.legalhold.support.MutableTestClock;
import com.chris64233.cc.legalhold.support.TestClockConfig;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 案件范围变更端到端 HTTP 流程：提交扩围、缩围双批准、版本差异、对象全部保全、
 * 释放原因、幂等与整批拒绝。
 */
@SpringBootTest(classes = {CcLegalHoldApplication.class, TestClockConfig.class})
@AutoConfigureMockMvc
class CaseScopeControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MutableTestClock clock;
    @Autowired
    private DataObjectRepository objectRepository;
    @Autowired
    private HoldMembershipRepository membershipRepository;
    @Autowired
    private HoldEventRepository eventRepository;
    @Autowired
    private DeletionTokenRepository tokenRepository;
    @Autowired
    private AuditEventRepository auditRepository;
    @Autowired
    private CaseScopeRepository caseRepository;
    @Autowired
    private CaseScopeVersionRepository versionRepository;
    @Autowired
    private ScopeVersionMemberRepository memberRepository;
    @Autowired
    private ScopeApprovalRepository approvalRepository;

    @AfterEach
    void cleanUp() {
        approvalRepository.deleteAll();
        memberRepository.deleteAll();
        versionRepository.deleteAll();
        caseRepository.deleteAll();
        eventRepository.deleteAll();
        membershipRepository.deleteAll();
        tokenRepository.deleteAll();
        auditRepository.deleteAll();
        objectRepository.deleteAll();
    }

    @BeforeEach
    void setUp() {
        clock.setTime(Instant.parse("2026-01-01T00:00:00Z"));
    }

    private void rule(String category, int days) throws Exception {
        mockMvc.perform(post("/api/objects/retention-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"" + category + "\",\"minRetentionDays\":"
                                + days + "}"))
                .andExpect(status().isOk());
    }

    private void register(String key, String category) throws Exception {
        mockMvc.perform(post("/api/objects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessKey\":\"" + key + "\",\"category\":\""
                                + category + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void fullScopeLifecycleOverHttp() throws Exception {
        rule("DOC", 30);
        register("http-1", "DOC");
        register("http-2", "DOC");
        register("http-3", "DOC");

        // 初始版本 v1：含 http-1,http-2，扩围直接生效
        mockMvc.perform(post("/api/cases/CASE-H/scope-changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changeNo":"CHG-1","requestedBy":"alice","reason":"立案",
                                 "batchSize":2,
                                 "criteria":{"businessKeys":["http-1","http-2"],
                                 "categories":[],"createdAfter":null,"createdBefore":null}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.memberCount").value(2));

        // 重复提交同一变更业务号幂等，不产生新版本
        mockMvc.perform(post("/api/cases/CASE-H/scope-changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changeNo":"CHG-1","requestedBy":"alice","reason":"立案重复",
                                 "criteria":{"businessKeys":["http-9"],"categories":[],
                                 "createdAfter":null,"createdBefore":null}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNo").value(1));

        // 版本差异 v1 -> v2（缩围：移除 http-2）
        mockMvc.perform(post("/api/cases/CASE-H/scope-changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changeNo":"CHG-2","requestedBy":"alice","reason":"缩围",
                                 "criteria":{"businessKeys":["http-1"],"categories":[],
                                 "createdAfter":null,"createdBefore":null}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.removedCount").value(1));

        mockMvc.perform(get("/api/cases/CASE-H/versions/2/diff?from=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addedBusinessKeys").isArray())
                .andExpect(jsonPath("$.removedBusinessKeys[0]").value("http-2"))
                .andExpect(jsonPath("$.unchangedBusinessKeys[0]").value("http-1"));

        // 申请人自批 409
        mockMvc.perform(post("/api/cases/CASE-H/scope-changes/CHG-2/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"alice\",\"comment\":\"自批\"}"))
                .andExpect(status().isConflict());

        // 第一批准后未生效，http-2 仍受保全
        mockMvc.perform(post("/api/cases/CASE-H/scope-changes/CHG-2/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"boss-a\",\"comment\":\"同意\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalCount").value(1))
                .andExpect(jsonPath("$.effective").value(false));

        // 同一人重复批准幂等，仍为 1
        mockMvc.perform(post("/api/cases/CASE-H/scope-changes/CHG-2/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"boss-a\",\"comment\":\"再同意\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalCount").value(1));

        // 第二批准（不同人员）生效，http-2 被释放
        mockMvc.perform(post("/api/cases/CASE-H/scope-changes/CHG-2/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"boss-b\",\"comment\":\"同意\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective").value(true))
                .andExpect(jsonPath("$.versionStatus").value("EFFECTIVE"));

        // 对象全部保全：http-1 仍在 CASE-H v2
        mockMvc.perform(get("/api/cases/holds/objects/http-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdCount").value(1))
                .andExpect(jsonPath("$.holds[0].caseNo").value("CASE-H"))
                .andExpect(jsonPath("$.holds[0].effectiveVersionNo").value(2));

        // http-2 已释放，但释放原因可查
        mockMvc.perform(get("/api/cases/holds/objects/http-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdCount").value(0));
        mockMvc.perform(get("/api/cases/holds/objects/http-2/release-reasons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.eventType=='RELEASE')].reason")
                        .value(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("范围缩小"))));

        // 案件历史含两个版本，旧版本 SUPERSEDED
        mockMvc.perform(get("/api/cases/CASE-H"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.currentVersionNo").value(2))
                .andExpect(jsonPath("$.versions.length()").value(2));
    }

    @Test
    void batchRejectIsSurfacedAsConflictWhenOtherCaseAppears() throws Exception {
        rule("DOC", 30);
        register("r-1", "DOC");

        mockMvc.perform(post("/api/cases/CASE-R/scope-changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changeNo":"RC-1","requestedBy":"alice","reason":"立案",
                                 "criteria":{"businessKeys":["r-1"],"categories":[],
                                 "createdAfter":null,"createdBefore":null}}"""))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/cases/CASE-R/scope-changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changeNo":"RC-CLOSE","requestedBy":"alice","reason":"关闭",
                                 "closeCase":true,
                                 "criteria":{"businessKeys":[],"categories":[],
                                 "createdAfter":null,"createdBefore":null}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        mockMvc.perform(post("/api/cases/CASE-R/scope-changes/RC-CLOSE/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"boss-a\"}"))
                .andExpect(status().isOk());

        // 另一案件在第二批准前保全 r-1
        mockMvc.perform(post("/api/cases/CASE-R2/scope-changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changeNo":"RC2-1","requestedBy":"carol","reason":"另案",
                                 "criteria":{"businessKeys":["r-1"],"categories":[],
                                 "createdAfter":null,"createdBefore":null}}"""))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/cases/CASE-R/scope-changes/RC-CLOSE/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"boss-b\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasons", org.hamcrest.Matchers
                        .hasItem(org.hamcrest.Matchers.containsString("其他案件保全变化"))));

        // 审批进度显示被拒
        mockMvc.perform(get("/api/cases/CASE-R/scope-changes/RC-CLOSE/approvals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected").value(true))
                .andExpect(jsonPath("$.effective").value(false));
    }

    @Test
    void invalidRequestIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/cases/CASE-X/scope-changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changeNo":"","requestedBy":"","reason":"r",
                                 "criteria":{"businessKeys":[],"categories":[],
                                 "createdAfter":null,"createdBefore":null}}"""))
                .andExpect(status().isBadRequest());
    }
}
