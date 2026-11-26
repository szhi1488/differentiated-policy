package com.company.policy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import com.company.policy.service.LoginSessionService;
import com.company.policy.vo.AuthSessionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=",
            "feishu.app-id=test-app",
            "feishu.tenant-key=attachment-http",
            "feishu.app-secret=test-secret",
            "feishu.allowed-origins=http://localhost:5173",
            "jackyun.import-scheduling-enabled=false",
            "jackyun.clean-scheduling-enabled=false"
        })
class AttachmentHttpIntegrationTest {
    @TempDir static Path directory;
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @MockitoBean LoginSessionService sessions;
    @Autowired com.company.policy.service.UserInfoService accounts;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("policy.storage.root", () -> directory.toString());
    }

    @Test
    void realHttpAcceptsFilesAboveDefaultLimitAndReturns413AboveTwentyMiB() throws Exception {
        var user =
                accounts.saveVerifiedIdentity("attachment-http", "http-owner", null, "附件联调", null);
        when(sessions.requireLogin(any()))
                .thenReturn(new AuthSessionResponse(user, Instant.now().plusSeconds(600)));
        var client = HttpClient.newHttpClient();
        for (int size : new int[] {2 * 1024 * 1024, 20 * 1024 * 1024, 20 * 1024 * 1024 + 1}) {
            byte[] content = new byte[size];
            System.arraycopy("%PDF-1.7".getBytes(StandardCharsets.US_ASCII), 0, content, 0, 8);
            var body =
                    HttpRequest.BodyPublishers.concat(
                            HttpRequest.BodyPublishers.ofString(
                                    "--attachment-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"policy.pdf\"\r\nContent-Type: application/pdf\r\n\r\n"),
                            HttpRequest.BodyPublishers.ofByteArray(content),
                            HttpRequest.BodyPublishers.ofString("\r\n--attachment-boundary--\r\n"));
            var response =
                    client.send(
                            request("/api/attachments")
                                    .header(
                                            "Content-Type",
                                            "multipart/form-data; boundary=attachment-boundary")
                                    .POST(body)
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            if (size <= 20 * 1024 * 1024) {
                assertThat(response.statusCode()).isEqualTo(201);
                var metadata = json.readTree(response.body());
                assertThat(metadata.path("size").asInt()).isEqualTo(size);
                var download =
                        client.send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        "http://127.0.0.1:"
                                                                + port
                                                                + "/api/attachments/"
                                                                + metadata.path("id").asText()
                                                                + "/content"))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofByteArray());
                assertThat(download.statusCode()).isEqualTo(200);
                assertThat(download.body()).isEqualTo(content);
            } else {
                assertThat(response.statusCode()).isEqualTo(413);
                assertThat(json.readTree(response.body()).path("code").asText())
                        .isEqualTo("ATTACHMENT_TOO_LARGE");
            }
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Origin", "http://localhost:5173")
                .header("X-Policy-Request", "1");
    }
}
