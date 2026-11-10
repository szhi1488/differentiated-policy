package com.company.policy.controller;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.common.exception.GlobalExceptionHandler;
import com.company.policy.service.FeishuTicketService;
import com.company.policy.service.JsSdkSignatureService;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FeishuControllerTest {
    private MockMvc mvc() {
        var properties = new FeishuProperties();
        properties.setAppId("test-app");
        properties.setAllowedOrigins(List.of("http://localhost:5173"));
        var tickets = new FeishuTicketService(properties, null, Clock.systemUTC());
        var signatures = new JsSdkSignatureService(properties, tickets, Clock.systemUTC());
        return MockMvcBuilders.standaloneSetup(new FeishuController(properties, signatures))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void configIsPublicAndDoesNotClaimLogin() throws Exception {
        mvc().perform(get("/api/feishu/config"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.appId").value("test-app"))
                .andExpect(jsonPath("$.sdkEnabled").value(false))
                .andExpect(jsonPath("$.appSecret").doesNotExist());
    }

    @Test
    void rejectsMissingUrl() throws Exception {
        mvc().perform(post("/api/feishu/js-sdk/signature").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void missingCredentialsAreActionableWithoutLeakingDetails() throws Exception {
        mvc().perform(post("/api/feishu/js-sdk/signature").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://localhost:5173/\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("FEISHU_NOT_CONFIGURED"))
                .andExpect(content().string(not(containsString("app_secret"))));
    }

    @Test
    void unapprovedOriginReturnsBadRequest() throws Exception {
        mvc().perform(post("/api/feishu/js-sdk/signature").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://unexpected.example/\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAGE_URL"));
    }
}
