package com.company.policy.service.impl;

import com.company.policy.common.config.AttachmentProperties;
import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.PolicyDraftRequest.Link;
import com.company.policy.mapper.PolicyAttachmentMapper;
import com.company.policy.mapper.PolicyAttachmentMapper.FileReference;
import com.company.policy.mapper.PolicyAttachmentMapper.Membership;
import com.company.policy.mapper.UserInfoMapper;
import com.company.policy.service.AttachmentService;
import com.company.policy.service.CatalogAccess;
import com.company.policy.support.policy.PolicyFormValidation;
import com.company.policy.vo.AttachmentResponse;
import com.company.policy.vo.CurrentUserResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipFile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
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
    private final PolicyAttachmentMapper policies;
    private final CatalogAccess access;

    public AttachmentServiceImpl(
            AttachmentProperties properties,
            ObjectMapper json,
            Clock clock,
            UserInfoMapper users,
            PolicyAttachmentMapper policies,
            CatalogAccess access) {
        this.root = properties.rootPath().resolve("attachments");
        this.json = json;
        this.clock = clock;
        this.users = users;
        this.policies = policies;
        this.access = access;
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
        return content(id, saved);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Link> bind(List<Link> attachments, CurrentUserResponse user) {
        var items = PolicyFormValidation.entries(attachments);
        var result = new ArrayList<Link>();
        if (items.stream().anyMatch(item -> "FILE".equals(item.type()))) {
            lockOwner(user);
        }
        for (var item : items) {
            if ("FILE".equals(item.type())) {
                result.add(ownedFile(item.id(), user));
            } else {
                result.add(item);
            }
        }
        return result;
    }

    @Override
    public List<Link> resolveChangeFiles(List<Link> files, CurrentUserResponse user) {
        var listed = PolicyFormValidation.entries(files);
        if (listed.size() > 10) {
            throw PolicyFormValidation.invalid("每项期限变更最多上传 10 个文件。");
        }
        var ids = new HashSet<String>();
        var result = new ArrayList<Link>();
        for (var item : listed) {
            if (!"FILE".equals(item.type())
                    || item.id() == null
                    || item.url() != null
                    || !ids.add(item.id())) {
                throw PolicyFormValidation.invalid("变更附件仅支持已上传的文件，每个文件只能添加一次。");
            }
            result.add(ownedFile(item.id(), user));
        }
        return List.copyOf(result);
    }

    private Link ownedFile(String id, CurrentUserResponse user) {
        var saved = authorized(id, user);
        if (!Files.isRegularFile(root.resolve(id).resolve("content"), LinkOption.NOFOLLOW_LINKS)) {
            throw notFound();
        }
        return new Link("FILE", null, id, saved.attachment().originalName());
    }

    @Override
    @Transactional(readOnly = true)
    public Download downloadCurrent(long policyId, String id, CurrentUserResponse user) {
        var owner = access.requireApplicant(user);
        var membership =
                policies.current(policyId, owner.getTenantKey(), owner.getId(), fileReference(id));
        if (membership == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "POLICY_NOT_FOUND", "未找到此申请。");
        }
        if (membership.auditId() == null
                && !"DRAFT".equals(membership.status())
                && !"REJECTED".equals(membership.status())) {
            throw new IllegalStateException("政策提交快照缺失。");
        }
        return policyContent(id, owner.getTenantKey(), membership);
    }

    @Override
    @Transactional(readOnly = true)
    public Download downloadApplicantHistory(
            long policyId, long logId, String id, CurrentUserResponse user) {
        var owner = access.requireApplicant(user);
        var membership =
                policies.applicantHistory(
                        policyId, logId, owner.getTenantKey(), owner.getId(), fileReference(id));
        if (membership == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "POLICY_NOT_FOUND", "未找到此申请。");
        }
        return historicalContent(id, owner.getTenantKey(), membership);
    }

    @Override
    @Transactional(readOnly = true)
    public Download downloadAdminHistory(
            long policyId, long logId, String id, CurrentUserResponse user) {
        var admin = access.requirePolicyAdmin(user);
        var membership =
                policies.adminHistory(policyId, logId, admin.getTenantKey(), fileReference(id));
        if (membership == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "POLICY_NOT_FOUND", "未找到已提交的政策。");
        }
        return historicalContent(id, admin.getTenantKey(), membership);
    }

    private Download historicalContent(String id, String tenantKey, Membership membership) {
        if (membership.auditId() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "POLICY_AUDIT_NOT_FOUND", "未找到该轮提交记录。");
        }
        return policyContent(id, tenantKey, membership);
    }

    private FileReference fileReference(String id) {
        try {
            var file = Map.of("type", "FILE", "id", id);
            return new FileReference(
                    json.writeValueAsString(file),
                    json.writeValueAsString(Map.of("attachmentInfo", List.of(file))));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法序列化政策附件引用。", error);
        }
    }

    private Download policyContent(String id, String tenantKey, Membership membership) {
        if (!membership.attached()) {
            throw notFound();
        }
        var saved = stored(id);
        if (!tenantKey.equals(saved.tenantKey())) {
            throw notFound();
        }
        return content(id, saved);
    }

    private Download content(String id, StoredAttachment saved) {
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
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(String id, CurrentUserResponse user) {
        lockOwner(user);
        authorized(id, user);
        Path directory = root.resolve(id);
        try {
            if (policies.attachmentReferenced(fileReference(id))) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "ATTACHMENT_REFERENCED",
                        "该文件已被政策引用，请从草稿移除后保存；历史附件将继续保留。");
            }
            Files.deleteIfExists(directory.resolve("content"));
            Files.delete(directory.resolve("metadata.json"));
            Files.delete(directory);
        } catch (IOException | DataAccessException error) {
            throw storageError();
        }
    }

    private void lockOwner(CurrentUserResponse user) {
        verifyUser(user);
        try {
            if (users.lockAttachmentOwner(user.tenantKey(), Long.parseLong(user.id())) == null) {
                throw notFound();
            }
        } catch (DataAccessException error) {
            throw storageError();
        }
    }

    private StoredAttachment authorized(String id, CurrentUserResponse user) {
        verifyUser(user);
        var saved = stored(id);
        if (!user.id().equals(saved.ownerId()) || !user.tenantKey().equals(saved.tenantKey())) {
            throw notFound();
        }
        return saved;
    }

    private StoredAttachment stored(String id) {
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
