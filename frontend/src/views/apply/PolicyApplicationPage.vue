<script setup>
import './policy-form.css'
import { inject } from 'vue'
import AppIcon from '../../components/AppIcon.vue'
import PolicyDetailsTable from './components/PolicyDetailsTable.vue'
import PolicyBasicSection from './components/PolicyBasicSection.vue'
import PolicyTimeSection from './components/PolicyTimeSection.vue'
import PolicyAttachmentsSection from './components/PolicyAttachmentsSection.vue'
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
  removeFile,
  reset,
  submit,
  updateField,
  updateDetail,
  removeDetail,
} = inject('policyForm')
function updateTime(value) {
  const [start = '', end = ''] = value || []
  updateField('startTime', start)
  updateField('endTime', end)
}
</script>
<template>
  <el-form class="policy-form" :model="form" label-position="top" @submit.prevent="submit">
    <PolicyBasicSection
      :form="form"
      :platforms="platforms"
      @update-field="updateField"
      @switch-channel="switchChannel"
      @toggle-platform="togglePlatform"
      @select-all="selectAll"
    />
    <PolicyTimeSection :form="form" @update-field="updateField" @update-time="updateTime" />
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
        :rows="rows"
        @update-detail="updateDetail"
        @remove-detail="removeDetail"
        :platform-name="activePlatformName"
        :has-platform="Boolean(activePlatform)"
      />
    </section>
    <PolicyAttachmentsSection
      :form="form"
      :files="files"
      @update-field="updateField"
      @add-files="addFiles"
      @remove-file="removeFile"
    />
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
