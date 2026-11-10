package com.company.policy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.common.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class FeishuTicketServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final FeishuApiTransport transport = mock(FeishuApiTransport.class);
    private final MutableClock clock = new MutableClock();

    private FeishuProperties properties() {
        var properties = new FeishuProperties();
        properties.setAppId("test-app");
        properties.setAppSecret("fake-test-secret");
        return properties;
    }

    private void stubValidResponses() throws Exception {
        when(transport.post(eq("/open-apis/auth/v3/tenant_access_token/internal"), any(), isNull()))
                .thenReturn(mapper.readTree("{\"tenant_access_token\":\"fake-token\",\"expire\":7200}"));
        when(transport.post(eq("/open-apis/jssdk/ticket/get"), any(), eq("fake-token")))
                .thenReturn(mapper.readTree("{\"data\":{\"ticket\":\"fake-ticket\",\"expire_in\":7200}}"));
    }

    @Test
    void concurrentCallsShareOneTokenAndTicketFetch() throws Exception {
        stubValidResponses();
        var service = new FeishuTicketService(properties(), transport, clock);
        var executor = Executors.newFixedThreadPool(8);
        try {
            for (var result : executor.invokeAll(Collections.nCopies(32, (Callable<String>) service::getTicket))) {
                assertThat(result.get()).isEqualTo("fake-ticket");
            }
        } finally {
            executor.shutdownNow();
        }
        verify(transport, times(1)).post(eq("/open-apis/auth/v3/tenant_access_token/internal"), any(), isNull());
        verify(transport, times(1)).post(eq("/open-apis/jssdk/ticket/get"), any(), eq("fake-token"));
    }

    @Test
    void refreshesBeforeExpiry() throws Exception {
        stubValidResponses();
        var service = new FeishuTicketService(properties(), transport, clock);
        service.getTicket();
        clock.current = clock.current.plusSeconds(7139);
        service.getTicket();
        verify(transport, times(1)).post(eq("/open-apis/jssdk/ticket/get"), any(), any());
        clock.current = clock.current.plusSeconds(1);
        service.getTicket();
        verify(transport, times(2)).post(eq("/open-apis/jssdk/ticket/get"), any(), any());
        verify(transport, times(2)).post(eq("/open-apis/auth/v3/tenant_access_token/internal"), any(), isNull());
    }

    @Test
    void missingCredentialsFailBeforeNetwork() {
        var service = new FeishuTicketService(new FeishuProperties(), transport, clock);
        assertThatThrownBy(service::getTicket).isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode()).isEqualTo("FEISHU_NOT_CONFIGURED");
        verifyNoInteractions(transport);
    }

    @Test
    void malformedTicketIsNeverCached() throws Exception {
        stubValidResponses();
        when(transport.post(eq("/open-apis/jssdk/ticket/get"), any(), eq("fake-token")))
                .thenReturn(mapper.readTree("{\"data\":{\"ticket\":\"sensitive-body\",\"expire_in\":0}}"));
        var service = new FeishuTicketService(properties(), transport, clock);
        assertThatThrownBy(service::getTicket).isInstanceOf(ApiException.class).hasMessageNotContaining("sensitive-body");
        assertThatThrownBy(service::getTicket).isInstanceOf(ApiException.class);
        verify(transport, times(2)).post(eq("/open-apis/jssdk/ticket/get"), any(), any());
        verify(transport, times(2)).post(eq("/open-apis/auth/v3/tenant_access_token/internal"), any(), isNull());
    }

    private static class MutableClock extends Clock {
        private Instant current = Instant.parse("2025-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
