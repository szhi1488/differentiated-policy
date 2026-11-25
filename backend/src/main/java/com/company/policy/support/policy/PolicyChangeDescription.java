package com.company.policy.support.policy;

import com.company.policy.vo.PolicyApplicability.Policy;
import com.company.policy.vo.PolicyApplicability.Product;
import com.company.policy.vo.PolicyApplicability.Selection;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    public static String sceneLabel(String code) {
        return SCENES.getOrDefault(code, code);
    }

    public static String verifyLabel(String code) {
        return VERIFICATION.getOrDefault(code, code);
    }

    public static String priceBaseLabel(String code) {
        return BASES.getOrDefault(code, code);
    }

    /** 同一段的原、拟执行名单出现同单不同轮时，单号旁补上轮次。 */
    public static Map<String, Set<Integer>> policyVersions(PolicyExecutionChange change) {
        var versions = new HashMap<String, Set<Integer>>();
        Stream.concat(change.before().stream(), change.after().stream())
                .forEach(
                        p ->
                                versions.computeIfAbsent(p.policyId(), id -> new HashSet<>())
                                        .add(p.submissionVersion()));
        return versions;
    }

    public static String policyName(Policy policy, Map<String, Set<Integer>> versions) {
        return policy.policyCode()
                + (versions.getOrDefault(policy.policyId(), Set.of()).size() > 1
                        ? " 第 " + policy.submissionVersion() + " 轮"
                        : "");
    }

    /** 以该段原、拟实际执行的单据及轮次区分沿用叠加和本轮拟议的叠加。 */
    public static String cardDecision(PolicyExecutionChange change) {
        if (change.after().isEmpty()) return "本段无拟执行政策";
        if (change.after().size() == 1) return "拟执行";
        return PolicyExecutionChange.sameMembers(change.before(), change.after())
                ? "继续叠加执行"
                : "拟叠加执行";
    }

    public static String cardPeriod(
            PolicyExecutionChange change, LocalDateTime from, LocalDateTime through) {
        String platform = change.scope().platformCode();
        return platformLabel(platform)
                + ("RETAIL".equals(change.perspective()) ? "零售" : "百亿")
                + " · "
                + TIME.format(from)
                + " 至 "
                + TIME.format(through);
    }

    /** 百亿按配置作决定，部分申报时在标题列实际申报的颜色，各单另列自身的商品范围。 */
    public static String cardScope(
            PolicyExecutionChange change,
            boolean wholeConfiguration,
            Set<String> ambiguousGoodsIds) {
        if (wholeConfiguration) return scopeName(change, "整组配置");
        if (change.scope().goodsId() != null)
            return identifyCardProduct(
                    scopeName(change), change.scope().goodsId().toString(), ambiguousGoodsIds);
        var policies = change.after().isEmpty() ? change.before() : change.after();
        String colors =
                policies.stream()
                        .flatMap(p -> p.selections().stream())
                        .flatMap(s -> s.products().stream())
                        .map(p -> identifyCardProduct(colorOrId(p), p.goodsId(), ambiguousGoodsIds))
                        .distinct()
                        .collect(Collectors.joining("、"));
        return scopeName(change, colors.isEmpty() ? "商品范围未记录" : colors);
    }

    private record ConfigurationColor(String spuId, String ram, String rom, String color) {}

    /** 在不同段可实际申报同色同配的不同商品，按本轮冻结的商品 ID 消歧。 */
    public static Set<String> ambiguousCardGoodsIds(List<PolicyExecutionChange> changes) {
        var firstIds = new HashMap<ConfigurationColor, String>();
        var ambiguous = new HashSet<String>();
        changes.stream()
                .flatMap(change -> change.after().stream())
                .flatMap(policy -> policy.selections().stream())
                .flatMap(row -> row.products().stream())
                .filter(
                        p ->
                                p.goodsId() != null
                                        && p.colorName() != null
                                        && !p.colorName().isBlank())
                .forEach(
                        p -> {
                            var color =
                                    new ConfigurationColor(
                                            p.spuId(), p.ram(), p.rom(), p.colorName());
                            var first = firstIds.putIfAbsent(color, p.goodsId());
                            if (first != null && !first.equals(p.goodsId())) {
                                ambiguous.add(first);
                                ambiguous.add(p.goodsId());
                            }
                        });
        return ambiguous;
    }

    private static String identifyCardProduct(
            String label, String goodsId, Set<String> ambiguousGoodsIds) {
        return goodsId != null && ambiguousGoodsIds.contains(goodsId)
                ? label + "（商品 ID " + goodsId + "）"
                : label;
    }

    public static String cardTerms(
            Policy policy,
            boolean retail,
            boolean wholeConfiguration,
            Set<String> ambiguousGoodsIds) {
        var termsBySelection =
                policy.selections().stream()
                        .map(
                                selection -> {
                                    String terms = subsidy(selection, true, wholeConfiguration);
                                    if (retail || wholeConfiguration) return terms;
                                    var products =
                                            selection.products().stream()
                                                    .map(
                                                            p ->
                                                                    identifyCardProduct(
                                                                            colorOrId(p),
                                                                            p.goodsId(),
                                                                            ambiguousGoodsIds))
                                                    .collect(Collectors.joining("、"));
                                    return products.isEmpty() ? terms : products + " · " + terms;
                                });
        if (wholeConfiguration) termsBySelection = termsBySelection.distinct();
        String rows = termsBySelection.collect(Collectors.joining("\n"));
        if (rows.isBlank()) rows = "补贴参数未记录";
        if (!retail) return rows;
        var terms = new ArrayList<String>();
        if (policy.sceneTypes() != null && !policy.sceneTypes().isEmpty())
            terms.add(
                    policy.sceneTypes().stream()
                            .map(PolicyChangeDescription::sceneLabel)
                            .collect(Collectors.joining("+")));
        String type = policy.verifyType();
        terms.add("核销类型：" + (type == null || type.isBlank() ? "未记录" : verifyLabel(type)));
        terms.add(rows);
        return String.join(" · ", terms);
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
        return scopeName(change, null);
    }

    private static String scopeName(PolicyExecutionChange change, String productRange) {
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
                    : "机型 "
                            + scope.spuId()
                            + " "
                            + scope.ram()
                            + "+"
                            + scope.rom()
                            + " "
                            + (productRange == null ? "整组配置" : productRange);
        }
        String model = product.modelName();
        if (model == null || model.isBlank()) model = product.goodsName();
        if (model == null || model.isBlank()) model = "机型 " + product.spuId();
        return Stream.of(
                        model,
                        product.edition(),
                        product.ram() + "+" + product.rom(),
                        productRange != null
                                ? productRange
                                : scope.goodsId() == null ? "整组配置" : colorOrId(product))
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
                                                    .map(PolicyChangeDescription::sceneLabel)
                                                    .collect(Collectors.joining("+")));
                                }
                                if (p.verifyType() != null) terms.add(verifyLabel(p.verifyType()));
                            }
                            for (var selection : p.selections()) {
                                String allocation = subsidy(selection, false, false);
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

    private static String subsidy(
            Selection selection, boolean forCard, boolean wholeConfiguration) {
        var plan = selection.subsidy();
        var fields = new ArrayList<String>();
        if (plan == null) {
            fields.add("补贴参数缺失");
        } else {
            if ("RATIO".equals(plan.subsidyMode()) && plan.ratio() != null) {
                String base = plan.priceBase() == null ? "基数未填写" : priceBaseLabel(plan.priceBase());
                String percent = decimal(new BigDecimal(plan.ratio())) + "%";
                fields.add(
                        forCard ? "比例补贴 " + base + " × " + percent : "按" + base + "的 " + percent);
            } else if ("FIXED".equals(plan.subsidyMode()) && plan.fixedAmount() != null) {
                fields.add(
                        (forCard ? "固定金额 " : "")
                                + decimal(new BigDecimal(plan.fixedAmount()))
                                + " 元/台");
            }
            if (plan.maxSubsidyAmount() != null)
                fields.add("上限 " + decimal(new BigDecimal(plan.maxSubsidyAmount())) + " 元");
            if (fields.isEmpty()) fields.add("补贴参数缺失");
        }
        if ("SPECIFIED".equals(selection.cardType())) {
            String label = wholeConfiguration ? "每件申报数量" : "数量";
            fields.add(
                    label
                            + (selection.quantity() == null
                                    ? "未填写"
                                    : " " + selection.quantity() + " 台"));
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
