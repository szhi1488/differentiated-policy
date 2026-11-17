package com.company.policy.service;

import com.company.policy.vo.JsSdkSignatureResponse;

/** 页面来源校验与飞书 JS-SDK 签名服务。 */
public interface JsSdkSignatureService {
    /** 为来源可信的页面地址生成飞书 JS-SDK 签名参数。 */
    JsSdkSignatureResponse sign(String pageUrl);

    /** 校验页面地址的来源，并返回去掉片段标识的地址。 */
    String validateAndStripFragment(String pageUrl);
}
