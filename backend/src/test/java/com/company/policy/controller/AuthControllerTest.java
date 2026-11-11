package com.company.policy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.company.policy.service.FeishuApiTransport;
import com.company.policy.service.UserInfoService;
import com.company.policy.vo.CurrentUserResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(
        properties = {
            "spring.config.import=",
            "feishu.app-id=test-app",
            "feishu.app-secret=test-secret",
            "feishu.allowed-origins=http://localhost:5173"
        })
@AutoConfigureMockMvc
class AuthControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean FeishuApiTransport transport;
    @MockitoBean Clock clock;
    @MockitoBean UserInfoService users;
    private final Instant now = Instant.parse("2026-11-10T02:00:00Z");

    @BeforeEach
    void setup() throws Exception {
        when(clock.instant()).thenReturn(now);
        when(users.saveVerifiedIdentity("tenant-test", "ou_verified", null, "测试用户"))
                .thenReturn(
                        new CurrentUserResponse(
                                "1", "ou_verified", "tenant-test", "测试用户", "APPLICANT"));
        when(transport.post(eq("/oauth/v3/token"), anyMap(), isNull()))
                .thenReturn(
                        mapper.readTree(
                                "{\"code\":0,\"access_token\":\"private-token\",\"expires_in\":7200}"));
        when(transport.getUserInfo("private-token"))
                .thenReturn(
                        mapper.readTree(
                                "{\"code\":0,\"data\":{\"open_id\":\"ou_verified\",\"tenant_key\":\"tenant-test\",\"name\":\"测试用户\"}}"));
    }

    private MockHttpServletRequestBuilder mutation(String path) {
        return post(path).header("Origin", "http://localhost:5173").header("X-Policy-Request", "1");
    }

    private String browserState(MockHttpSession session) throws Exception {
        var response =
                mvc.perform(mutation("/api/auth/feishu/browser/authorize").session(session))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn()
                        .getResponse();
        var url = mapper.readTree(response.getContentAsString()).path("authorizationUrl").asText();
        var uri = org.springframework.web.util.UriComponentsBuilder.fromUriString(url).build();
        assertThat(uri.getHost()).isEqualTo("accounts.feishu.cn");
        assertThat(uri.getPath()).isEqualTo("/open-apis/authen/v1/authorize");
        var params = uri.getQueryParams();
        assertThat(params.getFirst("client_id")).isEqualTo("test-app");
        assertThat(params.getFirst("response_type")).isEqualTo("code");
        assertThat(
                        java.net.URLDecoder.decode(
                                params.getFirst("redirect_uri"),
                                java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("http://localhost:5173/");
        assertThat(params).doesNotContainKeys("scope", "client_secret");
        return params.getFirst("state");
    }

    private MockHttpServletRequestBuilder browserLogin(MockHttpSession session, String state)
            throws Exception {
        return mutation("/api/auth/feishu/browser/login")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("code", "browser-once", "state", state)));
    }

    @Test
    void browserAuthorizationBindsCallbackAndEstablishesRestorableSession() throws Exception {
        var session = new MockHttpSession();
        var originalId = session.getId();
        var state = browserState(session);
        mvc.perform(browserLogin(session, state))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value("1"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
        assertThat(session.getId()).isNotEqualTo(originalId);
        verify(transport)
                .post(
                        "/oauth/v3/token",
                        Map.of(
                                "grant_type",
                                "authorization_code",
                                "client_id",
                                "test-app",
                                "client_secret",
                                "test-secret",
                                "code",
                                "browser-once",
                                "redirect_uri",
                                "http://localhost:5173/"),
                        null);
        mvc.perform(get("/api/auth/session").session(session)).andExpect(status().isOk());
        mvc.perform(browserLogin(session, state)).andExpect(status().isUnauthorized());
        verify(transport, times(1)).post(eq("/oauth/v3/token"), anyMap(), isNull());
    }

    @Test
    void browserRejectsForeignExpiredReplacedAndClientStatesBeforeTokenExchange() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(browserLogin(session, "missing")).andExpect(status().isUnauthorized());
        var state = browserState(session);
        mvc.perform(browserLogin(new MockHttpSession(), state))
                .andExpect(status().isUnauthorized());
        when(clock.instant()).thenReturn(now.plusSeconds(180));
        mvc.perform(browserLogin(session, state)).andExpect(status().isUnauthorized());
        when(clock.instant()).thenReturn(now);
        state = browserState(session);
        browserState(session);
        mvc.perform(browserLogin(session, state)).andExpect(status().isUnauthorized());
        mvc.perform(browserLogin(session, challenge(session))).andExpect(status().isUnauthorized());
        verifyNoInteractions(transport);
    }

    @Test
    void browserEndpointsEnforceOriginAndHeadersAndDoNotEstablishFailedLogin() throws Exception {
        for (var path :
                java.util.List.of(
                        "/api/auth/feishu/browser/authorize", "/api/auth/feishu/browser/login")) {
            mvc.perform(post(path)).andExpect(status().isForbidden());
            mvc.perform(
                            post(path)
                                    .header("Origin", "https://attacker.example")
                                    .header("X-Policy-Request", "1"))
                    .andExpect(status().isForbidden());
        }
        when(users.saveVerifiedIdentity(anyString(), anyString(), isNull(), anyString()))
                .thenThrow(
                        new com.company.policy.common.exception.ApiException(
                                org.springframework.http.HttpStatus.FORBIDDEN,
                                "USER_DISABLED",
                                "账号已停用"));
        var session = new MockHttpSession();
        var state = browserState(session);
        mvc.perform(browserLogin(session, state)).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/session").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(browserLogin(session, state)).andExpect(status().isUnauthorized());
    }

    private String challenge(MockHttpSession session) throws Exception {
        var result =
                mvc.perform(mutation("/api/auth/challenge").session(session))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("state").asText();
    }

    private MockHttpServletRequestBuilder login(MockHttpSession session, String state)
            throws Exception {
        return mutation("/api/auth/feishu/login")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        mapper.writeValueAsString(
                                Map.of("code", "single-use-code", "state", state)));
    }

    @Test
    void verifiedLoginRotatesSessionAndReusesIdentityUntilLogout() throws Exception {
        var session = new MockHttpSession();
        String originalId = session.getId();
        String state = challenge(session);
        mvc.perform(login(session, state))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.openId").value("ou_verified"))
                .andExpect(jsonPath("$.user.tenantKey").value("tenant-test"))
                .andExpect(jsonPath("$.user.name").value("测试用户"))
                .andExpect(jsonPath("$.access_token").doesNotExist())
                .andExpect(jsonPath("$.user.id").value("1"))
                .andExpect(jsonPath("$.user.roleType").value("APPLICANT"))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "private-token"))));
        assertThat(session.getId()).isNotEqualTo(originalId);
        verify(users).saveVerifiedIdentity("tenant-test", "ou_verified", null, "测试用户");
        verify(transport)
                .post(
                        "/oauth/v3/token",
                        Map.of(
                                "grant_type",
                                "authorization_code",
                                "client_id",
                                "test-app",
                                "client_secret",
                                "test-secret",
                                "code",
                                "single-use-code"),
                        null);
        mvc.perform(get("/api/auth/session").session(session)).andExpect(status().isOk());
        mvc.perform(mutation("/api/auth/logout").session(session))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/session")).andExpect(status().isUnauthorized());
        mvc.perform(mutation("/api/auth/logout")).andExpect(status().isNoContent());
    }

    @Test
    void rejectsMissingForeignExpiredAndReplayedStateBeforeTokenExchange() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(login(session, "missing")).andExpect(status().isUnauthorized());
        String state = challenge(session);
        mvc.perform(login(new MockHttpSession(), state)).andExpect(status().isUnauthorized());
        when(clock.instant()).thenReturn(now.plusSeconds(180));
        mvc.perform(login(session, state)).andExpect(status().isUnauthorized());
        when(clock.instant()).thenReturn(now);
        state = challenge(session);
        mvc.perform(login(session, state)).andExpect(status().isOk());
        mvc.perform(login(session, state)).andExpect(status().isUnauthorized());
        verify(transport, times(1)).post(eq("/oauth/v3/token"), anyMap(), isNull());
    }

    @Test
    void rejectsCrossOriginAndSimpleRequests() throws Exception {
        mvc.perform(post("/api/auth/challenge")).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/challenge").header("Origin", "http://localhost:5173"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/auth/challenge")
                                .header("X-Policy-Request", "1")
                                .header("Origin", "https://attacker.example"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(transport);
    }

    @Test
    void expiresAtTokenBoundaryAndProtectsBusinessApi() throws Exception {
        mvc.perform(get("/api/policies")).andExpect(status().isUnauthorized());
        var session = new MockHttpSession();
        mvc.perform(login(session, challenge(session))).andExpect(status().isOk());
        when(clock.instant()).thenReturn(now.plusSeconds(7200));
        mvc.perform(get("/api/auth/session").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
    }

    @Test
    void invalidUpstreamIdentityNeverCreatesLoginAndConsumesCodeAttempt() throws Exception {
        when(transport.getUserInfo(anyString()))
                .thenReturn(mapper.readTree("{\"code\":0,\"data\":{\"name\":\"假身份\"}}"));
        var session = new MockHttpSession();
        String state = challenge(session);
        mvc.perform(login(session, state)).andExpect(status().isBadGateway());
        mvc.perform(get("/api/auth/session").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(login(session, state)).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidTokenLifetimeIsRejectedAndShortLifetimeIsRespected() throws Exception {
        when(transport.post(eq("/oauth/v3/token"), anyMap(), isNull()))
                .thenReturn(
                        mapper.readTree(
                                "{\"code\":0,\"access_token\":\"private-token\",\"expires_in\":0}"));
        var session = new MockHttpSession();
        mvc.perform(login(session, challenge(session))).andExpect(status().isBadGateway());
        verify(transport, never()).getUserInfo(anyString());
        when(transport.post(eq("/oauth/v3/token"), anyMap(), isNull()))
                .thenReturn(
                        mapper.readTree(
                                "{\"code\":0,\"access_token\":\"private-token\",\"expires_in\":60}"));
        mvc.perform(login(session, challenge(session))).andExpect(status().isOk());
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        mvc.perform(get("/api/auth/session").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void storageFailureDoesNotEstablishSession() throws Exception {
        when(users.saveVerifiedIdentity(anyString(), anyString(), isNull(), anyString()))
                .thenThrow(
                        new com.company.policy.common.exception.ApiException(
                                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                                "USER_STORAGE_UNAVAILABLE",
                                "用户信息保存失败。"));
        var session = new MockHttpSession();
        mvc.perform(login(session, challenge(session)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("USER_STORAGE_UNAVAILABLE"));
        mvc.perform(get("/api/auth/session").session(session)).andExpect(status().isUnauthorized());
    }
}
