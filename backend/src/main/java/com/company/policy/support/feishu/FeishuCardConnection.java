package com.company.policy.support.feishu;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.service.PolicyCardCallback;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.ws.pb.Pbbp2.Frame;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 服务端主动建立飞书长连接，应用退出时中断等待并释放本次连接。 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class FeishuCardConnection implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(FeishuCardConnection.class);
    private final URI bootstrap;
    private final HttpClient http;
    private final ObjectMapper json;
    private final FeishuProperties feishu;
    private final PolicyCardCallback callback;
    private volatile Thread runner;

    @Autowired
    FeishuCardConnection(
            HttpClient http,
            ObjectMapper json,
            FeishuProperties feishu,
            PolicyCardCallback callback) {
        this(
                URI.create("https://open.feishu.cn/callback/ws/endpoint"),
                http,
                json,
                feishu,
                callback);
    }

    FeishuCardConnection(
            URI bootstrap,
            HttpClient http,
            ObjectMapper json,
            FeishuProperties feishu,
            PolicyCardCallback callback) {
        this.bootstrap = bootstrap;
        this.http = http;
        this.json = json;
        this.feishu = feishu;
        this.callback = callback;
    }

    @Override
    public boolean isAutoStartup() {
        return feishu.usesLongConnection();
    }

    @Override
    public synchronized void start() {
        if (!isAutoStartup() || isRunning()) return;
        if (!feishu.isConfigured() || !feishu.hasCompanyTenant())
            throw new IllegalStateException("启用飞书长连接需要本企业的应用凭证及企业标识。");
        runner = new Thread(this::runConnections, "feishu-policy-card");
        runner.setDaemon(true);
        runner.start();
    }

    @Override
    public synchronized void stop() {
        var worker = runner;
        runner = null;
        if (worker == null) return;
        worker.interrupt();
        try {
            worker.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        var worker = runner;
        return worker != null && worker.isAlive();
    }

    private void runConnections() {
        // 审核事务可能等待行锁。控制并发且不排队，给其他单据及心跳保留接收机会。
        var handlers =
                new ThreadPoolExecutor(
                        0,
                        8,
                        30,
                        TimeUnit.SECONDS,
                        new SynchronousQueue<>(),
                        work -> {
                            var thread = new Thread(work, "feishu-card-action");
                            thread.setDaemon(true);
                            return thread;
                        });
        try {
            connectLoop(handlers);
        } finally {
            handlers.shutdownNow();
        }
    }

    private void connectLoop(Executor handlers) {
        var schedule = new Timing();
        int retries = 0;
        while (runner == Thread.currentThread()) {
            var session = new Session(schedule, handlers);
            try {
                URI endpoint = endpoint(schedule);
                int service =
                        Arrays.stream(endpoint.getRawQuery().split("&"))
                                .filter(q -> q.startsWith("service_id="))
                                .mapToInt(
                                        q -> Integer.parseInt(q.substring("service_id=".length())))
                                .findFirst()
                                .orElseThrow();
                session.listen(endpoint, service);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception failure) {
                log.warn("飞书卡片长连接未就绪，{}", diagnostic(failure));
            } finally {
                session.close();
            }
            if (runner != Thread.currentThread()) return;
            if (session.connected) retries = 0;
            if (schedule.retries >= 0 && retries >= schedule.retries) {
                log.warn("已达到飞书指定的重连次数，卡片回调连接已停止。");
                return;
            }
            try {
                long delay =
                        retries++ == 0 && schedule.jitter > 0
                                ? ThreadLocalRandom.current()
                                        .nextLong(1, schedule.jitter * 1000L + 1)
                                : schedule.interval * 1000L;
                Thread.sleep(delay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private URI endpoint(Timing schedule) throws IOException, InterruptedException {
        var request =
                HttpRequest.newBuilder(bootstrap)
                        .timeout(Duration.ofSeconds(feishu.getRequestTimeoutSeconds()))
                        .header("Content-Type", "application/json")
                        .header("locale", "zh")
                        .POST(
                                HttpRequest.BodyPublishers.ofByteArray(
                                        json.writeValueAsBytes(
                                                Map.of(
                                                        "AppID",
                                                        feishu.getAppId(),
                                                        "AppSecret",
                                                        feishu.getAppSecret()))))
                        .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200)
            throw new EndpointFailure("握手地址接口 HTTP " + response.statusCode());
        var body = json.readTree(response.body());
        int code = body.path("code").asInt(-1);
        if (code != 0) throw new EndpointFailure("握手地址接口业务码 " + code);
        var data = body.path("data");
        schedule.update(data.path("ClientConfig"));
        var endpoint = URI.create(data.path("URL").asText(""));
        if (!"wss".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getHost() == null
                || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() == null) throw new EndpointFailure("平台未返回有效的 WSS 连接地址");
        return endpoint;
    }

    private static String diagnostic(Throwable failure) {
        if (failure instanceof ExecutionException && failure.getCause() != null)
            return diagnostic(failure.getCause());
        if (failure instanceof EndpointFailure) return failure.getMessage();
        if (failure instanceof WebSocketHandshakeException handshake)
            return "WebSocket 握手 HTTP " + handshake.getResponse().statusCode();
        // 网络异常消息可能包含带有设备凭证的完整 WebSocket URL。
        return failure.getClass().getSimpleName();
    }

    private static final class EndpointFailure extends IOException {
        EndpointFailure(String message) {
            super(message);
        }
    }

    private static final class Timing {
        volatile int ping = 120;
        int interval = 120;
        int jitter = 30;
        int retries = -1;

        void update(JsonNode config) {
            ping = Math.max(1, config.path("PingInterval").asInt(ping));
            interval = Math.max(1, config.path("ReconnectInterval").asInt(interval));
            jitter = Math.max(0, config.path("ReconnectNonce").asInt(jitter));
            retries = config.path("ReconnectCount").asInt(retries);
        }
    }

    private final class Session implements WebSocket.Listener {
        private final Executor handlers;
        private final Timing schedule;
        private final FeishuCardFrames frames = new FeishuCardFrames(json, callback);
        private final ByteArrayOutputStream message = new ByteArrayOutputStream();
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private volatile WebSocket socket;
        private boolean connected;
        private volatile boolean closed;
        private volatile long lastPong;

        Session(Timing schedule, Executor handlers) {
            this.schedule = schedule;
            this.handlers = handlers;
        }

        void listen(URI endpoint, int service) throws Exception {
            var opening =
                    http.newWebSocketBuilder()
                            .connectTimeout(Duration.ofSeconds(feishu.getRequestTimeoutSeconds()))
                            .buildAsync(endpoint, this);
            try {
                var ws = opening.get(feishu.getRequestTimeoutSeconds(), TimeUnit.SECONDS);
                connected = true;
                log.info("飞书卡片回调长连接已建立。");
                Frame ping =
                        Frame.newBuilder()
                                .setSeqID(0)
                                .setLogID(0)
                                .setMethod(0)
                                .setService(service)
                                .addHeaders(FeishuCardFrames.header("type", "ping"))
                                .build();
                lastPong = System.nanoTime();
                while (!done.isDone()) {
                    send(ws, ping);
                    int interval = schedule.ping;
                    try {
                        done.get(interval, TimeUnit.SECONDS);
                    } catch (TimeoutException heartbeat) {
                        if (System.nanoTime() - lastPong >= TimeUnit.SECONDS.toNanos(interval * 3L))
                            throw new EndpointFailure("飞书心跳响应超时");
                    }
                }
                done.get();
            } finally {
                opening.cancel(true);
            }
        }

        @Override
        public synchronized void onOpen(WebSocket ws) {
            if (closed) {
                ws.abort();
                return;
            }
            socket = ws;
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            try {
                if (data.remaining() > FeishuCardFrames.MAX_BYTES - message.size())
                    throw new IOException("飞书数据帧超出长度限制");
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                message.write(bytes);
                if (last) {
                    Frame f = Frame.parseFrom(message.toByteArray());
                    message.reset();
                    if (f.getMethod() == 1) {
                        try {
                            handlers.execute(() -> process(ws, f));
                        } catch (RejectedExecutionException full) {
                            send(ws, frames.error(f));
                        }
                    } else if ("pong".equals(FeishuCardFrames.header(f, "type"))) {
                        lastPong = System.nanoTime();
                        if (!f.getPayload().isEmpty())
                            schedule.update(json.readTree(f.getPayload().newInput()));
                    }
                }
                ws.request(1);
            } catch (Exception e) {
                done.completeExceptionally(e);
                ws.abort();
            }
            return null;
        }

        private void process(WebSocket ws, Frame frame) {
            try {
                if (closed) return;
                var reply = frames.reply(frame);
                if (reply != null && !closed) send(ws, reply);
            } catch (Exception e) {
                done.completeExceptionally(e);
                ws.abort();
            }
        }

        // JDK WebSocket 同时仅允许一条数据帧处于写入中，心跳与业务 ACK 共用写入锁。
        private synchronized void send(WebSocket ws, Frame frame) throws Exception {
            ws.sendBinary(ByteBuffer.wrap(frame.toByteArray()), true)
                    .get(feishu.getRequestTimeoutSeconds(), TimeUnit.SECONDS);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            done.completeExceptionally(new EndpointFailure("WebSocket 已关闭，状态码 " + status));
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            done.completeExceptionally(error);
        }

        synchronized void close() {
            closed = true;
            if (socket != null) socket.abort();
        }
    }
}
