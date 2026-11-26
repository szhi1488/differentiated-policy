package com.company.policy.service;

import com.company.policy.dto.PolicyDraftRequest.Link;
import com.company.policy.vo.AttachmentResponse;
import com.company.policy.vo.CurrentUserResponse;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/** 本地附件存储，政策文件通过当前表单或提交快照校验访问。 */
public interface AttachmentService {
    /** 保存当前用户上传的文件，返回用于绑定政策的附件信息。 */
    AttachmentResponse upload(MultipartFile file, CurrentUserResponse user);

    /** 校验文件归属后，下载当前用户上传的附件。 */
    Download download(String id, CurrentUserResponse user);

    /** 核验文件归属和内容是否存在，并返回可保存到政策表单的附件列表。 */
    List<Link> bind(List<Link> attachments, CurrentUserResponse user);

    /** 核验期限变更的可选上传文件并取原名。正式绑定时调用方须在同一事务持有上传者行锁。 */
    List<Link> resolveChangeFiles(List<Link> files, CurrentUserResponse user);

    /** 校验申请归属后，下载本人申请当前内容中引用的附件。 */
    Download downloadCurrent(long policyId, String attachmentId, CurrentUserResponse user);

    Download downloadShared(
            long policyId, int version, String attachmentId, CurrentUserResponse user);

    /** 下载本人申请在指定提交轮次的快照中引用的附件。 */
    Download downloadApplicantHistory(
            long policyId, long logId, String attachmentId, CurrentUserResponse user);

    /** 校验政策审核权限后，下载本公司申请在指定轮次的快照附件。 */
    Download downloadAdminHistory(
            long policyId, long logId, String attachmentId, CurrentUserResponse user);

    /** 按审核详情的轮次授权下载该轮实际引用的文件。 */
    Download downloadReviewRound(
            long policyId, int version, String attachmentId, CurrentUserResponse user);

    /** 核对原单、获批轮次及指定调整的文件引用。 */
    Download downloadTimeChange(
            long policyId, int version, long changeId, String fileId, CurrentUserResponse user);

    /** 删除本人上传且未被政策表单或历史快照引用的附件。 */
    void delete(String id, CurrentUserResponse user);

    record Download(AttachmentResponse attachment, Resource resource) {}
}
