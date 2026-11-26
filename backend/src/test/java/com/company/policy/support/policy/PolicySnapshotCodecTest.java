package com.company.policy.support.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PolicySnapshotCodecTest {
    // 按当前提交格式固定样本，独立于当前请求类、响应类和快照写入器。
    private static final String SNAPSHOT =
            """
            {
              "id":"5","policyCode":"ZC-000005","applicantId":"9","applicantName":"张三","submissionVersion":2,
              "form":{
                "recordVersion":3,"perspective":"RETAIL","selectedPlatforms":["JD","PDD"],
                "verifyType":"SALES","sceneTypes":["LOCAL_SUB"],"estimated":false,
                "startTime":"2026-11-10T09:00:00+08:00","endTime":"2026-12-01T00:00:00+08:00",
                "specialRemark":"保留原备注",
                "attachmentInfo":[{"type":"FILE","url":null,"id":"file-1","originalName":"凭证.pdf"},
                                  {"type":"LINK","url":"https://example.com","id":null,"originalName":null}],
                "goodsForm":{"cards":[{
                  "key":"card1","cardType":"SPECIFIED","followsOrderPlatforms":true,
                  "targets":[{"spuId":"7","ram":"8GB","rom":"128GB","goodsId":"9007199254740993"}],
                  "subsidyPlans":[
                    {"key":"a","subsidyMode":"FIXED","fixedAmount":"20.00","maxSubsidyAmount":null,"ratio":null,"priceBase":null},
                    {"key":"b","subsidyMode":"RATIO","fixedAmount":null,"maxSubsidyAmount":"100.50","ratio":"3.20","priceBase":"POLICY_PRICE"}],
                  "platformAssignments":[{"platformCode":"JD","planKey":"a","quantity":0},
                                         {"platformCode":"PDD","planKey":"b","quantity":null}]
                }]}
              },
              "goods":[{"goodsId":"9007199254740993","brandId":"1","brandName":"OPPO手机","seriesId":"4",
                "seriesName":"Find","spuId":"7","modelName":"Find X","edition":"标准版",
                "ram":"8GB","rom":"128GB","goodsName":"提交时原名","colorName":"黑色","onSale":true}]
            }
            """;

    @Test
    void roundTripsSubmittedSnapshotWithoutChangingPrecisionOrQuantity() throws Exception {
        var json = new ObjectMapper();
        var codec = new PolicySnapshotCodec();
        var expected = json.readTree(SNAPSHOT);

        var snapshot = codec.read(SNAPSHOT);
        assertThat(json.<JsonNode>valueToTree(snapshot)).isEqualTo(expected);
        assertThat(json.readTree(codec.write(snapshot))).isEqualTo(expected);
    }

    @Test
    void extraStoredFieldsDoNotHideThePolicyOrChangeItsCurrentForm() throws Exception {
        var json = new ObjectMapper();
        var original = json.readTree(SNAPSHOT);
        var stored = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) stored.path("form"))
                .put("sceneType", "LOCAL_SUB")
                .put("otherExplanation", "附加说明");
        ((com.fasterxml.jackson.databind.node.ObjectNode) stored)
                .put("sourcePolicyCode", "ZC-000001");
        ((com.fasterxml.jackson.databind.node.ObjectNode) stored.at("/form/goodsForm"))
                .putObject("timeAdjustment")
                .put("reason", "不属于本轮随单调整");
        var codec = new PolicySnapshotCodec();
        var detail = codec.read(stored.toString());
        assertThat(json.readTree(codec.write(detail))).isEqualTo(original);
    }

    @Test
    void pendingAdjustmentUsesTheOriginalPolicyAndItsApprovedRound() {
        var stored =
                """
                [
                  {"target":{"policyId":"5","auditLogId":"9","executionPolicyId":"7","executionAuditLogId":"8"},"reason":"本轮原单"},
                  {"target":{"policyId":"5","auditLogId":"8"},"reason":"不同轮次"},
                  {"target":{"policyId":"7","auditLogId":"9"},"reason":"不同原单"}
                ]
                """;
        var codec = new PolicySnapshotCodec();
        assertThat(codec.readEndAdjustments(stored, 5, 9))
                .singleElement()
                .satisfies(change -> assertThat(change.reason()).isEqualTo("本轮原单"));
        assertThat(codec.readEndAdjustments(stored, 7, 8)).isEmpty();
    }
}
