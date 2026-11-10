package com.company.policy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.company.policy.common.config.AttachmentProperties;
import com.company.policy.common.exception.GlobalExceptionHandler;
import com.company.policy.common.interceptor.LoginInterceptor;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.UserInfoMapper;
import com.company.policy.service.JsSdkSignatureService;
import com.company.policy.service.impl.AttachmentServiceImpl;
import com.company.policy.service.impl.LoginSessionServiceImpl;
import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.CurrentUserResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AttachmentControllerTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final UserInfoMapper users = mock(UserInfoMapper.class);
    private final Clock clock = Clock.systemUTC();
    private final LoginSessionServiceImpl sessions = new LoginSessionServiceImpl(clock);
    private MockMvc mvc;
    private AttachmentServiceImpl storage;
    private MockHttpSession owner;
    private UserInfo account;

    @BeforeEach
    void setup() {
        account = new UserInfo();
        account.setId(1L);
        account.setIsDeleted(0);
        when(users.findIdentity(anyString(), anyString())).thenReturn(account);
        var properties = new AttachmentProperties();
        properties.setRoot(directory.toString());
        storage = new AttachmentServiceImpl(properties, json, clock, users);
        mvc =
                MockMvcBuilders.standaloneSetup(new AttachmentController(storage, sessions))
                        .addInterceptors(
                                new LoginInterceptor(sessions, mock(JsSdkSignatureService.class)))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        owner = session("tenant", "open");
    }

    private MockHttpSession session(String tenant, String openId) {
        var request = new MockHttpServletRequest();
        sessions.establish(
                request,
                new AuthSessionResponse(
                        new CurrentUserResponse("1", openId, tenant, "测试用户", "APPLICANT"),
                        Instant.now().plusSeconds(600)));
        return (MockHttpSession) request.getSession();
    }

    private MockMultipartFile pdf(String name) {
        return new MockMultipartFile(
                "file", name, "application/pdf", "%PDF-1.7\n测试政策".getBytes(StandardCharsets.UTF_8));
    }

    private String upload(MockMultipartFile file) throws Exception {
        var result =
                mvc.perform(
                                multipart("/api/attachments")
                                        .file(file)
                                        .session(owner)
                                        .header("Origin", "http://localhost:5173")
                                        .header("X-Policy-Request", "1"))
                        .andExpect(status().isCreated())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.originalName").value(file.getOriginalFilename()))
                        .andExpect(jsonPath("$.ownerId").doesNotExist())
                        .andExpect(jsonPath("$.path").doesNotExist())
                        .andReturn();
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("id")
                .asText();
    }

    @Test
    void persistsUniqueFilesDownloadsAndDeletesWithOwnershipMetadata() throws Exception {
        var file = pdf("政策说明.pdf");
        String id = upload(file);
        String second = upload(file);
        assertThat(id).isNotEqualTo(second);
        assertThat(
                        Files.readAllBytes(
                                directory.resolve("attachments").resolve(id).resolve("content")))
                .isEqualTo(file.getBytes());
        mvc.perform(get("/api/attachments/{id}/content", id).session(owner))
                .andExpect(status().isOk())
                .andExpect(content().bytes(file.getBytes()))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(
                        header().string(
                                        "Content-Disposition",
                                        org.hamcrest.Matchers.containsString("attachment;")));
        // 使用同一目录创建新实例，验证重启后不依赖内存中的归属映射。
        var properties = new AttachmentProperties();
        properties.setRoot(directory.toString());
        var reopened = new AttachmentServiceImpl(properties, json, clock, users);
        var download =
                reopened.download(
                        id, new CurrentUserResponse("1", "open", "tenant", "测试用户", "APPLICANT"));
        try (var input = download.resource().getInputStream()) {
            assertThat(input.readAllBytes()).isEqualTo(file.getBytes());
        }
        mvc.perform(
                        delete("/api/attachments/{id}", id)
                                .session(owner)
                                .header("Origin", "http://localhost:5173")
                                .header("X-Policy-Request", "1"))
                .andExpect(status().isNoContent());
        assertThat(directory.resolve("attachments").resolve(id)).doesNotExist();
        mvc.perform(get("/api/attachments/{id}/content", id).session(owner))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsAnonymousUntrustedAndMissingFileRequests() throws Exception {
        mvc.perform(
                        multipart("/api/attachments")
                                .file(pdf("政策.pdf"))
                                .header("Origin", "http://localhost:5173")
                                .header("X-Policy-Request", "1"))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/attachments").file(pdf("政策.pdf")).session(owner))
                .andExpect(status().isForbidden());
        mvc.perform(
                        multipart("/api/attachments")
                                .session(owner)
                                .header("Origin", "http://localhost:5173")
                                .header("X-Policy-Request", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_INVALID"));
        assertThat(directory.resolve("attachments")).doesNotExist();
    }

    @Test
    void hidesOtherUsersAndTenantsAndRejectsDisabledAccounts() throws Exception {
        String id = upload(pdf("政策.pdf"));
        account.setId(2L);
        var otherRequest = new MockHttpServletRequest();
        sessions.establish(
                otherRequest,
                new AuthSessionResponse(
                        new CurrentUserResponse("2", "other", "tenant", "其他用户", "ADMIN"),
                        Instant.now().plusSeconds(600)));
        var other = (MockHttpSession) otherRequest.getSession();
        mvc.perform(get("/api/attachments/{id}/content", id).session(other))
                .andExpect(status().isNotFound());
        mvc.perform(
                        delete("/api/attachments/{id}", id)
                                .session(other)
                                .header("Origin", "http://localhost:5173")
                                .header("X-Policy-Request", "1"))
                .andExpect(status().isNotFound());
        account.setId(1L);
        mvc.perform(
                        get("/api/attachments/{id}/content", id)
                                .session(session("other-tenant", "open")))
                .andExpect(status().isNotFound());
        account.setIsDeleted(1);
        mvc.perform(get("/api/attachments/{id}/content", id).session(owner))
                .andExpect(status().isForbidden());
        mvc.perform(
                        multipart("/api/attachments")
                                .file(pdf("新政策.pdf"))
                                .session(owner)
                                .header("Origin", "http://localhost:5173")
                                .header("X-Policy-Request", "1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsInvalidNamesTypesEmptyAndOversizeFilesAndCleansTemporaryData() throws Exception {
        var invalid =
                new MockMultipartFile[] {
                    pdf("../政策.pdf"),
                    pdf("政策.html"),
                    new MockMultipartFile(
                            "file",
                            "政策.pdf",
                            "application/pdf",
                            "fake".getBytes(StandardCharsets.UTF_8)),
                    new MockMultipartFile("file", "政策.png", "image/png", new byte[0])
                };
        for (var file : invalid) {
            mvc.perform(
                            multipart("/api/attachments")
                                    .file(file)
                                    .session(owner)
                                    .header("Origin", "http://localhost:5173")
                                    .header("X-Policy-Request", "1"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(
                        multipart("/api/attachments")
                                .file(
                                        new MockMultipartFile(
                                                "file",
                                                "大文件.pdf",
                                                "application/pdf",
                                                new byte[20 * 1024 * 1024 + 1]))
                                .session(owner)
                                .header("Origin", "http://localhost:5173")
                                .header("X-Policy-Request", "1"))
                .andExpect(status().isPayloadTooLarge());
        try (var paths = Files.list(directory.resolve("attachments"))) {
            assertThat(paths.toList()).isEmpty();
        }
        mvc.perform(get("/api/attachments/{id}/content", "not-a-valid-id").session(owner))
                .andExpect(status().isNotFound());
    }

    @Test
    void validatesModernOfficePackageWithoutExtractingFiles() throws Exception {
        byte[] valid;
        try (var buffer = new ByteArrayOutputStream();
                var zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
            zip.closeEntry();
            zip.finish();
            valid = buffer.toByteArray();
        }
        upload(new MockMultipartFile("file", "政策.xlsx", "application/octet-stream", valid));
        mvc.perform(
                        multipart("/api/attachments")
                                .file(
                                        new MockMultipartFile(
                                                "file",
                                                "政策.docx",
                                                "application/octet-stream",
                                                valid))
                                .session(owner)
                                .header("Origin", "http://localhost:5173")
                                .header("X-Policy-Request", "1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsUnavailableWithoutExposingPathsWhenStorageCannotBeCreated() throws Exception {
        Files.writeString(directory.resolve("attachments"), "阻止创建同名目录");
        mvc.perform(
                        multipart("/api/attachments")
                                .file(pdf("政策.pdf"))
                                .session(owner)
                                .header("Origin", "http://localhost:5173")
                                .header("X-Policy-Request", "1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_STORAGE_UNAVAILABLE"))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        directory.toString()))));
    }
}
