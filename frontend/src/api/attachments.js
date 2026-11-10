import { request } from './request'

export function uploadAttachment(file) {
  const body = new FormData()
  body.append('file', file)
  // 由浏览器生成 multipart boundary，不手动设置 Content-Type。
  return request('/api/attachments', { method: 'POST', body })
}

export function deleteAttachment(id) {
  return request(`/api/attachments/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function attachmentDownloadUrl(id) {
  return `/api/attachments/${encodeURIComponent(id)}/content`
}
