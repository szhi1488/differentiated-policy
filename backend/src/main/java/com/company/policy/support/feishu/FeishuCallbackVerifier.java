package com.company.policy.support.feishu;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 校验飞书卡片回调所属应用和企业。HTTP 请求另行解密、验签和核对验证令牌。 */
@Component
public class FeishuCallbackVerifier {
    private final String encryptKey;
    private final String token;
    private final FeishuProperties feishu;
    private final ObjectMapper json;
    private final Clock clock;

    public FeishuCallbackVerifier(
            @Value("${feishu.callback-encrypt-key:}") String encryptKey,
            @Value("${feishu.callback-verification-token:}") String token,
            FeishuProperties feishu,
            ObjectMapper json,
            Clock clock) {
        this.encryptKey = encryptKey;
        this.token = token;
        this.feishu = feishu;
        this.json = json;
        this.clock = clock;
    }

    public JsonNode verify(byte[] raw, String timestamp, String nonce, String signature) {
        if (encryptKey.isBlank()
                || token.isBlank()
                || feishu.getAppId().isBlank()
                || !feishu.hasCompanyTenant()) throw denied();
        try {
            var outer = json.readTree(raw);
            byte[] data = Base64.getDecoder().decode(required(outer, "encrypt"));
            if (data.length < 32 || data.length % 16 != 0) throw denied();
            var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            byte[] key = MessageDigest.getInstance("SHA-256").digest(bytes(encryptKey));
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(Arrays.copyOf(data, 16)));
            var envelope = json.readTree(cipher.doFinal(data, 16, data.length - 16));
            if (envelope == null) throw denied();
            if ("url_verification".equals(envelope.path("type").textValue())) {
                // 飞书地址验证按解密后 token 回显 challenge；新版信封将 token 放在 header。
                var tokenSource =
                        envelope.has("schema") || envelope.has("header")
                                ? envelope.path("header")
                                : envelope;
                if (!same(token, required(tokenSource, "token"))) throw denied();
                required(envelope, "challenge");
                return envelope;
            }
            verifySignature(raw, timestamp, nonce, signature);
            if (!same(token, required(envelope.path("header"), "token"))) throw denied();
            verifyCardScope(envelope);
            return envelope;
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            throw denied();
        }
    }

    public void verifyCardScope(JsonNode envelope) {
        if (envelope == null || !"2.0".equals(envelope.path("schema").textValue())) throw denied();
        var header = envelope.path("header");
        if (!feishu.hasCompanyTenant()
                || !required(header, "app_id").equals(feishu.getAppId())
                || !required(header, "tenant_key").equals(feishu.getTenantKey())
                || !"card.action.trigger".equals(required(header, "event_type"))
                || !feishu.getTenantKey()
                        .equals(required(envelope.at("/event/operator"), "tenant_key")))
            throw denied();
    }

    private void verifySignature(byte[] raw, String timestamp, String nonce, String signature)
            throws GeneralSecurityException {
        if (signature == null
                || signature.length() != 64
                || timestamp == null
                || nonce == null
                || nonce.isBlank()) throw denied();
        long sent = Long.parseLong(timestamp);
        long now = clock.instant().getEpochSecond();
        if (sent < now - 300 || sent > now + 300) throw denied();
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(bytes(timestamp + nonce + encryptKey));
        if (!MessageDigest.isEqual(digest.digest(raw), HexFormat.of().parseHex(signature)))
            throw denied();
    }

    private static String required(JsonNode node, String key) {
        String value = node == null ? null : node.path(key).textValue();
        if (value == null || value.isBlank()) throw denied();
        return value;
    }

    private static boolean same(String expected, String actual) {
        return MessageDigest.isEqual(bytes(expected), bytes(actual));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static ApiException denied() {
        return new ApiException(HttpStatus.FORBIDDEN, "FEISHU_CALLBACK_DENIED", "飞书回调来源校验失败。");
    }
}
