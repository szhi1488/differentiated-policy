package com.company.policy.service;

/** 处理本企业飞书卡片、群消息和个人待办的事务登记。 */
public interface PolicyDeliveryWorker {
    /** 领取并处理一个到期动作，返回本次是否处理了记录。 */
    boolean processOne();

    record TrialResult(String status, String receiptId, String lastError) {}

    /** 按设置页的单号、轮次和投递编号处理一条初审卡，返回原登记的最新回执。 */
    TrialResult processTrialCard(long policyId, int version, String deliveryKey);
}
