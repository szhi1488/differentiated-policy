package com.company.policy.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "已上传附件；仅返回随机标识和展示信息，不暴露磁盘路径。尚未绑定政策。")
public record AttachmentResponse(
        String id, String originalName, long size, String contentType, Instant uploadedAt) {}
