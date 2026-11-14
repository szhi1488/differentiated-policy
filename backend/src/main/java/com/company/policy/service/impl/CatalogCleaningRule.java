package com.company.policy.service.impl;

import static com.company.policy.service.impl.CatalogText.normalize;

import com.company.policy.dto.CatalogCleaningRequest.Target;
import com.company.policy.entity.SourceGoodsItem;
import com.company.policy.vo.CatalogCleaningResponse.Candidate;
import com.company.policy.vo.CatalogCleaningResponse.Evidence;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class CatalogCleaningRule {
    static final String EXCLUSION = "RULE_EXCLUSION";
    private static final Pattern SKU =
            Pattern.compile(
                    "([1-9][0-9]?)\\s*(?:GB?)?\\s*\\+\\s*([1-9][0-9]{0,7})\\s*(TB?|GB?)?",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern SPEC_IN_NAME =
            Pattern.compile(
                    "(?<![0-9])" + SKU.pattern() + "(?![0-9A-Z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern LITE = Pattern.compile("\\(简装(?:版)?\\)|简装版|简装");
    private static final Pattern ACCESSORY =
            Pattern.compile("(?i)(^|[/>|])\\s*IOT\\s*&\\s*配件\\s*($|[/>|])");
    private static final Pattern SPECIAL =
            Pattern.compile(
                    "大地探索家卫星通信套装版|卫星通信版|探索家卫星版|卫星版|简易演示样机|全套演示样机"
                            + "|简易样机|全套样机|演示样机|展示机|样机|原神定制版|限定版|定制版|典藏版"
                            + "|套装版|套装|礼盒|移动版|运营商版|国际版|海外版|港版|【[A-Z]】|简装版|简装",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern COLOR = Pattern.compile("[\\p{IsHan}]{1,16}");
    private static final Pattern COLOR_BEFORE_SPEC =
            Pattern.compile(
                    "(?:^|[\\s(]|(?<=[A-Za-z0-9]))([\\p{IsHan}]{1,16}(?:\\s*[/、|]\\s*[\\p{IsHan}]{1,16})*)\\s*\\)?$");
    private static final Pattern RENO17_TYPO =
            Pattern.compile(
                    "^Reon *17(?=$|\\s|[1-9][0-9]?\\s*(?:GB?)?\\s*\\+|Pro(?:\\s|$))",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_COLOR =
            Pattern.compile("手机|系列|版本|定制|限定|样机|套装|简装|标配|默认|无颜色|未知|随机|原装|配件|活力版|全新|裸机|官换|翻新|代发");

    private CatalogCleaningRule() {}

    static Candidate parse(SourceGoodsItem source) {
        String brand = source.getBrandName();
        String rawName = normalize(source.getGoodsName());
        String rawSku = normalize(source.getSkuName());
        String category = normalize(source.getCateName());
        String series = normalize(source.getGoodsField4());
        String field1 = normalize(source.getGoodsField1());
        List<Evidence> evidence = new ArrayList<>();
        evidence.add(new Evidence("goodsField4", source.getGoodsField4()));
        evidence.add(new Evidence("cateName", source.getCateName()));
        String path = normalize(source.getCateFullName());
        if (!path.isEmpty()) {
            evidence.add(new Evidence("cateFullName", source.getCateFullName()));
        }
        String[] segments = path.split("/", -1);
        boolean structuredPath =
                segments.length == 3 && validSeries(segments[1].strip()) && !segments[2].isBlank();
        String pathSeries = structuredPath ? segments[1].strip() : "";
        String pathModel = structuredPath ? modelName(segments[2].strip(), brand) : "";
        boolean usablePath = structuredPath && brand.equals(segments[0].strip());
        var names = displayNames(rawName, rawSku, field1);

        String model = modelName(category, brand);
        if (model.isEmpty() && usablePath) {
            model = pathModel;
        }
        Matcher sku = SKU.matcher(LITE.matcher(rawSku).replaceAll("").strip());
        String spec = sku.matches() ? spec(sku) : null;
        String name = modelName(rawName, brand);
        String nameTail = afterModel(name, model);
        boolean k10 = "K10活力版".equals(model.replace(" ", ""));
        // 同一型号后缀在分类和商品名中的位置可能不同。
        if (nameTail == null && k10 && name.endsWith("-活力版")) {
            nameTail = afterModel(name.substring(0, name.length() - "-活力版".length()), "K10");
        }
        String title = nameTail == null ? name : nameTail;
        List<MatchResult> nameSpecs = SPEC_IN_NAME.matcher(title).results().limit(2).toList();
        var titleColor = titleColor(title, nameTail != null, nameSpecs, field1, rawSku);
        String reason =
                exclusion(rawName, category + " " + path, series, series.isEmpty() && usablePath);
        if (Boolean.TRUE.equals(source.getIsPackageGood())) {
            evidence.add(new Evidence("isPackageGood", "true"));
            reason = "吉客云标记为组合商品。";
        }
        if (Boolean.TRUE.equals(source.getIsBlockup())) {
            evidence.add(new Evidence("isBlockup", "true"));
            reason = "吉客云标记 SKU 已停用。";
        }
        if (reason != null) {
            if (rawName.contains("工厂代发")) {
                evidence.add(new Evidence("goodsName", "工厂代发"));
            }
            if (!titleColor.candidates().isEmpty() && !rawName.contains("工厂代发")) {
                evidence.add(new Evidence("goodsName", source.getGoodsName()));
            }
            if (!titleColor.candidates().isEmpty()) {
                evidence.add(new Evidence("skuName", source.getSkuName()));
            }
            return result(
                    null, null, null, titleColor, names, EXCLUSION, reason, "EXCLUDE", evidence);
        }

        evidence.add(new Evidence("skuName", source.getSkuName()));
        evidence.add(new Evidence("goodsName", source.getGoodsName()));
        if (structuredPath
                && (!usablePath
                        || (!series.isEmpty() && !series.equals(pathSeries))
                        || (!model.isEmpty() && !model.equalsIgnoreCase(pathModel)))) {
            return review(
                    series,
                    model,
                    spec,
                    titleColor,
                    names,
                    "CATEGORY_PATH_CONFLICT",
                    "完整分类路径与品牌、系列或型号不一致，需要核对。",
                    evidence);
        }
        if (series.isEmpty() && usablePath) {
            return review(
                    pathSeries,
                    model,
                    spec,
                    titleColor,
                    names,
                    "SERIES_FROM_PATH",
                    "自定义字段 4 为空，已从完整分类路径提取系列候选。",
                    evidence);
        }
        if (model.isEmpty() || model.length() > 64 || series.length() > 64 || nameTail == null) {
            return review(
                    series,
                    model,
                    spec,
                    titleColor,
                    names,
                    "MODEL_UNCERTAIN",
                    "分类和商品名中的完整型号需要核对。",
                    evidence);
        }
        if (spec == null || memory(spec) == null || nameSpecs.size() != 1) {
            return review(
                    series,
                    model,
                    spec,
                    titleColor,
                    names,
                    "SPEC_UNCERTAIN",
                    "SKU 名或商品名中的配置无法唯一识别。",
                    evidence);
        }
        if (!sameMemory(spec, spec(nameSpecs.get(0)))) {
            return review(
                    series,
                    model,
                    spec,
                    titleColor,
                    names,
                    "SPEC_CONFLICT",
                    "SKU 名和商品名中的配置不一致。",
                    evidence);
        }

        String policyName = modelName(field1, brand);
        boolean alias = "OPPO手机".equals(brand) && RENO17_TYPO.matcher(policyName).find();
        if (alias) {
            policyName = RENO17_TYPO.matcher(policyName).replaceFirst("Reno17");
        }
        if (!field1.isEmpty()) {
            evidence.add(new Evidence("goodsField1", source.getGoodsField1()));
            String policyTail = afterModel(policyName, model);
            if (policyTail == null && !k10) {
                return review(
                        series,
                        model,
                        spec,
                        titleColor,
                        names,
                        "MODEL_EVIDENCE_CONFLICT",
                        "自定义字段 1 的型号写法需要核对。",
                        evidence);
            }
            Matcher policySpec = SPEC_IN_NAME.matcher(policyTail == null ? field1 : policyTail);
            boolean hasPolicySpec = alias ? policySpec.lookingAt() : policySpec.find();
            if (alias && !hasPolicySpec) {
                return review(
                        series,
                        model,
                        spec,
                        titleColor,
                        names,
                        "MODEL_EVIDENCE_CONFLICT",
                        "自定义字段 1 的别名需与完整型号和配置一致。",
                        evidence);
            }
            if (hasPolicySpec
                    && (!sameMemory(spec, spec(policySpec)) || (alias && policySpec.find()))) {
                return review(
                        series,
                        model,
                        spec,
                        titleColor,
                        names,
                        "SPEC_CONFLICT",
                        "自定义字段 1 和 SKU 名的配置不一致。",
                        evidence);
            }
        }

        if (titleColor.edition() != null) {
            return review(
                    series,
                    model,
                    spec,
                    titleColor,
                    names,
                    "SPECIAL_EDITION",
                    "特殊版本的政策适用范围需要复核。",
                    evidence);
        }
        if (titleColor.confirmed() == null) {
            String message =
                    titleColor.candidates().size() > 1
                            ? "发现多个颜色候选，请确认其中一个。"
                            : titleColor.candidates().isEmpty() ? "原文中没有可识别的颜色。" : "已找到颜色候选，请确认。";
            return review(
                    series, model, spec, titleColor, names, "COLOR_UNCERTAIN", message, evidence);
        }
        String explanation =
                alias
                        ? "自定义字段 1 的 Reon17 已按 OPPO 别名归一为 Reno17，配置及颜色校验通过。"
                        : "商品名命中完整型号、唯一配置及颜色位置，SKU 配置一致。";
        return result(series, model, spec, titleColor, names, null, explanation, null, evidence);
    }

    private static ColorMatch titleColor(
            String title,
            boolean matchedModel,
            List<MatchResult> specs,
            String field1,
            String sku) {
        String before = specs.isEmpty() ? title : title.substring(0, specs.get(0).start()).strip();
        String after =
                specs.isEmpty() ? "" : title.substring(specs.get(specs.size() - 1).end()).strip();
        if (before.endsWith("(") && after.startsWith(")")) {
            before = before.substring(0, before.length() - 1).strip();
            after = after.substring(1).strip();
        }
        String edition =
                SPECIAL.matcher(before + " " + after + " " + field1 + " " + sku)
                        .results()
                        .map(MatchResult::group)
                        .findFirst()
                        .orElse(null);
        before = withoutEdition(before);
        after = withoutEdition(after);
        String confirmed =
                matchedModel && specs.size() == 1
                        ? color(before.isEmpty() ? after : after.isEmpty() ? before : "")
                        : null;
        if (confirmed != null) {
            return new ColorMatch(confirmed, List.of(confirmed), edition);
        }
        var candidates = new LinkedHashSet<String>();
        if (matchedModel) {
            addColors(candidates, before);
        } else if (!specs.isEmpty()) {
            var suffix = COLOR_BEFORE_SPEC.matcher(before);
            if (suffix.find()) {
                addColors(candidates, suffix.group(1));
            }
        } else if (before.endsWith(")") && before.lastIndexOf('(') >= 0) {
            addColors(candidates, before.substring(before.lastIndexOf('(')));
        }
        addColors(candidates, after);
        if (candidates.isEmpty()) {
            Matcher skuSpec = SPEC_IN_NAME.matcher(sku);
            if (skuSpec.lookingAt()) {
                addColors(candidates, withoutEdition(sku.substring(skuSpec.end())));
            }
        }
        return new ColorMatch(null, List.copyOf(candidates), edition);
    }

    private static void addColors(LinkedHashSet<String> candidates, String text) {
        String fragment = text.strip().replaceFirst("^\\(([^()]+)\\)$", "$1");
        for (String value : fragment.split("[/、|]")) {
            String candidate = color(value.strip());
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
    }

    private static String withoutEdition(String text) {
        return SPECIAL.matcher(text).replaceAll("").strip();
    }

    static Candidate manual(Target target, Candidate original) {
        String spec = normalize(target.spec());
        String edition = original.edition();
        if (edition != null && spec.endsWith(edition)) {
            spec = spec.substring(0, spec.length() - edition.length()).strip();
        }
        Matcher parsed = SKU.matcher(spec);
        spec = parsed.matches() ? spec(parsed) : spec;
        return new Candidate(
                normalize(target.seriesName()),
                normalize(target.modelName()),
                spec,
                normalize(target.colorName()),
                List.of(normalize(target.colorName())),
                original.edition(),
                original.displayGoodsName(),
                original.displaySkuName(),
                null,
                "管理员确认标准规格和颜色。",
                null,
                original.evidence());
    }

    static Candidate issue(Candidate candidate, String code, String explanation, String action) {
        return new Candidate(
                candidate.seriesName(),
                candidate.modelName(),
                candidate.spec(),
                candidate.colorName(),
                candidate.colorCandidates(),
                candidate.edition(),
                candidate.displayGoodsName(),
                candidate.displaySkuName(),
                code,
                explanation,
                action,
                candidate.evidence());
    }

    private static boolean validSeries(String series) {
        return series.endsWith("系列") && !series.equals("系列");
    }

    private static String exclusion(
            String name, String category, String series, boolean pathCandidate) {
        if (name.contains("工厂代发")) {
            return "商品名命中工厂代发。";
        }
        if (category.contains("组合装")) {
            return "分类命中组合装。";
        }
        if (ACCESSORY.matcher(category).find()) {
            return "分类命中 IOT&配件。";
        }
        if (series.replace(" ", "").contains("手机-一加(高成本折扣)")) {
            return "自定义字段 4 命中一加高成本折扣标签。";
        }
        return validSeries(series) || pathCandidate ? null : "自定义字段 4 须以系列结尾并包含系列名称。";
    }

    private static Candidate review(
            String series,
            String model,
            String spec,
            ColorMatch colors,
            Names names,
            String code,
            String reason,
            List<Evidence> evidence) {
        return result(series, model, spec, colors, names, code, reason, "REVIEW", evidence);
    }

    private static Candidate result(
            String series,
            String model,
            String spec,
            ColorMatch colors,
            Names names,
            String code,
            String reason,
            String action,
            List<Evidence> evidence) {
        String candidate = colors.candidates().size() == 1 ? colors.candidates().get(0) : null;
        return new Candidate(
                series,
                model,
                spec,
                candidate,
                colors.candidates(),
                colors.edition(),
                names.goods(),
                names.sku(),
                code,
                reason,
                action,
                List.copyOf(evidence));
    }

    private static Names displayNames(String name, String sku, String policyName) {
        boolean lite =
                LITE.matcher(name).find()
                        || LITE.matcher(sku).find()
                        || LITE.matcher(policyName).find();
        return lite
                ? new Names(
                        LITE.matcher(name).replaceAll("").strip() + "（简装版）",
                        LITE.matcher(sku).replaceAll("").strip())
                : new Names(name, sku);
    }

    private static String afterModel(String text, String model) {
        if (model.isEmpty()) {
            return null;
        }
        String prefix =
                model.replace(" ", "")
                        .codePoints()
                        .mapToObj(c -> Pattern.quote(Character.toString(c)))
                        .collect(Collectors.joining(" *"));
        Matcher match = Pattern.compile("^" + prefix, Pattern.CASE_INSENSITIVE).matcher(text);
        return match.find() ? text.substring(match.end()).strip() : null;
    }

    private static String modelName(String text, String brand) {
        return "OPPO手机".equals(brand)
                ? text.replaceFirst("(?i)^OPPO\\s*", "")
                : text.replaceFirst("(?i)^ONEPLUS\\s*", "一加 ");
    }

    static Memory memory(String value) {
        Matcher match = SKU.matcher(value);
        if (!match.matches()) {
            return null;
        }
        long storage = Long.parseLong(match.group(2));
        String unit = match.group(3);
        if (unit != null && Character.toUpperCase(unit.charAt(0)) == 'T') {
            storage *= 1024;
        }
        return storage > 99_999_999 ? null : new Memory(match.group(1) + "GB", storage + "GB");
    }

    record Memory(String ram, String rom) {}

    private static boolean sameMemory(String left, String right) {
        Memory memory = memory(left);
        return memory != null && memory.equals(memory(right));
    }

    private static String spec(MatchResult match) {
        String unit = match.group(3);
        return match.group(1)
                + "GB+"
                + match.group(2)
                + (unit != null && Character.toUpperCase(unit.charAt(0)) == 'T' ? "TB" : "GB");
    }

    private static String color(String value) {
        String candidate = value.replaceFirst("^\\(([^()]+)\\)$", "$1").strip();
        return COLOR.matcher(candidate).matches() && !NOT_COLOR.matcher(candidate).find()
                ? candidate
                : null;
    }

    private record Names(String goods, String sku) {}

    private record ColorMatch(String confirmed, List<String> candidates, String edition) {}
}
