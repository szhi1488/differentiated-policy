package com.company.policy.service;

import com.company.policy.service.PolicyFeishuSender.Delivery;

/** 管理创建飞书对象前的收件人和请求内容核对。 */
public interface PolicyDeliveryAttemptGuard {
    /** 校验本次租约并记录实际外发参数，结果未知时只允许原请求重放。 */
    void prepare(Delivery item, String recipient, String fingerprint);
}
