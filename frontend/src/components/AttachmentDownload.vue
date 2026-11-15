<script setup>
import { onScopeDispose, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import { readAttachmentContent } from '../api/attachments'

const props = defineProps({
  url: { type: String, required: true },
  filename: { type: String, required: true },
  popupError: { type: Boolean, default: false },
})
const downloading = ref(false)
const error = ref('')
let operation

function cancelDownload() {
  operation?.abort()
  downloading.value = false
  error.value = ''
}
watch(() => [props.url, props.filename], cancelDownload)
onScopeDispose(cancelDownload)

async function download() {
  if (downloading.value) {
    return
  }
  const controller = new AbortController()
  operation = controller
  downloading.value = true
  error.value = ''
  try {
    // 在当前网页会话中读取文件，再保存字节，保留飞书内的登录身份。
    const content = await readAttachmentContent(props.url, controller.signal)
    if (controller.signal.aborted) {
      return
    }
    const blobUrl = URL.createObjectURL(content)
    const link = document.createElement('a')
    link.href = blobUrl
    link.download = props.filename
    document.body.appendChild(link)
    try {
      link.click()
    } finally {
      link.remove()
      // 等浏览器接收下载后释放临时地址。
      setTimeout(() => URL.revokeObjectURL(blobUrl), 1000)
    }
  } catch (cause) {
    if (!controller.signal.aborted) {
      error.value = cause.message || '附件下载失败，请重试。'
      if (props.popupError) {
        ElMessage({ message: error.value, type: 'error', showClose: true })
      }
    }
  } finally {
    if (operation === controller) {
      downloading.value = false
    }
  }
}
</script>

<template>
  <span class="attachment-download" :aria-busy="downloading">
    <el-link
      type="primary"
      role="button"
      tabindex="0"
      :disabled="downloading"
      :aria-label="`下载附件 ${filename}`"
      @click="download"
      @keydown.enter.prevent="download"
      @keydown.space.prevent="download"
    >
      {{ filename }}{{ downloading ? '（下载中…）' : '' }}
    </el-link>
    <span v-if="error && !popupError" class="attachment-download-error" role="alert">
      {{ error }}
    </span>
  </span>
</template>

<style scoped>
.attachment-download {
  display: inline-grid;
  min-width: 0;
  max-width: 100%;
  gap: 4px;
  vertical-align: middle;
  overflow-wrap: anywhere;
}
.attachment-download :deep(.el-link__inner) {
  min-width: 0;
  white-space: normal;
  text-align: left;
}
.attachment-download-error {
  font-size: 12px;
  color: var(--el-color-danger);
}
</style>
