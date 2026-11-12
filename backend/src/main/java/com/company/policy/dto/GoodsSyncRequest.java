package com.company.policy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

public record GoodsSyncRequest(
        @NotNull @Pattern(regexp = "OPPO手机|一加手机") @Schema(allowableValues = {"OPPO手机", "一加手机"})
                String brandName,
        @NotNull
                @Pattern(regexp = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                @Schema(description = "小写 UUID。同一操作重放必须使用相同键。")
                String idempotencyKey) {

    public record Control(@NotNull @PositiveOrZero Integer recordVersion) {}
}
