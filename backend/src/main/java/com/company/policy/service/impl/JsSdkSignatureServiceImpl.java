package com.company.policy.service.impl;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.common.exception.ApiException;
import com.company.policy.service.FeishuTicketService;
import com.company.policy.service.JsSdkSignatureService;
import com.company.policy.vo.JsSdkSignatureResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class JsSdkSignatureServiceImpl implements JsSdkSignatureService {
    private final FeishuProperties properties;
    private final FeishuTicketService ticketService;
    private final Clock clock;

    public JsSdkSignatureServiceImpl(
            FeishuProperties properties, FeishuTicketService ticketService, Clock clock) {
        this.properties = properties;
        this.ticketService = ticketService;
        this.clock = clock;
    }

    @Override
    public JsSdkSignatureResponse sign(String pageUrl) {
        String url = validateAndStripFragment(pageUrl);
        String ticket = ticketService.getTicket();
        long timestamp = clock.millis();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        return new JsSdkSignatureResponse(
                properties.getAppId(),
                timestamp,
                nonce,
                createSignature(ticket, nonce, timestamp, url),
                List.of());
    }

    @Override
    public String validateAndStripFragment(String pageUrl) {
        if (pageUrl == null || pageUrl.isBlank() || pageUrl.length() > 8192) {
            throw invalidUrl();
        }
        try {
            URI uri = URI.create(pageUrl);
            String origin = origin(uri);
            boolean allowed =
                    properties.getAllowedOrigins().stream()
                            .anyMatch(
                                    value -> {
                                        try {
                                            URI candidate = URI.create(value);
                                            return candidate.getRawQuery() == null
                                                    && candidate.getRawFragment() == null
                                                    && (candidate.getRawPath() == null
                                                            || candidate.getRawPath().isEmpty()
                                                            || candidate.getRawPath().equals("/"))
                                                    && origin(candidate).equals(origin);
                                        } catch (RuntimeException ignored) {
                                            return false;
                                        }
                                    });
            if (!allowed) {
                throw invalidUrl();
            }
            // 按浏览器原始 URL 字节签名，URI 规范化会改变已编码的查询参数值。
            int fragment = pageUrl.indexOf('#');
            return fragment < 0 ? pageUrl : pageUrl.substring(0, fragment);
        } catch (IllegalArgumentException ignored) {
            throw invalidUrl();
        }
    }

    private String origin(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getPort() > 65535) {
            throw invalidUrl();
        }
        int port =
                uri.getPort() == -1 ? (scheme.equalsIgnoreCase("https") ? 443 : 80) : uri.getPort();
        return scheme.toLowerCase(Locale.ROOT)
                + "://"
                + uri.getHost().toLowerCase(Locale.ROOT)
                + ":"
                + port;
    }

    public static String createSignature(String ticket, String nonce, long timestamp, String url) {
        String content =
                "jsapi_ticket="
                        + ticket
                        + "&noncestr="
                        + nonce
                        + "&timestamp="
                        + timestamp
                        + "&url="
                        + url;
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-1")
                                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 unavailable", impossible);
        }
    }

    private ApiException invalidUrl() {
        return new ApiException(
                HttpStatus.BAD_REQUEST, "INVALID_PAGE_URL", "页面地址不在已配置的 JS-SDK 允许来源中。");
    }
}
