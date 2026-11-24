package com.company.policy;

import com.company.policy.common.config.FeishuProperties;
import com.company.policy.service.PolicyDeliveryWorker;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** 本机运维命令。单独启动一次，仅处理管理员在投递记录中指定的初审卡。 */
final class PolicyCardCommand {
    private static final Logger log = LoggerFactory.getLogger(PolicyCardCommand.class);

    private PolicyCardCommand() {}

    static void run(String[] args) {
        if (args.length != 4) {
            throw new IllegalArgumentException("用法：" + args[0] + " 申请ID 提交轮次 原投递编号");
        }
        long policyId = Long.parseLong(args[1]);
        int round = Integer.parseInt(args[2]);
        String key = args[3];
        if (policyId < 1 || round < 1 || !UUID.fromString(key).toString().equals(key)) {
            throw new IllegalArgumentException("请核对申请 ID、提交轮次和完整投递编号。");
        }

        // 这些命令行参数优先于本机配置，单卡进程不会监听端口或领取其他队列任务。
        try (var context =
                new SpringApplicationBuilder(PolicyApplication.class)
                        .run(
                                "--spring.main.web-application-type=none",
                                "--policy.review-delivery-enabled=false",
                                "--jackyun.import-scheduling-enabled=false",
                                "--jackyun.clean-scheduling-enabled=false",
                                "--spring.sql.init.mode=never")) {
            var feishu = context.getBean(FeishuProperties.class);
            if (!feishu.isConfigured()
                    || !feishu.hasCompanyTenant()
                    || feishu.getPolicyErpUrl().isBlank()) {
                throw new IllegalArgumentException("请先配置当前飞书应用、企业和 FEISHU_POLICY_ERP_URL。");
            }
            var worker = context.getBean(PolicyDeliveryWorker.class);
            var result = worker.processTrialCard(policyId, round, key);
            if (!"SENT".equals(result.status())
                    || result.receiptId() == null
                    || result.receiptId().isBlank()) {
                throw new IllegalStateException(
                        "指定初审卡操作未完成。状态 "
                                + result.status()
                                + (result.lastError() == null ? "" : "，" + result.lastError()));
            }
            log.info(
                    "指定初审卡操作 {} 已完成。申请 {}，轮次 {}，投递编号 {}，消息 ID {}",
                    args[0],
                    policyId,
                    round,
                    key,
                    result.receiptId());
        }
    }
}
