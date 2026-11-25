package com.company.policy.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.dto.PolicyDraftRequest.Card;
import com.company.policy.dto.PolicyDraftRequest.GoodsForm;
import com.company.policy.dto.PolicyDraftRequest.PlatformAssignment;
import com.company.policy.dto.PolicyDraftRequest.SubsidyPlan;
import com.company.policy.dto.PolicyDraftRequest.Target;
import com.company.policy.entity.PolicyTimeChange;
import com.company.policy.mapper.PolicyDeliveryAudienceMapper;
import com.company.policy.support.policy.PolicyExecutionChange;
import com.company.policy.support.policy.PolicyRouteSummary;
import com.company.policy.support.policy.PolicySnapshotCodec;
import com.company.policy.vo.ApplicantCatalogResponse;
import com.company.policy.vo.PolicyApplicability.Policy;
import com.company.policy.vo.PolicyApplicability.Product;
import com.company.policy.vo.PolicyApplicability.Scope;
import com.company.policy.vo.PolicyApplicability.Selection;
import com.company.policy.vo.PolicyResponse.Snapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PolicyApprovalMessageTest {
    private static final SubsidyPlan POLICY_PRICE_RATIO =
            new SubsidyPlan("p", "RATIO", null, null, "10", "POLICY_PRICE");

    @Test
    void shorteningUsesOriginalApprovalAndFreezesRequestedAndActualDeadline() {
        var audience = mock(PolicyDeliveryAudienceMapper.class);
        String original =
                """
                {"id":"3","policyCode":"ZC-000003","submissionVersion":1,"applicantName":"原申请人",
                "form":{"perspective":"RETAIL","selectedPlatforms":["JD"],"verifyType":"SALES",
                "startTime":"2026-11-20T08:30:45+08:00","endTime":"2026-11-27T18:00:00+08:00",
                "sceneTypes":["NATIONAL"],"goodsForm":{"cards":[]}},"goods":[]}
                """;
        when(audience.approvedRound("company", 3, 1))
                .thenReturn(new PolicyDeliveryAudienceMapper.ApprovedRound(original, null, null));
        var change = new PolicyTimeChange();
        change.setTenantKey("company");
        change.setPolicyId(3L);
        change.setPolicyVersion(1);
        change.setActorName("接手人");
        change.setDirection("SHORTEN");
        change.setPreviousEnd(LocalDateTime.parse("2026-11-30T18:00:00"));
        change.setRequestedEnd(LocalDateTime.parse("2026-11-28T18:00:00"));
        change.setChangedAt(LocalDateTime.parse("2026-11-29T10:05:37"));
        change.setActualEnd(change.getChangedAt().minusSeconds(1));
        change.setReason("活动结束");
        String text =
                new PolicyApprovalMessage(audience, new PolicySnapshotCodec(), new ObjectMapper())
                        .adjusted(change);
        assertThat(text)
                .contains(
                        "政策截止已提前 · ZC-000003",
                        "操作人：接手人",
                        "平台：京东",
                        "调整前政策时段（北京时间）：2026-11-20 08:30:45 至 2026-11-30 18:00:00",
                        "2026-11-30 18:00:00 → 2026-11-28 18:00:00",
                        "原因：活动结束",
                        "实际截止（北京时间）：2026-11-29 10:05:36")
                .doesNotContain("调整单：", "采购终审");
    }

    @Test
    void sixOrdinaryGoodsAreAnnouncedOncePerConfigurationAndSpecifiedTermsStaySeparate() {
        var ordinary = sixGoods();
        var separate = product(7, "8", "Reno mini", "128GB", "松石绿");
        var goods = new ArrayList<>(ordinary);
        goods.add(separate);
        var cards =
                List.of(
                        card(
                                "SPECIFIED",
                                List.of(separate),
                                new SubsidyPlan("p", "FIXED", "27.50", null, null, null),
                                0),
                        card("COMMON", ordinary, POLICY_PRICE_RATIO, null));
        String message = announcement(true, cards, goods);
        assertThat(message.lines().toList())
                .containsSubsequence(
                        "手机型号（6 件商品）",
                        "快手：补贴比例 10%（计算基数：政策价）",
                        "指定适用机型（1 件商品）",
                        "快手：固定补贴 27.50 元，数量 0 台");
        assertThat(message)
                .contains("12GB+128GB 松石绿")
                .containsOnlyOnce("12GB+256GB 整组配置")
                .containsOnlyOnce("12GB+512GB 整组配置")
                .doesNotContain("初雪白", "可可棕", "奇遇喵", "128GB 整组配置");
    }

    @Test
    void unmarkedHistoricalOrdinaryCardListsOnlyItsSelectedColorsOncePerConfiguration() {
        var goods = sixGoods();
        var selected = List.of(goods.get(0), goods.get(1), goods.get(2), goods.get(3));
        String message =
                announcement(
                        false,
                        List.of(card("COMMON", selected, POLICY_PRICE_RATIO, null)),
                        selected);
        assertThat(message)
                .containsOnlyOnce("12GB+256GB")
                .containsOnlyOnce("12GB+512GB")
                .contains("手机型号（4 件商品）")
                .doesNotContain("整组配置");
        assertThat(message.lines().filter(line -> line.contains("12GB+256GB")).toList())
                .singleElement()
                .asString()
                .contains("初雪白", "可可棕", "奇遇喵");
        assertThat(message.lines().filter(line -> line.contains("12GB+512GB")).toList())
                .singleElement()
                .asString()
                .contains("初雪白")
                .doesNotContain("可可棕", "奇遇喵");
    }

    @Test
    void longOrdinaryCardCountsOmittedConfigurationRowsAndKeepsItsPlatformSubsidy() {
        var goods = new ArrayList<ApplicantCatalogResponse>();
        int configurations = 75;
        for (int i = 0; i < configurations; i++) {
            String spu = Integer.toString(1000 + i);
            goods.add(product(i * 2 + 1, spu, "Reno-" + spu, "512GB", "白"));
            goods.add(product(i * 2 + 2, spu, "Reno-" + spu, "512GB", "黑"));
        }
        String message =
                announcement(true, List.of(card("COMMON", goods, POLICY_PRICE_RATIO, null)), goods);
        var omission = Pattern.compile("另有 (\\d+)项商品范围未列出。").matcher(message);
        assertThat(omission.find()).isTrue();
        long shown = message.lines().filter(line -> line.contains("整组配置")).count();
        assertThat(shown).isPositive();
        assertThat(shown + Integer.parseInt(omission.group(1))).isEqualTo(configurations);
        assertThat(message)
                .contains("手机型号（150 件商品）", "快手：补贴比例 10%（计算基数：政策价）")
                .doesNotContain("逐段采用名单", "确认时段", "【其他信息】", "附件及链接");
    }

    private static List<ApplicantCatalogResponse> sixGoods() {
        var goods = new ArrayList<ApplicantCatalogResponse>();
        for (String storage : List.of("256GB", "512GB"))
            for (String color : List.of("初雪白", "可可棕", "奇遇喵"))
                goods.add(product(goods.size() + 1, "7", "Reno17 Pro", storage, color));
        return goods;
    }

    private static ApplicantCatalogResponse product(
            int id, String spu, String model, String rom, String color) {
        return new ApplicantCatalogResponse(
                Integer.toString(id),
                "1",
                "OPPO",
                "2",
                "Reno",
                spu,
                model,
                null,
                "12GB",
                rom,
                model + color,
                color,
                true);
    }

    private static Card card(
            String type, List<ApplicantCatalogResponse> goods, SubsidyPlan plan, Integer quantity) {
        var targets =
                goods.stream()
                        .map(p -> new Target(p.spuId(), p.ram(), p.rom(), p.goodsId()))
                        .toList();
        return new Card(
                type,
                type,
                true,
                targets,
                List.of(plan),
                List.of(new PlatformAssignment("KUAISHOU", plan.key(), quantity)));
    }

    private static String announcement(
            boolean whole, List<Card> cards, List<ApplicantCatalogResponse> goods) {
        return PolicyApprovalMessage.format(approvedSnapshot(whole, cards, goods), null, "小周");
    }

    private static Snapshot approvedSnapshot(
            boolean whole, List<Card> cards, List<ApplicantCatalogResponse> goods) {
        var form =
                new PolicyDraftRequest(
                        1,
                        "RETAIL",
                        List.of("KUAISHOU"),
                        "SALES",
                        List.of("NATIONAL_SUB"),
                        false,
                        "2026-11-24T10:00:00+08:00",
                        "2026-12-02T09:00:01+08:00",
                        null,
                        List.of(),
                        new GoodsForm(cards));
        return new Snapshot("15", "ZC-000015", "3", "小林", 2, form, goods, null, null, whole);
    }

    @Test
    void approvedRetailStackListsOnlyTheSelectedOriginalsInEachConfirmedPeriod() throws Exception {
        var a = retailPolicy(11, "SALES", List.of("NATIONAL_SUB"), POLICY_PRICE_RATIO);
        var both = List.of("NATIONAL_SUB", "LOCAL_SUB");
        var fixed = new SubsidyPlan("p", "FIXED", "10.00", "204.00", null, null);
        var b = retailPolicy(12, "SALES", both, fixed);
        var current = retailPolicy(15, "ACTIVATION", both, fixed);
        var unselected = retailPolicy(16, "ACTIVATION", both, POLICY_PRICE_RATIO);
        var originals = List.of(a, b);
        var candidates = List.of(a, b, current, unselected);
        var scope = new Scope("KUAISHOU", 1L, null, null, null);
        var start = LocalDateTime.parse("2026-11-26T00:00:00");
        var split = start.plusDays(1);
        var end = split.plusDays(1);
        var first =
                PolicyExecutionChange.compare(
                        "RETAIL",
                        scope,
                        start,
                        split.minusSeconds(1),
                        originals,
                        List.of(a, b, current),
                        candidates,
                        true);
        var second =
                PolicyExecutionChange.compare(
                        "RETAIL",
                        scope,
                        split,
                        end.minusSeconds(1),
                        originals,
                        originals,
                        candidates,
                        true);
        var solo =
                PolicyExecutionChange.compare(
                        "RETAIL",
                        scope,
                        end,
                        end.plusDays(1).minusSeconds(1),
                        originals,
                        List.of(a),
                        candidates);
        var unaffected =
                PolicyExecutionChange.compare(
                        "RETAIL",
                        scope,
                        end.plusDays(1),
                        end.plusDays(2),
                        originals,
                        originals,
                        List.of());
        var route = PolicyRouteSummary.from(List.of(first, second, solo, unaffected), List.of());
        var goods = List.of(product(1, "7", "Reno17 Pro", "256GB", "初雪白"));
        var snapshot = approvedSnapshot(true, List.of(card("COMMON", goods, fixed, null)), goods);
        var audience = mock(PolicyDeliveryAudienceMapper.class);
        var json = new ObjectMapper().findAndRegisterModules();
        var codec = new PolicySnapshotCodec();
        when(audience.approvedRound("company", 15, 2))
                .thenReturn(
                        new PolicyDeliveryAudienceMapper.ApprovedRound(
                                codec.write(snapshot), "Becca", json.writeValueAsString(route)));
        var messages = new PolicyApprovalMessage(audience, codec, json);
        String execution = "实际采用本单的平台：快手。";
        String announcement =
                messages.build(
                        "company",
                        15,
                        2,
                        "小周",
                        execution,
                        text ->
                                FeishuGroupPost.fits(
                                        json, "oc_test", text, List.of("ou_test"), "uuid"));
        String[] sections = announcement.split("【叠加政策】", -1);
        assertThat(sections).hasSize(2);
        assertThat(sections[0]).contains("【商品及补贴信息】", "实际采用本单的平台：快手。");
        String footer = sections[1];
        assertThat(footer).doesNotContain("未列出", "未展开");
        String retained = "ZC-000011 ＋ ZC-000012";
        assertThat(footer).containsOnlyOnce(retained + " ＋ ZC-000015");
        int followingPeriod = footer.lastIndexOf(retained);
        assertThat(followingPeriod).isGreaterThan(footer.indexOf(retained));
        String firstPeriod = footer.substring(0, followingPeriod);
        String secondPeriod = footer.substring(followingPeriod);
        for (var period : List.of(firstPeriod, secondPeriod)) {
            assertThat(period)
                    .contains("快手零售", "Reno17 Pro 12GB+256GB 整组配置")
                    .containsOnlyOnce("ZC-000011（原政策）\n国补 · 核销类型：销售维度 · 比例补贴 政策价 × 10%")
                    .containsOnlyOnce("ZC-000012（原政策）\n国补+地补 · 核销类型：销售维度 · 固定金额 10 元/台，上限 204 元")
                    .doesNotContain("ZC-000016", "核销类型：激活维度", "2026-11-28", "2026-11-29");
        }
        assertThat(firstPeriod)
                .contains("2026-11-26 00:00:00 至 2026-11-26 23:59:59")
                .doesNotContain("本次申请未在本段采用");
        assertThat(secondPeriod)
                .contains("2026-11-27 00:00:00 至 2026-11-27 23:59:59", "本次申请未在本段采用");

        int summaryBudget = sections[0].length() + 30;
        String summaryOnly =
                messages.build(
                        "company", 15, 2, "小周", execution, text -> text.length() <= summaryBudget);
        assertThat(summaryOnly)
                .hasSizeLessThanOrEqualTo(summaryBudget)
                .contains("【叠加政策】")
                .containsPattern("2\\s*组叠加政策未列出")
                .doesNotContain("ZC-000011", "ZC-000012");

        int firstOriginalBudget = announcement.indexOf("ZC-000012（原政策）") + 36;
        String partial =
                messages.build(
                        "company",
                        15,
                        2,
                        "小周",
                        execution,
                        text -> text.length() <= firstOriginalBudget);
        assertThat(partial)
                .hasSizeLessThanOrEqualTo(firstOriginalBudget)
                .contains("ZC-000011（原政策）\n国补 · 核销类型：销售维度 · 比例补贴 政策价 × 10%")
                .containsPattern("1\\s*张政策的补贴条款未列出")
                .containsPattern("1\\s*组叠加政策未列出")
                .doesNotContain("ZC-000012（原政策）", "国补+地补 · 核销类型：销售维度", "2026-11-27 00:00:00");
    }

    private static Policy retailPolicy(
            int id, String verification, List<String> scenes, SubsidyPlan subsidy) {
        var product =
                new Product("1", "Reno17 Pro 初雪白", "初雪白", "7", "Reno17 Pro", null, "12GB", "256GB");
        var selection = new Selection("COMMON", List.of(product), subsidy, null, 1);
        boolean current = id == 15;
        return new Policy(
                Integer.toString(id),
                "ZC-%06d".formatted(id),
                current ? 2 : 1,
                null,
                current,
                null,
                null,
                null,
                null,
                verification,
                scenes,
                List.of(selection));
    }

    @Test
    void retailAnnouncementKeepsTheSelectedGoodsAndEachPlatformPlanAndQuantityTogether() {
        var snapshot =
                new PolicySnapshotCodec()
                        .read(
                                """
                {
                  "policyCode":"ZC-000051","submissionVersion":2,"applicantName":"小林",
                  "form":{"perspective":"RETAIL","selectedPlatforms":["JD","PDD"],"verifyType":"ACTIVATION",
                    "sceneTypes":["NATIONAL_SUB","LOCAL_SUB"],"estimated":false,
                    "startTime":"2026-11-24T10:00:00+08:00","endTime":"2026-12-02T09:00:01+08:00",
                    "specialRemark":"仅线上","attachmentInfo":[
                      {"type":"FILE","id":"private-id","originalName":"政策说明.pdf"},
                      {"type":"LINK","url":"https://example.com/policy-reference"}],
                    "goodsForm":{"cards":[{"cardType":"SPECIFIED","targets":[{"goodsId":"7"}],
                      "subsidyPlans":[{"key":"f","subsidyMode":"FIXED","fixedAmount":"20.10","maxSubsidyAmount":"35.55"},
                                      {"key":"r","subsidyMode":"RATIO","ratio":"4.50","priceBase":"HAND_PRICE"}],
                      "platformAssignments":[{"platformCode":"JD","planKey":"f","quantity":0},
                                             {"platformCode":"PDD","planKey":"r"}]}]}},
                  "goods":[{"goodsId":"7","brandName":"OPPO","modelName":"Reno","edition":"Pro","ram":"12GB","rom":"1024GB","colorName":"白色"}],
                  "consent":{"decisions":[],"endAdjustments":[{"target":{"policyId":"42","previousEnd":"2026-12-03T23:59:59+08:00"},
                    "endTime":"2026-12-02T09:00:01+08:00","reason":"库存调整"}]}
                }
                """);
        String message = PolicyApprovalMessage.format(snapshot, null, "小周");
        assertThat(message)
                .contains(
                        "ZC-000051 · 第 2 轮",
                        "申请人：小林",
                        "终审人：小周",
                        "零售渠道",
                        "京东、拼多多",
                        "激活维度",
                        "国补、地补",
                        "是否为预估政策：否",
                        "特别备注：仅线上",
                        "指定适用机型",
                        "Reno Pro 12GB+1T 白色",
                        "京东：固定补贴 20.10 元，补贴上限 35.55 元，数量 0 台",
                        "拼多多：补贴比例 4.50%（计算基数：到手价），数量未填",
                        "ZC-000042",
                        "2026-12-02 09:00:01",
                        "库存调整")
                .doesNotContain(
                        "逐段采用名单",
                        "确认时段",
                        "【其他信息】",
                        "附件及链接",
                        "政策说明.pdf",
                        "private-id",
                        "外部链接",
                        "https://example.com/policy-reference");
    }
}
