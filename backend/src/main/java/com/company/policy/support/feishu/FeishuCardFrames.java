package com.company.policy.support.feishu;

import com.company.policy.common.exception.ApiException;
import com.company.policy.service.PolicyCardCallback;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.google.protobuf.ByteString;
import com.lark.oapi.ws.pb.Pbbp2.Frame;
import com.lark.oapi.ws.pb.Pbbp2.Header;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 每条长连接独立合包。ACK 保留飞书原帧标识，data 携带 Base64 编码的业务响应。 */
final class FeishuCardFrames {
    private static final Logger log = LoggerFactory.getLogger(FeishuCardFrames.class);
    static final int MAX_BYTES = 1024 * 1024;
    private final Map<String, Parts> fragments = new HashMap<>();
    private final ObjectMapper json;
    private final PolicyCardCallback callback;

    FeishuCardFrames(ObjectMapper json, PolicyCardCallback callback) {
        this.json = json;
        this.callback = callback;
    }

    Frame reply(Frame frame) throws IOException {
        long start = System.nanoTime();
        Map<String, ?> reply;
        try {
            String type = header(frame, "type");
            if (!"event".equals(type) && !"card".equals(type)) throw new IllegalArgumentException();
            ByteString body = payload(frame);
            if (body == null) return null;
            JsonNode event = json.readTree(body.newInput());
            if (event == null
                    || !"card.action.trigger".equals(event.at("/header/event_type").textValue()))
                throw new IllegalArgumentException();
            Map<String, ?> result;
            try {
                result = callback.handleLongConnection(event);
            } catch (ApiException denied) {
                if (!"FEISHU_CALLBACK_DENIED".equals(denied.getCode())) throw denied;
                result = Map.of("toast", Map.of("type", "error", "content", "此卡当前无法办理。"));
            }
            reply = Map.of("code", 200, "data", json.writeValueAsBytes(result));
        } catch (Exception failure) {
            log.warn("飞书卡片长连接回调处理失败，类型 {}", failure.getClass().getSimpleName());
            reply = Map.of("code", 500);
        }
        return ack(frame, reply, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }

    Frame error(Frame frame) throws IOException {
        return ack(frame, Map.of("code", 500), 0);
    }

    private Frame ack(Frame frame, Map<String, ?> reply, long elapsed) throws IOException {
        return frame.toBuilder()
                .setPayload(ByteString.copyFrom(json.writeValueAsBytes(reply)))
                .addHeaders(header("biz_rt", Long.toString(elapsed)))
                .build();
    }

    private synchronized ByteString payload(Frame frame) {
        int total = Integer.parseInt(header(frame, "sum"));
        int seq = Integer.parseInt(header(frame, "seq"));
        var data = frame.getPayload();
        if (total < 1 || total > 64 || seq < 0 || seq >= total || data.size() > MAX_BYTES)
            throw new IllegalArgumentException();
        if (total == 1) return data;
        String id = header(frame, "message_id");
        if (id.isBlank()) throw new IllegalArgumentException();
        long now = System.nanoTime();
        fragments.values().removeIf(p -> now - p.createdAt > Duration.ofMinutes(5).toNanos());
        var parts = fragments.get(id);
        if (parts == null) {
            if (fragments.size() >= 16) throw new IllegalArgumentException();
            parts = new Parts(total, now);
            fragments.put(id, parts);
        }
        if (parts.chunks.length != total) throw new IllegalArgumentException();
        if (parts.chunks[seq] == null) {
            parts.bytes += data.size();
            if (parts.bytes > MAX_BYTES) {
                fragments.remove(id);
                throw new IllegalArgumentException();
            }
            parts.chunks[seq] = data;
            parts.count++;
        } else if (!parts.chunks[seq].equals(data)) {
            fragments.remove(id);
            throw new IllegalArgumentException();
        }
        if (parts.count < total) return null;
        fragments.remove(id);
        return ByteString.copyFrom(java.util.Arrays.asList(parts.chunks));
    }

    private static final class Parts {
        final ByteString[] chunks;
        final long createdAt;
        int bytes;
        int count;

        Parts(int total, long createdAt) {
            this.chunks = new ByteString[total];
            this.createdAt = createdAt;
        }
    }

    static String header(Frame frame, String key) {
        return frame.getHeadersList().stream()
                .filter(h -> key.equals(h.getKey()))
                .findFirst()
                .map(Header::getValue)
                .orElse("");
    }

    static Header header(String key, String value) {
        return Header.newBuilder().setKey(key).setValue(value).build();
    }
}
