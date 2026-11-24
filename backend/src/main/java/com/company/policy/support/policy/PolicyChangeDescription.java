package com.company.policy.support.policy;

import com.company.policy.vo.PolicyApplicability.Policy;
import com.company.policy.vo.PolicyApplicability.Product;
import com.company.policy.vo.PolicyApplicability.Selection;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 将同一份分流依据转为可供采购和产品运营核对的业务短句。 */
public final class PolicyChangeDescription {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Map<String, String> PLATFORMS =
            Map.of(
                    "JD",
                    "京东",
                    "PDD",
                    "拼多多",
                    "TAOBAO",
                    "淘宝",
                    "KUAISHOU",
                    "快手",
                    "DOUYIN",
                    "抖音",
                    "TMALL",
                    "天猫",
                    "ZHAOSHANG",
                    "招商",
                    "DEWU",
                    "得物",
                    "UNICOM",
                    "联通",
                    "GOV_ENTERPRISE",
                    "政企");
    private static final Map<String, String> SCENES =
            Map.of("NATIONAL_SUB", "国补", "LOCAL_SUB", "地补", "SELF_SUB", "自补");
    private static final Map<String, String> BASES =
            Map.of(
                    "RETAIL_PRICE",
                    "零售价",
                    "POLICY_PRICE",
                    "政策价",
                    "HAND_PRICE",
                    "到手价",
                    "LOCAL_ACTUAL",
                    "地补实际",
                    "MERCHANT_CONTROL",
                    "商家配控");
    private static final Map<String, String> VERIFICATION =
            Map.of("SALES", "销售维度", "ACTIVATION", "激活维度");

    private PolicyChangeDescription() {}

    public static String platformLabel(String code) {
        return PLATFORMS.getOrDefault(code, code);
    }

    /** 卡片先给时段和商品，再分别列出本轮冻结的原采用、拟采用名单。 */
    public static String cardScope(PolicyExecutionChange change) {
        String platform = change.scope().platformCode();
        return platformLabel(platform)
                + ("RETAIL".equals(change.perspective()) ? "零售" : "百亿")
                + " · "
                + TIME.format(change.from())
                + " 至 "
                + TIME.format(change.through())
                + "\n"
                + scopeName(change);
    }

    public static String cardPolicies(List<Policy> policies, PolicyExecutionChange change) {
        return policies.isEmpty() ? "无" : describePolicies(policies, change);
    }

    static String describe(PolicyExecutionChange change) {
        var scope = change.scope();
        String platform = platformLabel(scope.platformCode());
        String channel = "RETAIL".equals(change.perspective()) ? "零售" : "百亿";
        var parts = new ArrayList<String>();
        parts.add(platform + channel);
        parts.add(scopeName(change));
        parts.add(TIME.format(change.from()) + " 至 " + TIME.format(change.through()));
        if (!change.candidates().isEmpty())
            parts.add("该段有 " + change.candidates().size() + " 张候选政策");
        switch (change.kind()) {
            case UNCHANGED ->
                    parts.add(
                            change.retained().isEmpty()
                                    ? "该段无执行政策"
                                    : "沿用原单" + describePolicies(change.retained(), change));
            case ORDINARY_ADDITION ->
                    parts.add("新增普通政策" + describePolicies(change.added(), change));
            case STACK -> {
                if (!change.retained().isEmpty())
                    parts.add("保留" + describePolicies(change.retained(), change));
                parts.add("新增叠加" + describePolicies(change.added(), change));
            }
            case REPLACEMENT -> {
                parts.add(
                        "拟以"
                                + describePolicies(change.added(), change)
                                + "替换"
                                + describePolicies(change.removed(), change));
                if (!change.retained().isEmpty())
                    parts.add("继续与" + describePolicies(change.retained(), change) + "叠加");
            }
            case REMOVE_STACK -> {
                parts.add("取消与" + describePolicies(change.removed(), change) + "的叠加");
                parts.add("保留" + describePolicies(change.retained(), change));
            }
            case REMOVAL -> parts.add("结束" + describePolicies(change.removed(), change));
        }
        var sum = fixedTotal(change.after());
        if (sum != null) parts.add("拟执行固定补贴合计 " + decimal(sum) + " 元/台");
        return String.join("，", parts) + "。";
    }

    private static String scopeName(PolicyExecutionChange change) {
        var scope = change.scope();
        var product =
                Stream.concat(change.after().stream(), change.before().stream())
                        .flatMap(p -> p.selections().stream())
                        .flatMap(s -> s.products().stream())
                        .filter(
                                p ->
                                        scope.goodsId() == null
                                                || scope.goodsId().toString().equals(p.goodsId()))
                        .findFirst()
                        .orElse(null);
        if (product == null) {
            return scope.goodsId() != null
                    ? "商品 " + scope.goodsId()
                    : "机型 " + scope.spuId() + " " + scope.ram() + "+" + scope.rom() + " 整组配置";
        }
        String model = product.modelName();
        if (model == null || model.isBlank()) model = product.goodsName();
        if (model == null || model.isBlank()) model = "机型 " + product.spuId();
        return Stream.of(
                        model,
                        product.edition(),
                        product.ram() + "+" + product.rom(),
                        scope.goodsId() == null ? "整组配置" : colorOrId(product))
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(" "));
    }

    private static String colorOrId(Product product) {
        return product.colorName() == null || product.colorName().isBlank()
                ? "商品 " + product.goodsId()
                : product.colorName();
    }

    private static String describePolicies(List<Policy> policies, PolicyExecutionChange change) {
        return policies.stream()
                .map(
                        p -> {
                            var terms = new ArrayList<String>();
                            if ("RETAIL".equals(change.perspective())) {
                                if (p.sceneTypes() != null && !p.sceneTypes().isEmpty()) {
                                    terms.add(
                                            p.sceneTypes().stream()
                                                    .map(s -> SCENES.getOrDefault(s, s))
                                                    .collect(Collectors.joining("+")));
                                }
                                if (p.verifyType() != null)
                                    terms.add(
                                            VERIFICATION.getOrDefault(
                                                    p.verifyType(), p.verifyType()));
                            }
                            for (var selection : p.selections()) {
                                String allocation = subsidy(selection);
                                if ("BILLION".equals(change.perspective())) {
                                    // 配置审核跨颜色，每条补贴仍要能回到这份原单实际申报的商品。
                                    String products =
                                            selection.products().stream()
                                                    .map(PolicyChangeDescription::colorOrId)
                                                    .collect(Collectors.joining("、"));
                                    allocation = "申报" + products + " " + allocation;
                                }
                                terms.add(allocation);
                            }
                            String code = p.policyCode();
                            boolean otherRound =
                                    Stream.concat(
                                                    change.added().stream(),
                                                    change.removed().stream())
                                            .anyMatch(
                                                    other ->
                                                            p.policyId().equals(other.policyId())
                                                                    && p.submissionVersion()
                                                                            != other
                                                                                    .submissionVersion());
                            if (otherRound) code += "第 " + p.submissionVersion() + " 轮";
                            return code + "（" + String.join("，", terms) + "）";
                        })
                .collect(Collectors.joining("、"));
    }

    private static String subsidy(Selection selection) {
        var plan = selection.subsidy();
        var fields = new ArrayList<String>();
        if (plan == null) {
            fields.add("补贴参数缺失");
        } else {
            if ("RATIO".equals(plan.subsidyMode()) && plan.ratio() != null) {
                String base =
                        plan.priceBase() == null
                                ? "基数未填写"
                                : BASES.getOrDefault(plan.priceBase(), plan.priceBase());
                fields.add("按" + base + "的 " + decimal(new BigDecimal(plan.ratio())) + "%");
            } else if ("FIXED".equals(plan.subsidyMode()) && plan.fixedAmount() != null) {
                fields.add(decimal(new BigDecimal(plan.fixedAmount())) + " 元/台");
            }
            if (plan.maxSubsidyAmount() != null)
                fields.add("上限 " + decimal(new BigDecimal(plan.maxSubsidyAmount())) + " 元");
            if (fields.isEmpty()) fields.add("补贴参数缺失");
        }
        if ("SPECIFIED".equals(selection.cardType())) {
            fields.add(
                    selection.quantity() == null ? "数量未填写" : "数量 " + selection.quantity() + " 台");
        }
        return String.join("，", fields);
    }

    private static String decimal(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    /** 各单须为相同商品、核销和数量口径的一份固定额，且上限足以覆盖合计。 */
    private static BigDecimal fixedTotal(List<Policy> policies) {
        if (policies.size() < 2) return null;
        Selection basis = null;
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal lowestCap = null;
        for (var policy : policies) {
            if (policy.selections().size() != 1
                    || !Objects.equals(policy.verifyType(), policies.get(0).verifyType())
                    || !Objects.equals(policy.sceneTypes(), policies.get(0).sceneTypes()))
                return null;
            var selection = policy.selections().get(0);
            if (basis != null
                    && (!Objects.equals(selection.quantity(), basis.quantity())
                            || !selection.cardType().equals(basis.cardType())
                            || !selection.products().equals(basis.products()))) return null;
            basis = selection;
            var plan = selection.subsidy();
            if (plan == null || !"FIXED".equals(plan.subsidyMode()) || plan.fixedAmount() == null)
                return null;
            sum = sum.add(new BigDecimal(plan.fixedAmount()));
            if (plan.maxSubsidyAmount() != null) {
                var cap = new BigDecimal(plan.maxSubsidyAmount());
                lowestCap = lowestCap == null ? cap : lowestCap.min(cap);
            }
        }
        return lowestCap == null || lowestCap.compareTo(sum) >= 0 ? sum : null;
    }
}
