import { computed, ref } from 'vue'
import { deleteAttachment, uploadAttachment } from '../api/attachments'
import {
  ATTACHMENT_ACCEPT,
  ATTACHMENT_MAX_COUNT,
  ATTACHMENT_MAX_SIZE,
} from '../constants/attachments'

/** 上传成功后保留服务端标识；失败保留原文件供用户手动重试。 */
export function useAttachments() {
  const files = ref([])
  const attachmentNotice = ref('')
  const attachmentsBusy = computed(() =>
    files.value.some((entry) => ['uploading', 'deleting'].includes(entry.status)),
  )

  async function upload(entry) {
    entry.status = 'uploading'
    entry.error = ''
    try {
      entry.attachment = await uploadAttachment(entry.file)
      entry.status = 'uploaded'
    } catch (error) {
      entry.status = 'failed'
      entry.error = error.message
    }
  }

  async function addFiles(list) {
    if (attachmentsBusy.value) {
      return
    }
    attachmentNotice.value = ''
    const selected = Array.from(list || [])
    if (files.value.length + selected.length > ATTACHMENT_MAX_COUNT) {
      attachmentNotice.value = `当前页面最多添加 ${ATTACHMENT_MAX_COUNT} 个附件。`
      return
    }
    const ids = []
    for (const file of selected) {
      const extension = file.name.slice(file.name.lastIndexOf('.')).toLowerCase()
      if (!ATTACHMENT_ACCEPT.split(',').includes(extension) || !file.size) {
        attachmentNotice.value = '请选择非空的 PDF、Word、Excel、PNG 或 JPG 文件。'
        continue
      }
      if (file.size > ATTACHMENT_MAX_SIZE) {
        attachmentNotice.value = '单个附件不能超过 20MB。'
        continue
      }
      const id = crypto.randomUUID()
      ids.push(id)
      files.value.push({ id, file, attachment: null, status: 'uploading', error: '' })
    }
    // 逐个上传，避免多个大文件同时占用连接和内存。
    for (const id of ids) {
      await upload(files.value.find((entry) => entry.id === id))
    }
  }

  async function retryFile(entry) {
    if (attachmentsBusy.value || entry.status !== 'failed') {
      return
    }
    await upload(entry)
  }

  async function removeFile(entry) {
    if (attachmentsBusy.value) {
      return false
    }
    if (entry.attachment) {
      entry.status = 'deleting'
      entry.error = ''
      try {
        await deleteAttachment(entry.attachment.id)
      } catch (error) {
        if (error.status !== 404) {
          entry.status = 'uploaded'
          entry.error = error.message
          return false
        }
      }
    }
    files.value = files.value.filter((item) => item.id !== entry.id)
    return true
  }

  async function clearFiles() {
    if (attachmentsBusy.value) {
      return false
    }
    for (const entry of [...files.value]) {
      if (!(await removeFile(entry))) {
        return false
      }
    }
    attachmentNotice.value = ''
    return true
  }

  return {
    files,
    attachmentNotice,
    attachmentsBusy,
    addFiles,
    retryFile,
    removeFile,
    clearFiles,
  }
}
