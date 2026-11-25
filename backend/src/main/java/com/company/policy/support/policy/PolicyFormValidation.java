package com.company.policy.support.policy;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.dto.PolicyDraftRequest.Card;
import com.company.policy.dto.PolicyDraftRequest.GoodsForm;
import com.company.policy.dto.PolicyDraftRequest.Link;
import com.company.policy.dto.PolicyDraftRequest.PlatformAssignment;
import com.company.policy.dto.PolicyDraftRequest.SubsidyPlan;
import com.company.policy.dto.PolicyDraftRequest.Target;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

public final class PolicyFormValidation {
    public static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'+08:00'")
                    .withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern MEMORY = Pattern.compile("[1-9][0-9]{0,7}GB");
    private static final Pattern DECIMAL = Pattern.compile("[0-9]+(?:\\.[0-9]{1,2})?");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    public static final Set<String> RETAIL =
            Set.of(
                    "KUAISHOU",
                    "PDD",
                    "JD",
                    "DOUYIN",
                    "TMALL",
                    "TAOBAO",
                    "ZHAOSHANG",
                    "DEWU",
                    "UNICOM",
                    "GOV_ENTERPRISE");
    public static final Set<String> BILLION = Set.of("PDD", "TAOBAO", "UNICOM");
    public static final Set<String> VERIFY_TYPES = Set.of("SALES", "ACTIVATION");
    public static final Set<String> SCENES = Set.copyOf(PolicyScenes.CODES);
    private static final Set<String> BASES =
            Set.of(
                    "RETAIL_PRICE",
                    "POLICY_PRICE",
                    "HAND_PRICE",
                    "LOCAL_ACTUAL",
                    "MERCHANT_CONTROL");

    private PolicyFormValidation() {}

    public static PolicyDraftRequest validate(PolicyDraftRequest form, boolean submitting) {
        require(
                form.goodsForm() == null || form.goodsForm().timeAdjustment() == null,
                "请从调整政策时间入口办理原单改期。");
        boolean retail = "RETAIL".equals(form.perspective());
        require(retail || "BILLION".equals(form.perspective()), "请选择政策渠道。");
        var platforms = entries(form.selectedPlatforms());
        var allowed = retail ? RETAIL : BILLION;
        var platformSet = new HashSet<>(platforms);
        require(
                allowed.containsAll(platforms) && platformSet.size() == platforms.size(),
                "整单平台重复或不属于当前渠道。");
        require(!submitting || !platforms.isEmpty(), "请选择整单平台。");
        var selected = entries(form.sceneTypes());
        List<String> scenes = null;
        if (retail) {
            require(oneOf(form.verifyType(), VERIFY_TYPES), "请选择有效的零售核销类型。");
            require(
                    SCENES.containsAll(selected)
                            && new HashSet<>(selected).size() == selected.size(),
                    "请选择有效且不重复的涉及场景。");
            if (!selected.isEmpty()) {
                scenes = PolicyScenes.CODES.stream().filter(selected::contains).toList();
            }
            require(
                    !submitting
                            || (form.verifyType() != null
                                    && scenes != null
                                    && form.estimated() != null),
                    "请填写零售核销类型、场景和预估状态。");
        } else {
            require(
                    form.verifyType() == null
                            && form.sceneTypes() == null
                            && form.estimated() == null,
                    "百亿渠道的零售字段须留空。");
        }
        var start = time(form.startTime());
        var end = time(form.endTime());
        require(!submitting || (start != null && end != null), "请填写政策起止时间。");
        require(start == null || end == null || start.isBefore(end), "政策开始时间须早于结束时间。");

        var cards = entries(form.goodsForm() == null ? null : form.goodsForm().cards());
        require(!submitting || !cards.isEmpty(), "请至少选择一个适用商品。");
        var cardKeys = new HashSet<String>();
        var assignedGoods = new HashMap<GoodsPlatform, Boolean>();
        var specifiedGoods = new HashSet<Long>();
        var commonConfigurations = new HashSet<Configuration>();
        for (Card card : cards) {
            key(card.key(), cardKeys, "商品卡片标识无效或重复。");
            boolean common = "COMMON".equals(card.cardType());
            require(common || "SPECIFIED".equals(card.cardType()), "商品卡片类型无效。");
            validateCard(card, common, platformSet, assignedGoods, submitting);
            if (common) {
                require(
                        card.targets().stream()
                                .map(
                                        target ->
                                                new Configuration(
                                                        id(target.spuId()),
                                                        target.ram(),
                                                        target.rom()))
                                .distinct()
                                .allMatch(commonConfigurations::add),
                        "同一机型配置只能加入一张普通商品卡，请编辑已有卡片。");
            } else {
                require(
                        specifiedGoods.add(id(card.targets().get(0).goodsId())),
                        "同一指定商品只能有一张指定商品卡片。");
            }
        }
        var links = entries(form.attachmentInfo());
        require(
                links.stream().filter(link -> "LINK".equals(link.type())).count() <= 1,
                "当前申请表单支持一个外部附件链接。");
        require(
                links.stream().filter(link -> "FILE".equals(link.type())).count() <= 10,
                "每份申请最多添加 10 个文件。");
        var fileIds = new HashSet<String>();
        for (var link : links) {
            if ("FILE".equals(link.type())) {
                require(
                        link.id() != null && fileIds.add(link.id()) && link.url() == null,
                        "附件文件标识无效或重复。");
            } else {
                validateLink(link);
                require(link.id() == null && link.originalName() == null, "外部链接不能携带文件信息。");
            }
        }
        return new PolicyDraftRequest(
                null,
                form.perspective(),
                platforms,
                form.verifyType(),
                scenes,
                form.estimated(),
                form.startTime(),
                form.endTime(),
                text(form.specialRemark()),
                links,
                new GoodsForm(cards));
    }

    private static void validateCard(
            Card card,
            boolean common,
            Set<String> orderPlatforms,
            Map<GoodsPlatform, Boolean> assignedGoods,
            boolean submitting) {
        require(card.followsOrderPlatforms() != null, "请选择商品卡片的平台联动方式。");
        var targets = entries(card.targets());
        require(common || targets.size() <= 1, "指定适用机型每张卡最多选择一件商品。");
        require(!targets.isEmpty(), "商品卡片须至少选择一件商品。");
        var goods = new HashSet<Long>();
        for (Target target : targets) {
            id(target.spuId());
            require(
                    validMemory(target.ram()) && validMemory(target.rom()),
                    "商品配置须同时提供标准 RAM 和 ROM。");
            require(goods.add(id(target.goodsId())), "同一张商品卡片不能重复选择同一商品。");
        }
        var planKeys = new HashSet<String>();
        var assignments = entries(card.platformAssignments());
        var usedPlans = new HashSet<String>();
        assignments.forEach(assignment -> usedPlans.add(assignment.planKey()));
        for (SubsidyPlan plan : entries(card.subsidyPlans())) {
            key(plan.key(), planKeys, "补贴方案标识无效或重复。");
            validatePlan(plan, submitting && usedPlans.contains(plan.key()));
        }
        var assigned = new HashSet<String>();
        for (PlatformAssignment assignment : assignments) {
            String platform = assignment.platformCode();
            require(orderPlatforms.contains(platform) && assigned.add(platform), "卡片平台重复或不属于整单平台。");
            require(planKeys.contains(assignment.planKey()), "平台分配的补贴方案不在当前卡片中。");
            Integer quantity = assignment.quantity();
            require(quantity == null || (!common && quantity >= 0), "数量仅在指定商品中选填，须为非负整数。");
            for (long goodsId : goods) {
                var previousCommon =
                        assignedGoods.putIfAbsent(new GoodsPlatform(platform, goodsId), common);
                if (previousCommon != null) {
                    throw invalid(
                            previousCommon == common
                                    ? "同一平台重复选择了同一商品，请整理商品卡片。"
                                    : "同一平台的商品已同时选入普通卡和指定卡，请移除重复的指定卡，或将该配置改为仅用指定卡申报。");
                }
            }
        }
        require(
                !card.followsOrderPlatforms() || assigned.equals(orderPlatforms),
                "沿用整单平台的商品卡片须与整单平台一致。");
        require(!submitting || !assigned.isEmpty(), "请为每张商品卡片选择适用平台。");
    }

    private static void validatePlan(SubsidyPlan plan, boolean required) {
        if ("FIXED".equals(plan.subsidyMode())) {
            require(plan.ratio() == null && plan.priceBase() == null, "固定补贴方案的比例和计算基数须留空。");
            decimal(plan.fixedAmount(), 10, required, "固定补贴金额");
            decimal(plan.maxSubsidyAmount(), 10, false, "补贴上限");
        } else if ("RATIO".equals(plan.subsidyMode())) {
            require(
                    plan.fixedAmount() == null && plan.maxSubsidyAmount() == null,
                    "比例补贴方案的固定金额和上限须留空。");
            decimal(plan.ratio(), 5, required, "补贴比例");
            require(
                    oneOf(plan.priceBase(), BASES) && (!required || plan.priceBase() != null),
                    "请选择有效的补贴计算基数。");
        } else {
            require(false, "请选择补贴方式。");
        }
    }

    private static void decimal(String value, int precision, boolean required, String name) {
        require(!required || value != null, "请填写" + name + "。");
        if (value != null) {
            require(
                    value.length() <= precision + 1 && matches(DECIMAL, value),
                    name + "须为最多两位小数的非负十进制字符串。");
            var decimal = new BigDecimal(value);
            require(decimal.precision() - decimal.scale() <= precision - 2, name + "超出可保存的数值精度。");
        }
    }

    public static LocalDateTime time(String value) {
        if (value == null) {
            return null;
        }
        try {
            var result = LocalDateTime.parse(value, TIME);
            require(result.getYear() >= 1000 && result.getYear() <= 9999, "政策时间超出可保存的范围。");
            return result;
        } catch (java.time.format.DateTimeParseException error) {
            throw invalid("政策时间须精确到秒并使用 +08:00，例如 2026-11-10T09:00:00+08:00。");
        }
    }

    private static void validateLink(Link link) {
        require("LINK".equals(link.type()) && link.url() != null, "请选择有效的外部附件链接。");
        try {
            var uri = URI.create(link.url());
            require(
                    ("https".equalsIgnoreCase(uri.getScheme())
                                    || "http".equalsIgnoreCase(uri.getScheme()))
                            && uri.getHost() != null
                            && uri.getRawUserInfo() == null,
                    "附件链接须使用 HTTP 或 HTTPS，且不得包含登录信息。");
        } catch (IllegalArgumentException error) {
            throw invalid("附件链接格式无效。");
        }
    }

    /** 审核意见和改派原因统一清理首尾的 Unicode 空白，保留正文换行。 */
    public static String trimReviewText(String value) {
        return value == null ? "" : value.replaceAll("(?U)^\\s+|\\s+$", "");
    }

    private static String text(String value) {
        require(
                value == null
                        || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 65535,
                "特别备注超出可保存的长度。");
        return value == null || value.isBlank() ? null : value;
    }

    private static void key(String value, Set<String> keys, String message) {
        require(matches(KEY, value) && keys.add(value), message);
    }

    public static long id(String value) {
        try {
            require(value != null && value.matches("[1-9][0-9]{0,18}"), "商品和机型 ID 须为正整数字符串。");
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw invalid("商品或机型 ID 超出范围。");
        }
    }

    public static <T> List<T> entries(List<T> value) {
        if (value == null) {
            return List.of();
        }
        require(value.stream().noneMatch(Objects::isNull), "表单列表不能包含空项。");
        return value;
    }

    private static boolean oneOf(String value, Set<String> values) {
        return value == null || values.contains(value);
    }

    public static boolean validMemory(String value) {
        return matches(MEMORY, value);
    }

    private static boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw invalid(message);
        }
    }

    public static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "POLICY_VALIDATION_FAILED", message);
    }

    private record GoodsPlatform(String platform, long goodsId) {}

    private record Configuration(long spuId, String ram, String rom) {}
}
