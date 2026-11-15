<script setup>
import { watch } from 'vue'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import AppIcon from '../../../components/AppIcon.vue'
import AttachmentDownload from '../../../components/AttachmentDownload.vue'
import { attachmentDownloadUrl } from '../../../api/attachments'
import { ATTACHMENT_ACCEPT } from '../../../constants/attachments'
const props = defineProps({
  form: { type: Object, required: true },
  files: { type: Array, required: true },
  readOnly: { type: Boolean, default: false },
})
const emit = defineEmits(['update-field', 'add-files', 'remove-file', 'retry-file'])
watch(
  () =>
    props.files.map((entry) => ({
      id: entry.id,
      error: entry.error,
      name: entry.attachment?.originalName ?? entry.file?.name,
    })),
  (entries, previous = []) => {
    for (const entry of entries) {
      if (entry.error && entry.error !== previous.find((item) => item.id === entry.id)?.error) {
        ElMessage({ message: `${entry.name}，${entry.error}`, type: 'error', showClose: true })
      }
    }
  },
  { immediate: true },
)
let selected = []
let scheduled = false
function selectFile(file) {
  selected.push(file.raw)
  if (scheduled) {
    return
  }
  scheduled = true
  queueMicrotask(() => {
    emit('add-files', selected)
    selected = []
    scheduled = false
  })
}
</script>
<template>
  <section class="form-section" aria-labelledby="other-title">
    <h2 id="other-title">
      <span></span>
      四、其他信息
    </h2>
    <el-form-item label="上传文件或链接">
      <el-upload
        v-if="!readOnly"
        class="attachment-upload"
        drag
        multiple
        action="#"
        :auto-upload="false"
        :accept="ATTACHMENT_ACCEPT"
        :show-file-list="false"
        :file-list="[]"
        :on-change="selectFile"
      >
        <AppIcon name="upload" :size="28" />
        <div class="el-upload__text">
          将文件拖到此处，或
          <em>点击选择</em>
        </div>
        <template #tip>
          <div class="el-upload__tip">
            文件选择后上传，保存草稿后关联申请。最多 10 个，每个 20MB。
          </div>
        </template>
      </el-upload>
      <ul v-if="files.length" class="policy-attachment-list" aria-label="附件文件">
        <li v-for="entry in files" :key="entry.id">
          <AttachmentDownload
            v-if="entry.attachment"
            :url="attachmentDownloadUrl(entry.attachment.id)"
            :filename="entry.attachment.originalName"
            popup-error
          />
          <span v-else>{{ entry.file.name }}</span>
          <span role="status">
            {{
              {
                uploading: '上传中…',
                uploaded: '已上传',
                failed: '上传失败',
                deleting: '移除中…',
              }[entry.status]
            }}
          </span>
          <el-button
            v-if="entry.status === 'failed' && !readOnly"
            link
            type="primary"
            @click="$emit('retry-file', entry.id)"
          >
            重试
          </el-button>
          <el-button
            v-if="!readOnly"
            link
            type="danger"
            :aria-label="`移除附件 ${entry.attachment?.originalName ?? entry.file.name}`"
            @click="$emit('remove-file', entry.id)"
          >
            移除
          </el-button>
        </li>
      </ul>
      <el-input
        :model-value="form.links"
        @update:model-value="$emit('update-field', 'links', $event)"
        type="url"
        class="attachment-link"
        aria-label="附件链接"
        placeholder="或粘贴文件链接 https://"
      />
    </el-form-item>
  </section>
</template>

<style scoped>
.policy-attachment-list {
  width: 100%;
  padding: 0;
  margin: 8px 0;
  list-style: none;
}
.policy-attachment-list li {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 12px;
  margin: 6px 0;
  overflow-wrap: anywhere;
}
.policy-attachment-list a,
.policy-attachment-list li > span:first-child {
  min-width: 0;
  max-width: 100%;
}
</style>
