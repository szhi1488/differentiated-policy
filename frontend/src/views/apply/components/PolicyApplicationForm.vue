<script setup>
import { computed } from 'vue'
import { ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message-box/style/css'
import AppIcon from '../../../components/AppIcon.vue'
import { usePolicyForm } from '../../../composables/usePolicyForm'
import PolicyDetailsTable from './PolicyDetailsTable.vue'

const {
  form,
  activePlatform,
  files,
  notice,
  platforms,
  selectedPlatforms,
  activePlatformName,
  rows,
  switchChannel,
  togglePlatform,
  selectAll,
  addModel,
  addFiles,
  reset,
  submit,
} = usePolicyForm(async (message) => {
  try {
    await ElMessageBox.confirm(message, '确认操作', {
      type: 'warning',
      confirmButtonText: '确认清除',
      cancelButtonText: '保留内容',
    })
    return true
  } catch {
    return false
  }
})

// 使用字符串保留北京时间墙上时间，避免浏览器时区转换。
const policyTime = computed({
  get: () =>
    form.value.startTime && form.value.endTime ? [form.value.startTime, form.value.endTime] : [],
  set: (value) => {
    ;[form.value.startTime, form.value.endTime] = value || ['', '']
  },
})
const uploadFiles = computed(() =>
  files.value.map(({ file }) => ({
    uid: file.uid,
    name: file.name,
    status: 'ready',
    raw: file,
  })),
)
function removeFile(file) {
  files.value = files.value.filter((entry) => entry.file.uid !== file.uid)
}
</script>

<template>
  <el-form class="policy-form" :model="form" label-position="top" @submit.prevent="submit">
    <section class="form-section" aria-labelledby="basic-title">
      <h2 id="basic-title">
        <span></span>
        一、基础信息
      </h2>
      <div class="basic-grid" :class="{ 'billion-grid': form.channel === 'BILLION' }">
        <el-form-item label="渠道" required>
          <el-select
            :model-value="form.channel"
            aria-label="渠道"
            @update:model-value="switchChannel"
          >
            <el-option label="零售渠道" value="RETAIL" />
            <el-option label="百亿渠道" value="BILLION" />
          </el-select>
        </el-form-item>
        <el-form-item label="平台" class="platforms-field" required>
          <div class="platform-options" role="group" aria-label="平台">
            <el-check-tag
              v-for="[code, name] in platforms"
              :key="code"
              :checked="form.platforms.includes(code)"
              @change="togglePlatform(code)"
            >
              {{ name }}
            </el-check-tag>
            <el-button link type="primary" @click="selectAll">全部选择</el-button>
          </div>
        </el-form-item>
        <template v-if="form.channel === 'RETAIL'">
          <el-form-item label="核销类型" required>
            <el-radio-group v-model="form.verifyType" aria-label="核销类型">
              <el-radio value="SALES">销售维度</el-radio>
              <el-radio value="ACTIVATION">激活维度</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="涉及场景" required>
            <el-radio-group v-model="form.scene" aria-label="涉及场景">
              <el-radio value="NATIONAL_SUB">国补</el-radio>
              <el-radio value="LOCAL_SUB">地补</el-radio>
              <el-radio value="SELF_SUB">自补</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="是否为预估政策" required>
            <el-radio-group v-model="form.estimated" aria-label="是否为预估政策">
              <el-radio :value="true">是</el-radio>
              <el-radio :value="false">否</el-radio>
            </el-radio-group>
          </el-form-item>
        </template>
      </div>
    </section>
    <section class="form-section" aria-labelledby="time-title">
      <h2 id="time-title">
        <span></span>
        二、政策时间与详情
      </h2>
      <div class="two-columns">
        <el-form-item label="开始结束时间（北京时间）" required>
          <el-date-picker
            v-model="policyTime"
            type="datetimerange"
            format="YYYY-MM-DD HH:mm:ss"
            value-format="YYYY-MM-DDTHH:mm:ss"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            start-label="政策开始时间"
            end-label="政策结束时间"
            popper-class="policy-date-popper"
          />
        </el-form-item>
        <el-form-item label="特别备注">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            aria-label="特别备注"
            placeholder="请输入特别备注（选填）"
          />
        </el-form-item>
      </div>
    </section>
    <section class="form-section" aria-labelledby="models-title">
      <div class="section-heading">
        <h2 id="models-title">
          <span></span>
          三、机型及补贴信息
        </h2>
        <el-button type="primary" @click="addModel">
          <AppIcon name="plus" :size="15" />
          添加机型
        </el-button>
      </div>
      <p class="section-description">各平台分别填写机型、数量与补贴，可切换平台查看已填写内容。</p>
      <el-tabs v-if="selectedPlatforms.length" v-model="activePlatform" class="platform-detail-nav">
        <el-tab-pane v-for="[code, name] in selectedPlatforms" :key="code" :name="code">
          <template #label>
            {{ name }}
            <el-tag size="small" type="info" round>{{ form.details[code]?.length || 0 }}</el-tag>
          </template>
        </el-tab-pane>
      </el-tabs>
      <PolicyDetailsTable
        v-model="rows"
        :platform-name="activePlatformName"
        :has-platform="Boolean(activePlatform)"
      />
    </section>
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
            :on-change="(file) => addFiles([file.raw])"
            :on-remove="removeFile"
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
            v-model="form.links"
            type="url"
            class="attachment-link"
            aria-label="附件链接"
            placeholder="或粘贴文件链接 https://"
          />
        </el-form-item>
        <el-form-item label="其他说明（选填）">
          <el-input
            v-model="form.explanation"
            type="textarea"
            :rows="6"
            aria-label="其他说明"
            placeholder="请输入其他说明…"
          />
        </el-form-item>
      </div>
    </section>
    <el-alert
      v-if="notice"
      :title="notice"
      type="warning"
      show-icon
      :closable="false"
      role="status"
    />
    <footer class="form-actions">
      <span>请确认政策信息填写准确后提交</span>
      <el-button @click="reset">清空表单</el-button>
      <el-button native-type="submit" type="primary">
        提交申请
        <AppIcon name="arrow" :size="15" />
      </el-button>
    </footer>
  </el-form>
</template>
