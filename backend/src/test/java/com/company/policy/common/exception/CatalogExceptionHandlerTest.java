package com.company.policy.common.exception;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.policy.controller.CatalogDictionaryController;
import com.company.policy.service.CatalogDictionaryService;
import com.company.policy.service.LoginSessionService;
import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.CurrentUserResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CatalogExceptionHandlerTest {
    @Test
    void distinguishesLockConflictFromQueryTimeout() throws Exception {
        var dictionary = mock(CatalogDictionaryService.class);
        var sessions = mock(LoginSessionService.class);
        var admin = new CurrentUserResponse("1", "admin", "tenant", "管理员", "ADMIN");
        when(sessions.requireLogin(any())).thenReturn(new AuthSessionResponse(admin, Instant.MAX));
        when(dictionary.list(admin))
                .thenThrow(new CannotAcquireLockException("lock conflict"))
                .thenThrow(new QueryTimeoutException("query timeout"));
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new CatalogDictionaryController(dictionary, sessions))
                        .setControllerAdvice(
                                new GlobalExceptionHandler(), new CatalogExceptionHandler())
                        .build();

        mvc.perform(get("/api/admin/catalog/dictionary"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_DICTIONARY_CONFLICT"));
        mvc.perform(get("/api/admin/catalog/dictionary"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CATALOG_STORAGE_UNAVAILABLE"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }
}
