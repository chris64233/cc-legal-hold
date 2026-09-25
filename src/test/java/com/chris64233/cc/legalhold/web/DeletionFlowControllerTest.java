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

@SpringBootTest(classes = {CcLegalHoldApplication.class, TestClockConfig.class})
@AutoConfigureMockMvc
class DeletionFlowControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MutableTestClock clock;

    @BeforeEach
    void setUp() {
        clock.setTime(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void fullHappyPathOverHttp() throws Exception {
        mockMvc.perform(post("/api/objects/retention-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"DOC\",\"minRetentionDays\":30}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/objects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessKey\":\"w-1\",\"category\":\"DOC\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        clock.setTime(Instant.parse("2026-02-01T00:00:00Z"));

        String token = requestToken();

        mockMvc.perform(post("/api/deletions/confirm/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.alreadyConfirmed").value(false));

        mockMvc.perform(post("/api/deletions/confirm/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyConfirmed").value(true));

        mockMvc.perform(get("/api/objects/w-1/eligibility"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"))
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.blockingReasons[0]").value("对象已删除"));
    }

    @Test
    void blockedRequestReturnsConflictWithReasons() throws Exception {
        mockMvc.perform(post("/api/objects/retention-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"DOC\",\"minRetentionDays\":30}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/objects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessKey\":\"w-2\",\"category\":\"DOC\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/deletions/request/w-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasons[0]").value(
                        org.hamcrest.Matchers.containsString("保留期未满")));

        mockMvc.perform(get("/api/objects/w-2/eligibility"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.retentionDeadline")
                        .value("2026-01-31T00:00:00Z"))
                .andExpect(jsonPath("$.activeHoldCases").isArray());
    }

    @Test
    void invalidRequestBodyReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/objects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessKey\":\"\",\"category\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    private String requestToken() throws Exception {
        String response = mockMvc.perform(post("/api/deletions/request/w-1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int start = response.indexOf("\"token\":\"") + 9;
        int end = response.indexOf('"', start);
        return response.substring(start, end);
    }
}
