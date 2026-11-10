package com.company.policy.service.impl;

import com.company.policy.common.config.AttachmentProperties;
import com.company.policy.common.exception.ApiException;
import com.company.policy.mapper.UserInfoMapper;
import com.company.policy.service.AttachmentService;
import com.company.policy.vo.AttachmentResponse;
import com.company.policy.vo.CurrentUserResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipFile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AttachmentServiceImpl implements AttachmentService {
    private static final long MAX_SIZE = 20L * 1024 * 1024;
    private static final Map<String, String> TYPES =
            Map.of(
                    "pdf",
                    "application/pdf",
                    "doc",
                    "application/msword",
                    "docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "xls",
                    "application/vnd.ms-excel",
                    "xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "png",
                    "image/png",
                    "jpg",
                    "image/jpeg",
                    "jpeg",
                    "image/jpeg");
    private final Path root;
    private final ObjectMapper json;
    private final Clock clock;
    private final UserInfoMapper users;

    public AttachmentServiceImpl(
            AttachmentProperties properties, ObjectMapper json, Clock clock, UserInfoMapper users) {
        this.root = properties.rootPath().resolve("attachments");
        this.json = json;
        this.clock = clock;
        this.users = users;
    }

    @Override
    public AttachmentResponse upload(MultipartFile file, CurrentUserResponse user) {
        verifyUser(user);
        String name = file.getOriginalFilename();
        if (name == null
                || name.isBlank()
                || name.length() > 180
                || name.matches(".*[\\\\/:*?\"<>|].*")
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("文件名无效，请使用不超过 180 个字符的普通文件名。");
        }
        int dot = name.lastIndexOf('.');
        String extension = dot < 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!TYPES.containsKey(extension)) {
            throw invalid("仅支持 PDF、Word、Excel、PNG 和 JPG 文件。");
        }
        if (file.isEmpty()) {
            throw invalid("不能上传空文件。");
        }
        if (file.getSize() > MAX_SIZE) {
            throw tooLarge();
        }
        String id = UUID.randomUUID().toString();
        Path temporary = root.resolve(".upload-" + id);
        try {
            Files.createDirectories(root);
            Files.createDirectory(temporary);
            Path content = temporary.resolve("content");
            // 不依赖客户端声明的大小，以实际读取字节数限制磁盘写入。
            long size = 0;
            try (var input = file.getInputStream();
                    var output = Files.newOutputStream(content)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    size += length;
                    if (size > MAX_SIZE) {
                        throw tooLarge();
                    }
                    output.write(buffer, 0, length);
                }
            }
            if (size == 0) {
                throw invalid("不能上传空文件。");
            }
            validateContent(content, extension);
            var attachment =
                    new AttachmentResponse(id, name, size, TYPES.get(extension), clock.instant());
            json.writeValue(
                    temporary.resolve("metadata.json").toFile(),
                    new StoredAttachment(user.id(), user.tenantKey(), attachment));
            // 内容和归属元数据都写完后才对下载接口可见；不覆盖同名目录。
            Files.move(temporary, root.resolve(id));
            return attachment;
        } catch (IOException error) {
            throw storageError();
        } finally {
            cleanup(temporary);
        }
    }

    @Override
    public Download download(String id, CurrentUserResponse user) {
        var saved = authorized(id, user);
        try {
            Path content = root.resolve(id).resolve("content");
            if (!Files.isRegularFile(content, LinkOption.NOFOLLOW_LINKS)) {
                throw notFound();
            }
            return new Download(
                    saved.attachment(), new InputStreamResource(Files.newInputStream(content)));
        } catch (IOException error) {
            throw storageError();
        }
    }

    @Override
    public synchronized void delete(String id, CurrentUserResponse user) {
        authorized(id, user);
        Path directory = root.resolve(id);
        try {
            Files.deleteIfExists(directory.resolve("content"));
            Files.delete(directory.resolve("metadata.json"));
            Files.delete(directory);
        } catch (IOException error) {
            throw storageError();
        }
    }

    private StoredAttachment authorized(String id, CurrentUserResponse user) {
        verifyUser(user);
        if (id == null
                || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw notFound();
        }
        Path directory = root.resolve(id);
        Path metadata = directory.resolve("metadata.json");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)) {
            throw notFound();
        }
        try {
            if (Files.size(metadata) > 16384) {
                throw storageError();
            }
            var saved = json.readValue(metadata.toFile(), StoredAttachment.class);
            if (!user.id().equals(saved.ownerId()) || !user.tenantKey().equals(saved.tenantKey())) {
                throw notFound();
            }
            if (saved.attachment() == null || !id.equals(saved.attachment().id())) {
                throw storageError();
            }
            return saved;
        } catch (IOException error) {
            throw storageError();
        }
    }

    private void verifyUser(CurrentUserResponse user) {
        try {
            // 每次读取当前账号，旧会话不能绕过停用或租户/身份变化。
            var current = users.findIdentity(user.tenantKey(), user.openId());
            if (current == null
                    || !Integer.valueOf(0).equals(current.getIsDeleted())
                    || !user.id().equals(String.valueOf(current.getId()))) {
                throw new ApiException(
                        HttpStatus.FORBIDDEN, "ATTACHMENT_ACCESS_DENIED", "账号不可用，请重新登录。");
            }
        } catch (DataAccessException error) {
            throw storageError();
        }
    }

    private void validateContent(Path file, String extension) throws IOException {
        byte[] head;
        try (InputStream input = Files.newInputStream(file)) {
            head = input.readNBytes(8);
        }
        boolean valid =
                switch (extension) {
                    case "pdf" -> startsWith(head, new byte[] {0x25, 0x50, 0x44, 0x46, 0x2d});
                    case "png" ->
                            startsWith(
                                    head,
                                    new byte[] {
                                        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
                                    });
                    case "jpg", "jpeg" ->
                            startsWith(head, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff});
                    case "doc", "xls" ->
                            startsWith(
                                    head,
                                    new byte[] {
                                        (byte) 0xd0,
                                        (byte) 0xcf,
                                        0x11,
                                        (byte) 0xe0,
                                        (byte) 0xa1,
                                        (byte) 0xb1,
                                        0x1a,
                                        (byte) 0xe1
                                    });
                    case "docx", "xlsx" -> validOfficePackage(file, extension);
                    default -> false;
                };
        if (!valid) {
            throw invalid("文件内容与扩展名不符，或文件已损坏。");
        }
    }

    private boolean validOfficePackage(Path file, String extension) {
        // 只读取 ZIP 目录，不解压、不执行内容；不允许宏 Office 包伪装为普通文档。
        try (var zip = new ZipFile(file.toFile())) {
            String document = extension.equals("docx") ? "word/document.xml" : "xl/workbook.xml";
            return zip.getEntry("[Content_Types].xml") != null
                    && zip.getEntry(document) != null
                    && zip.stream()
                            .noneMatch(
                                    entry ->
                                            entry.getName()
                                                    .toLowerCase(Locale.ROOT)
                                                    .endsWith("vbaproject.bin"));
        } catch (IOException error) {
            return false;
        }
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        return value.length >= prefix.length
                && Arrays.equals(Arrays.copyOf(value, prefix.length), prefix);
    }

    private void cleanup(Path temporary) {
        try {
            Files.deleteIfExists(temporary.resolve("content"));
            Files.deleteIfExists(temporary.resolve("metadata.json"));
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // 不掩盖原始结果；异常中断遗留的 .upload-* 目录不对外提供访问。
        }
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_INVALID", message);
    }

    private ApiException tooLarge() {
        return new ApiException(
                HttpStatus.PAYLOAD_TOO_LARGE, "ATTACHMENT_TOO_LARGE", "单个附件不能超过 20MB。");
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "附件不存在或无权访问。");
    }

    private ApiException storageError() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "ATTACHMENT_STORAGE_UNAVAILABLE",
                "附件存储暂时不可用，请稍后重试或联系管理员。");
    }

    private record StoredAttachment(
            String ownerId, String tenantKey, AttachmentResponse attachment) {}
}
