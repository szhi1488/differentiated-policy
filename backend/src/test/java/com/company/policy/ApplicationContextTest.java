package com.company.policy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.config.import=", "feishu.app-secret="})
@AutoConfigureMockMvc
class ApplicationContextTest {
    @Autowired MockMvc mvc;

    @Test
    void applicationAndApiDocumentationStartWithoutDatabaseOrCredentials() throws Exception {
        mvc.perform(get("/api/feishu/config")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs"))
                .andDo(
                        result -> {
                            if (result.getResolvedException() != null) {
                                throw new AssertionError(
                                        "OpenAPI generation failed", result.getResolvedException());
                            }
                        })
                .andExpect(status().isOk());
        mvc.perform(get("/doc.html")).andExpect(status().isOk());
    }
}
