package com.company.policy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.common.exception.ApiException;
import com.company.policy.service.impl.JsSdkSignatureServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsSdkSignatureServiceTest {
    private final FeishuProperties properties = properties();
    private final FeishuTicketService tickets = mock(FeishuTicketService.class);
    private final JsSdkSignatureService service =
            new JsSdkSignatureServiceImpl(
                    properties,
                    tickets,
                    Clock.fixed(Instant.parse("2025-01-01T00:00:00.123Z"), ZoneOffset.UTC));

    private static FeishuProperties properties() {
        var properties = new FeishuProperties();
        properties.setAppId("test-app");
        properties.setAllowedOrigins(List.of("http://localhost:5173", "https://example.com"));
        return properties;
    }

    @Test
    void signatureMatchesIndependentSha1Vector() {
        assertThat(
                        JsSdkSignatureServiceImpl.createSignature(
                                "test-ticket",
                                "nonce-123",
                                1735689600123L,
                                "http://localhost:5173/?q=a%2Fb&x=1"))
                .isEqualTo("3f6ff72637cc72a5196285b733224826c4cddfac");
    }

    @Test
    void preservesEncodedQueryAndOnlyRemovesFragment() {
        assertThat(
                        service.validateAndStripFragment(
                                "http://localhost:5173/a%2Fb?q=x%20y&token=%23abc#view"))
                .isEqualTo("http://localhost:5173/a%2Fb?q=x%20y&token=%23abc");
        assertThat(service.validateAndStripFragment("https://example.com:443/?x=1"))
                .isEqualTo("https://example.com:443/?x=1");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://evil.example/",
                "http://localhost:5174/",
                "http://localhost:5173.evil.example/",
                "http://name@localhost:5173/",
                "javascript:alert(1)",
                "file:///etc/passwd",
                "//localhost:5173/",
                "http://localhost:5173/with space",
                "https://example.com:65536/",
                ""
            })
    void rejectsUnexpectedOriginsBeforeFetchingTicket(String url) {
        assertThatThrownBy(() -> service.sign(url))
                .isInstanceOf(ApiException.class)
                .hasMessage("页面地址不在已配置的 JS-SDK 允许来源中。");
        verifyNoInteractions(tickets);
    }

    @Test
    void responseUsesMillisecondsAndFreshNonce() {
        when(tickets.getTicket()).thenReturn("test-ticket");
        var first = service.sign("http://localhost:5173/#home");
        var second = service.sign("http://localhost:5173/#home");
        assertThat(first.timestamp()).isEqualTo(1735689600123L);
        assertThat(first.nonceStr()).isNotEqualTo(second.nonceStr());
        assertThat(first.signature())
                .isEqualTo(
                        JsSdkSignatureServiceImpl.createSignature(
                                "test-ticket",
                                first.nonceStr(),
                                first.timestamp(),
                                "http://localhost:5173/"));
        assertThat(first.jsApiList()).isEmpty();
    }
}
