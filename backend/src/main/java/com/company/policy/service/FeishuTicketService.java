package com.company.policy.service;

/** 飞书 JS-SDK 票据获取与缓存服务。 */
public interface FeishuTicketService {
    /** 获取用于页面签名的飞书 JS-SDK 票据，已有可用缓存时直接复用。 */
    String getTicket();
}
