package com.company.policy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;

public record GoodsSyncRequest(
        @NotNull @Pattern(regexp = "OPPO手机|一加手机") @Schema(allowableValues = {"OPPO手机", "一加手机"})
                String brandName,
        @NotNull
                @Pattern(regexp = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                @Schema(description = "小写 UUID。同一操作重放必须使用相同键。")
                String idempotencyKey,
        @Schema(description = "FULL 全量、CREATED 按建档日、MODIFIED 按变更日") Mode mode,
        @Schema(description = "按日导入必填，北京时间 YYYY-MM-DD") LocalDate date) {

    public GoodsSyncRequest {
        if (mode == null) {
            mode = Mode.FULL;
        }
    }

    public enum Mode {
        FULL,
        CREATED,
        MODIFIED
    }

    public record Control(@NotNull @PositiveOrZero Integer recordVersion) {}
}
