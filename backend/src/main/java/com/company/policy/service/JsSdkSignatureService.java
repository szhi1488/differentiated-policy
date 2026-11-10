package com.company.policy.service;

import com.company.policy.vo.JsSdkSignatureResponse;

/** 页面来源校验与飞书 JS-SDK 签名服务。 */
public interface JsSdkSignatureService {
    JsSdkSignatureResponse sign(String pageUrl);

    String validateAndStripFragment(String pageUrl);
}
