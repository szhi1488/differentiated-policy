package com.company.policy.service;

import com.company.policy.dto.PolicyDraftRequest.Link;
import com.company.policy.vo.AttachmentResponse;
import com.company.policy.vo.CurrentUserResponse;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/** 本地附件存储，政策文件通过当前表单或提交快照校验访问。 */
public interface AttachmentService {
    AttachmentResponse upload(MultipartFile file, CurrentUserResponse user);

    Download download(String id, CurrentUserResponse user);

    List<Link> bind(List<Link> attachments, CurrentUserResponse user);

    /** 调用方须先校验父申请及该文件属于当前表单或指定轮次。 */
    Download downloadForPolicy(String id, CurrentUserResponse user);

    void delete(String id, CurrentUserResponse user);

    record Download(AttachmentResponse attachment, Resource resource) {}
}
