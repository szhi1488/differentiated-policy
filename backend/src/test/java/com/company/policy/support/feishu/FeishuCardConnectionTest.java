package com.company.policy.support.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.service.PolicyCardCallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.google.protobuf.ByteString;
import com.lark.oapi.ws.pb.Pbbp2.Frame;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeishuCardConnectionTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PolicyCardCallback callbacks = mock(PolicyCardCallback.class);
    private static final String EVENT =
            """
            {"schema":"2.0","header":{"event_type":"card.action.trigger"},"event":{"round":1}}
            """;

    @ParameterizedTest
    @ValueSource(strings = {"card", "event"})
    void repliesOnOriginalFrameAfterOutOfOrderPartsWithIntegersAndToast(String type)
            throws Exception {
        var frames = new FeishuCardFrames(json, callbacks);
        var expected = Map.<String, Object>of("toast", Map.of("type", "success", "content", "已办理"));
        when(callbacks.handleLongConnection(any())).thenReturn(expected);
        assertThat(frames.reply(frame(type, 2, 1, EVENT.substring(20)))).isNull();
        verifyNoInteractions(callbacks);
        Frame reply = frames.reply(frame(type, 2, 0, EVENT.substring(0, 20)));
        verify(callbacks)
                .handleLongConnection(argThat(e -> e.at("/event/round").isIntegralNumber()));
        assertThat(reply.getSeqID()).isEqualTo(17);
        assertThat(reply.getLogID()).isEqualTo(35);
        assertThat(FeishuCardFrames.header(reply, "type")).isEqualTo(type);
        assertThat(FeishuCardFrames.header(reply, "biz_rt")).matches("[0-9]+");
        var body = json.readTree(reply.getPayload().newInput());
        assertThat(body.path("code").asInt()).isEqualTo(200);
        assertThat(json.readTree(body.get("data").binaryValue()))
                .isEqualTo(json.valueToTree(expected));
    }

    @Test
    void rejectsInvalidFragmentsAndReturnsFailureAckForBusinessOutage() throws Exception {
        var frames = new FeishuCardFrames(json, callbacks);
        var reply = frames.reply(frame("card", 99999, 0, EVENT));
        assertThat(json.readTree(reply.getPayload().newInput()).path("code").asInt())
                .isEqualTo(500);
        verifyNoInteractions(callbacks);
        when(callbacks.handleLongConnection(any())).thenThrow(new IllegalStateException());
        reply = frames.reply(frame("event", 1, 0, EVENT));
        assertThat(json.readTree(reply.getPayload().newInput()).path("code").asInt())
                .isEqualTo(500);
    }

    @Test
    void bootstrapsPingsProcessesWebSocketFragmentsReconnectsAndStops() throws Exception {
        HttpClient http = mock(HttpClient.class);
        WebSocket.Builder builder = mock(WebSocket.Builder.class, RETURNS_SELF);
        var first = mock(WebSocket.class);
        var second = mock(WebSocket.class);
        var sockets = new LinkedBlockingQueue<WebSocket>();
        sockets.add(first);
        sockets.add(second);
        var sent = new LinkedBlockingQueue<Frame>();
        var listeners = new LinkedBlockingQueue<WebSocket.Listener>();
        String remote = "wss://example.test/ws?service_id=42&device_id=test-device";
        for (var ws : sockets) {
            doAnswer(
                            call -> {
                                ByteBuffer buffer = call.getArgument(0);
                                sent.add(Frame.parseFrom(buffer));
                                return CompletableFuture.completedFuture(ws);
                            })
                    .when(ws)
                    .sendBinary(any(ByteBuffer.class), eq(true));
        }
        when(http.newWebSocketBuilder()).thenReturn(builder);
        when(builder.buildAsync(eq(URI.create(remote)), any()))
                .thenAnswer(
                        call -> {
                            var ws = sockets.remove();
                            WebSocket.Listener listener = call.getArgument(1);
                            listener.onOpen(ws);
                            listeners.add(listener);
                            return CompletableFuture.completedFuture(ws);
                        });
        var bootstrap = URI.create("https://bootstrap.test/callback/ws/endpoint");
        HttpResponse<?> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        doReturn(
                        json.writeValueAsBytes(
                                Map.of(
                                        "code",
                                        0,
                                        "data",
                                        Map.of(
                                                "URL",
                                                remote,
                                                "ClientConfig",
                                                Map.of(
                                                        "ReconnectNonce",
                                                        0,
                                                        "ReconnectCount",
                                                        2,
                                                        "ReconnectInterval",
                                                        1,
                                                        "PingInterval",
                                                        60)))))
                .when(response)
                .body();
        doReturn(response)
                .when(http)
                .send(
                        argThat(req -> req.uri().equals(bootstrap) && req.method().equals("POST")),
                        any());
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(callbacks.handleLongConnection(any()))
                .thenAnswer(
                        call -> {
                            if (call.getArgument(0, com.fasterxml.jackson.databind.JsonNode.class)
                                            .at("/event/round")
                                            .asInt()
                                    == 1) {
                                started.countDown();
                                if (!release.await(4, TimeUnit.SECONDS))
                                    throw new IllegalStateException();
                            }
                            return Map.of("toast", Map.of("type", "success", "content", "成功"));
                        });
        var feishu = new FeishuProperties();
        feishu.setPolicyCallbackMode(FeishuProperties.PolicyCallbackMode.LONG_CONNECTION);
        feishu.setAppId("test-app");
        feishu.setAppSecret("test-only");
        feishu.setTenantKey("test-tenant");
        var client = new FeishuCardConnection(bootstrap, http, json, feishu, callbacks);
        try {
            client.start();
            var listener = listeners.poll(4, TimeUnit.SECONDS);
            assertThat(listener).isNotNull();
            var ping = sent.poll(4, TimeUnit.SECONDS);
            assertThat(ping.getService()).isEqualTo(42);
            assertThat(FeishuCardFrames.header(ping, "type")).isEqualTo("ping");
            var bytes = frame("card", 1, 0, EVENT).toByteArray();
            listener.onBinary(first, ByteBuffer.wrap(bytes, 0, 20), false);
            listener.onBinary(first, ByteBuffer.wrap(bytes, 20, bytes.length - 20), true);
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            var next =
                    frame("card", 1, 0, EVENT.replace("\"round\":1", "\"round\":2")).toBuilder()
                            .setSeqID(18)
                            .build();
            listener.onBinary(first, ByteBuffer.wrap(next.toByteArray()), true);
            assertThat(sent.poll(2, TimeUnit.SECONDS).getSeqID()).isEqualTo(18);
            release.countDown();
            var ack = sent.poll(4, TimeUnit.SECONDS);
            assertThat(json.readTree(ack.getPayload().newInput()).path("code").asInt())
                    .isEqualTo(200);
            listener.onClose(first, 1001, "");
            assertThat(listeners.poll(4, TimeUnit.SECONDS)).isNotNull();
            assertThat(sent.poll(4, TimeUnit.SECONDS)).isNotNull();
            verify(http, times(2)).send(any(HttpRequest.class), any());
            verify(callbacks, times(2)).handleLongConnection(any());
        } finally {
            release.countDown();
            client.stop();
        }
        assertThat(client.isRunning()).isFalse();
        verify(second).abort();
    }

    private static Frame frame(String type, int sum, int seq, String payload) {
        return Frame.newBuilder()
                .setMethod(1)
                .setService(42)
                .setSeqID(17)
                .setLogID(35)
                .addHeaders(FeishuCardFrames.header("type", type))
                .addHeaders(FeishuCardFrames.header("sum", Integer.toString(sum)))
                .addHeaders(FeishuCardFrames.header("seq", Integer.toString(seq)))
                .addHeaders(FeishuCardFrames.header("message_id", "test-id"))
                .setPayload(ByteString.copyFrom(payload.getBytes(StandardCharsets.UTF_8)))
                .build();
    }
}
