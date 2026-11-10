package com.company.policy.service;

import com.company.policy.vo.AttachmentResponse;
import com.company.policy.vo.CurrentUserResponse;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/** 独立附件存储；政策持久化接入时须补充绑定及父申请权限。 */
public interface AttachmentService {
    AttachmentResponse upload(MultipartFile file, CurrentUserResponse user);

    Download download(String id, CurrentUserResponse user);

    void delete(String id, CurrentUserResponse user);

    record Download(AttachmentResponse attachment, Resource resource) {}
}
