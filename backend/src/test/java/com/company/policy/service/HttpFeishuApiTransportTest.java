package com.company.policy.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.policy.common.exception.ApiException;
import com.company.policy.service.impl.HttpFeishuApiTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.verify;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpFeishuApiTransportTest {
    private final HttpClient client = mock(HttpClient.class);
    private final HttpFeishuApiTransport transport = new HttpFeishuApiTransport(client, new ObjectMapper());

    @Test
    void hidesUpstreamErrorBody() throws Exception {
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"code\":999,\"msg\":\"secret-from-upstream\"}");
        when(client.<String>send(any(), any())).thenReturn(response);
        assertThatThrownBy(() -> transport.post("/open-apis/jssdk/ticket/get", Map.of(), "fake-token"))
                .isInstanceOf(ApiException.class).hasMessageNotContaining("secret-from-upstream");
    }

    @Test
    void hidesTransportExceptionText() throws Exception {
        when(client.<String>send(any(), any())).thenThrow(new IOException("private-token"));
        assertThatThrownBy(() -> transport.post("/open-apis/jssdk/ticket/get", Map.of(), "fake-token"))
                .isInstanceOf(ApiException.class).hasMessageNotContaining("private-token").hasCause(null);
    }

    @Test
    void usesOfficialV3TokenHostAndBearerUserInfoEndpoint() throws Exception {
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"code\":0}");
        when(client.<String>send(any(), any())).thenReturn(response);
        transport.post("/oauth/v3/token", Map.of("code", "single-use"), null);
        var requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(requests.capture(), any());
        assertThat(requests.getValue().uri().toString()).isEqualTo("https://accounts.feishu.cn/oauth/v3/token");
        assertThat(requests.getValue().headers().firstValue("Authorization")).isEmpty();
        org.mockito.Mockito.clearInvocations(client);
        transport.getUserInfo("private-token");
        verify(client).send(requests.capture(), any());
        assertThat(requests.getValue().uri().toString()).isEqualTo("https://open.feishu.cn/open-apis/authen/v1/user_info");
        assertThat(requests.getValue().method()).isEqualTo("GET");
        assertThat(requests.getValue().headers().firstValue("Authorization")).contains("Bearer private-token");
    }

    @Test
    void expiredCodesAndDeniedUsersReturnActionableSanitizedErrors() throws Exception {
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(400);
        when(client.<String>send(any(), any())).thenReturn(response);
        when(response.body()).thenReturn("{\"code\":20065,\"error_description\":\"private-details\"}");
        assertThatThrownBy(() -> transport.post("/oauth/v3/token", Map.of(), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("FEISHU_CODE_INVALID"))
                .hasMessageNotContaining("private-details");
        when(response.body()).thenReturn("{\"code\":20010,\"error_description\":\"private-details\"}");
        assertThatThrownBy(() -> transport.post("/oauth/v3/token", Map.of(), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("FEISHU_ACCESS_DENIED"));
    }
}
