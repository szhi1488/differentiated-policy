<script setup>
import { computed } from 'vue'
import AppIcon from '../../../components/AppIcon.vue'
const props = defineProps({
  form: { type: Object, required: true },
  files: { type: Array, required: true },
})
defineEmits(['update-field', 'add-files', 'remove-file'])
const uploadFiles = computed(() =>
  props.files.map(({ file }) => ({ uid: file.uid, name: file.name, status: 'ready', raw: file })),
)
</script>
<template>
  <section class="form-section" aria-labelledby="other-title">
    <h2 id="other-title">
      <span></span>
      四、其他信息
    </h2>
    <div class="two-columns">
      <el-form-item label="上传文件或链接">
        <el-upload
          class="attachment-upload"
          drag
          multiple
          action="#"
          :auto-upload="false"
          :file-list="uploadFiles"
          :on-change="(file) => $emit('add-files', [file.raw])"
          :on-remove="(file) => $emit('remove-file', file.uid)"
        >
          <AppIcon name="upload" :size="28" />
          <div class="el-upload__text">
            将文件拖到此处，或
            <em>点击选择</em>
          </div>
          <template #tip>
            <div class="el-upload__tip">文件仅暂存于当前页面，尚未上传</div>
          </template>
        </el-upload>
        <el-input
          :model-value="form.links"
          @update:model-value="$emit('update-field', 'links', $event)"
          type="url"
          class="attachment-link"
          aria-label="附件链接"
          placeholder="或粘贴文件链接 https://"
        />
      </el-form-item>
      <el-form-item label="其他说明（选填）">
        <el-input
          :model-value="form.explanation"
          @update:model-value="$emit('update-field', 'explanation', $event)"
          type="textarea"
          :rows="6"
          aria-label="其他说明"
          placeholder="请输入其他说明…"
        />
      </el-form-item>
    </div>
  </section>
</template>
