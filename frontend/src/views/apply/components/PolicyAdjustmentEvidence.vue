<script setup>
import { onScopeDispose, ref, watch } from 'vue'
import { useAttachments } from '../../../composables/useAttachments'
import { ATTACHMENT_ACCEPT } from '../../../constants/attachments'
import AttachmentDownload from '../../../components/AttachmentDownload.vue'
import { attachmentDownloadUrl } from '../../../api/attachments'

const props = defineProps({
  reason: { type: String, default: '' },
  attachmentInfo: { type: Array, default: () => [] },
  uploads: { type: Object, default: null },
  disabled: Boolean,
})
const emit = defineEmits(['update:reason', 'update:attachmentInfo', 'status-change'])
const picker = ref(null)
let disposed = false
let operation
const uploads =
  props.uploads ??
  useAttachments({
    onChange: (files) => {
      if (!disposed) {
        emit('update:attachmentInfo', files)
      }
    },
  })
const { files, attachmentsBusy, attachmentsFailed, attachmentNotice } = uploads
if (!props.uploads) {
  uploads.restore(props.attachmentInfo)
}
watch(
  [attachmentsBusy, attachmentsFailed],
  ([busy, failed]) => emit('status-change', { busy, failed }),
  {
    flush: 'sync',
    immediate: true,
  },
)
function selectFiles(event) {
  const selected = Array.from(event.target.files)
  event.target.value = ''
  if (!props.disabled && !attachmentsBusy.value) {
    operation = uploads.addFiles(selected)
  }
}
function retry(entry) {
  if (!props.disabled && !attachmentsBusy.value) {
    operation = uploads.retryFile(entry)
  }
}
function remove(entry) {
  if (!props.disabled && !attachmentsBusy.value) {
    operation = uploads.removeFile(entry)
  }
}
// 弹窗取消或移除目标后，只清理本次新上传的文件。迟到的上传响应也须先拿到文件 ID。
onScopeDispose(async () => {
  disposed = true
  if (props.uploads) {
    return
  }
  await operation
  await uploads.clearFiles()
})
defineExpose({ retainFiles: uploads.markBound })
</script>

<template>
  <div class="adjustment-evidence">
    <el-form-item label="调整原因" required>
      <el-input
        :model-value="reason"
        type="textarea"
        :rows="2"
        maxlength="512"
        show-word-limit
        aria-label="调整原因"
        placeholder="填写本次时间调整的原因"
        :disabled="disabled"
        @update:model-value="emit('update:reason', $event)"
      />
    </el-form-item>
    <el-form-item label="证明文件（选填）">
      <input
        ref="picker"
        hidden
        type="file"
        multiple
        :accept="ATTACHMENT_ACCEPT"
        :disabled="disabled || attachmentsBusy"
        aria-label="上传调整证明文件"
        @change="selectFiles"
      />
      <div class="evidence-files">
        <div>
          <el-button size="small" :disabled="disabled || attachmentsBusy" @click="picker.click()">
            添加文件
          </el-button>
          <span class="evidence-limits">最多 10 个，每个 20MB</span>
        </div>
        <p v-if="attachmentNotice" role="alert">{{ attachmentNotice }}</p>
        <ul v-if="files.length" aria-label="调整证明文件">
          <li v-for="entry in files" :key="entry.id">
            <AttachmentDownload
              v-if="entry.attachment"
              :url="attachmentDownloadUrl(entry.attachment.id)"
              :filename="entry.attachment.originalName"
            />
            <span v-else>{{ entry.file.name }}</span>
            <span v-if="entry.status === 'uploading'" role="status">上传中…</span>
            <span v-else-if="entry.status === 'deleting'" role="status">移除中…</span>
            <span v-if="entry.error" role="alert">{{ entry.error }}</span>
            <el-button
              v-if="entry.status === 'failed'"
              link
              type="primary"
              :disabled="disabled || attachmentsBusy"
              @click="retry(entry)"
            >
              重试
            </el-button>
            <el-button
              link
              type="danger"
              :disabled="disabled || attachmentsBusy"
              :aria-label="'移除证明文件 ' + (entry.attachment?.originalName ?? entry.file.name)"
              @click="remove(entry)"
            >
              移除
            </el-button>
          </li>
        </ul>
      </div>
    </el-form-item>
  </div>
</template>

<style scoped>
.adjustment-evidence,
.evidence-files {
  display: grid;
  gap: 8px;
  min-width: 0;
}
.adjustment-evidence :deep(.el-form-item) {
  margin: 0;
}
.evidence-files ul,
.evidence-files p {
  margin: 0;
  padding: 0;
}
.evidence-files li {
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex-wrap: wrap;
  overflow-wrap: anywhere;
}
.evidence-files [role='alert'] {
  color: var(--el-color-danger);
}
.evidence-limits {
  margin-inline-start: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
