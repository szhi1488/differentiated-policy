package com.company.policy.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.policy.common.exception.ApiException;
import com.company.policy.service.impl.HttpFeishuApiTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
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
}
