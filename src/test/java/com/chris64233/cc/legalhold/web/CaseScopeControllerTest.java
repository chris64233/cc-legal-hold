package com.chris64233.cc.legalhold.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chris64233.cc.legalhold.CcLegalHoldApplication;
import com.chris64233.cc.legalhold.support.MutableTestClock;
import com.chris64233.cc.legalhold.support.TestClockConfig;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = {CcLegalHoldApplication.class, TestClockConfig.class}, properties = {
        "legalhold.scope.materialize-batch-size=2",
        "legalhold.scope.max-batches-per-call=100"
})
@AutoConfigureMockMvc
class CaseScopeControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MutableTestClock clock;

    @BeforeEach
    void setUp() {
        clock.setTime(Instant.parse("2026-01-01T00:00:00Z"));
    }

    private void rule(String category, int days) throws Exception {
        mockMvc.perform(post("/api/objects/retention-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"" + category + "\",\"minRetentionDays\":" + days + "}"))
                .andExpect(status().isOk());
    }

    private void obj(String key, String category) throws Exception {
        mockMvc.perform(post("/api/objects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessKey\":\"" + key + "\",\"category\":\"" + category + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void expandShrinkApproveDiffAndQueriesOverHttp() throws Exception {
        rule("DOC", 30);
        obj("h-1", "DOC");
        obj("h-2", "DOC");
        obj("h-3", "DOC");

        // 扩围：显式三个对象
        mockMvc.perform(post("/api/case-scopes/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"caseNo":"CASE-H","changeNo":"CHG-H1","reason":"立案",
                                 "createdBy":"alice",
                                 "criteria":{"businessKeys":["h-1","h-2","h-3"],
                                             "categories":[],"createdFrom":null,"createdTo":null}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.addedCount").value(3));

        // 同 changeNo 重放：幂等，不产生新版本
        mockMvc.perform(post("/api/case-scopes/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"caseNo":"CASE-H","changeNo":"CHG-H1","reason":"立案",
                                 "createdBy":"alice",
                                 "criteria":{"businessKeys":["h-1","h-2","h-3"],
                                             "categories":[],"createdFrom":null,"createdTo":null}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.idempotentReplay").value(true));

        // 缩围到只剩 h-1
        mockMvc.perform(post("/api/case-scopes/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"caseNo":"CASE-H","changeNo":"CHG-H2","reason":"缩小",
                                 "createdBy":"alice",
                                 "criteria":{"businessKeys":["h-1"],
                                             "categories":[],"createdFrom":null,"createdTo":null}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.removedCount").value(2));

        // 进度查询：0 人批准
        mockMvc.perform(get("/api/case-scopes/changes/CHG-H2/approval-progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredApprovals").value(2))
                .andExpect(jsonPath("$.approveCount").value(0));

        // 审批人不能是申请人
        mockMvc.perform(post("/api/case-scopes/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changeNo":"CHG-H2","reviewer":"alice","vote":"APPROVE"}
                                """))
                .andExpect(status().isConflict());

        // 第一名批准：仍待审批
        mockMvc.perform(post("/api/case-scopes/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changeNo":"CHG-H2","reviewer":"bob","vote":"APPROVE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.approveCount").value(1));

        // 同一人重复投票幂等
        mockMvc.perform(post("/api/case-scopes/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changeNo":"CHG-H2","reviewer":"bob","vote":"APPROVE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approveCount").value(1));

        // 第二名不同人员批准：生效
        mockMvc.perform(post("/api/case-scopes/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changeNo":"CHG-H2","reviewer":"carol","vote":"APPROVE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"));

        // 差异查询
        mockMvc.perform(get("/api/case-scopes/CASE-H/diff").param("from", "1").param("to", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removedBusinessKeys[0]").value("h-2"))
                .andExpect(jsonPath("$.removedBusinessKeys[1]").value("h-3"))
                .andExpect(jsonPath("$.retainedBusinessKeys[0]").value("h-1"));

        // 版本列表与当前版本
        mockMvc.perform(get("/api/case-scopes/CASE-H/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/case-scopes/CASE-H/versions/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNo").value(2));

        // 对象所受保全与释放原因
        mockMvc.perform(get("/api/case-scopes/objects/h-1/holds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].caseNo").value("CASE-H"));
        mockMvc.perform(get("/api/case-scopes/objects/h-2/holds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/case-scopes/objects/h-2/release-reasons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].changeNo").value("CHG-H2"))
                .andExpect(jsonPath("$[0].versionNo").value(2));
    }

    @Test
    void emptyCriteriaIsRejected() throws Exception {
        mockMvc.perform(post("/api/case-scopes/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"caseNo":"CASE-E","changeNo":"CHG-E","reason":"空",
                                 "createdBy":"alice",
                                 "criteria":{"businessKeys":[],"categories":[],
                                             "createdFrom":null,"createdTo":null}}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownChangeProgressIsNotFound() throws Exception {
        mockMvc.perform(get("/api/case-scopes/changes/NOPE/approval-progress"))
                .andExpect(status().isNotFound());
    }
}
