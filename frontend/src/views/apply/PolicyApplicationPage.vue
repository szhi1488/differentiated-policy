<script setup>
import './policy-form.css'
import { inject, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '../../components/AppIcon.vue'
import { POLICY_STATUS_LABELS } from '../../constants/policy'
import PolicyGoodsPicker from './components/PolicyGoodsPicker.vue'
import PolicyModelCard from './components/PolicyModelCard.vue'
import PolicyBasicSection from './components/PolicyBasicSection.vue'
import PolicyTimeSection from './components/PolicyTimeSection.vue'
import PolicyAttachmentsSection from './components/PolicyAttachmentsSection.vue'
const {
  form,
  files,
  notice,
  noticeType,
  confirming,
  record,
  busy,
  writing,
  locked,
  loadError,
  conflict,
  readOnly,
  reloadDraft,
  saveDraft,
  platforms,
  selectedPlatforms,
  models,
  switchChannel,
  togglePlatform,
  selectAll,
  addGoods,
  addSpecialGood,
  addFiles,
  removeFile,
  reset,
  submit,
  updateField,
  setModelPlatforms,
  updateModelPlatform,
  addSubsidy,
  updateSubsidy,
  removeSubsidy,
  removeModel,
  removeGoods,
} = inject('policyForm')
const route = useRoute()
const router = useRouter()
const goodsPicker = ref('')
watch(
  () => [route.query.policyId, writing.value],
  ([id, writingAction]) => {
    if (
      route.name === 'apply' &&
      typeof id === 'string' &&
      !writingAction &&
      id !== record.value?.id
    ) {
      void reloadDraft(id, false)
    }
  },
  { immediate: true },
)
watch(
  () => record.value?.id,
  (id) => {
    if (id && route.name === 'apply' && route.query.policyId !== id) {
      void router.replace({ name: 'apply', query: { ...route.query, policyId: id } })
    }
  },
  { immediate: true },
)
watch(locked, (value) => {
  if (value) {
    goodsPicker.value = ''
  }
})

async function resetForm() {
  if (await reset()) {
    await router.replace({ name: 'apply' })
  }
}

function reload() {
  const id = route.query.policyId ?? record.value?.id
  if (typeof id === 'string') {
    void reloadDraft(id)
  }
}

watch(form, () => {
  goodsPicker.value = ''
})

function confirmGoods(goods) {
  if (goodsPicker.value === 'special') {
    addSpecialGood(goods[0])
  } else {
    addGoods(goods)
  }
  goodsPicker.value = ''
}

function updateTime(value) {
  const [start = '', end = ''] = value || []
  updateField('startTime', start)
  updateField('endTime', end)
}
</script>
<template>
  <el-form
    class="policy-form"
    :model="form"
    :disabled="locked"
    label-position="top"
    @submit.prevent="submit"
  >
    <el-alert
      v-if="record || (busy && !writing) || loadError"
      :title="
        loadError ||
        (record
          ? `申请单号 ${record.policyCode} · ${POLICY_STATUS_LABELS[record.status]}`
          : '正在读取申请…')
      "
      :type="loadError ? 'error' : 'info'"
      :closable="false"
      show-icon
    />
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
          三、商品及补贴信息
        </h2>
        <div class="policy-goods-actions">
          <el-button :disabled="!selectedPlatforms.length" @click="goodsPicker = 'special'">
            指定适用机型
          </el-button>
          <el-button
            type="primary"
            :disabled="!selectedPlatforms.length"
            @click="goodsPicker = 'standard'"
          >
            <AppIcon name="plus" :size="15" />
            选择手机型号
          </el-button>
        </div>
      </div>
      <div class="policy-goods-summary">
        <span>已选 {{ form.model?.goods.length ?? 0 }} 件普通商品</span>
        <span v-if="form.specialGoods.length">{{ form.specialGoods.length }} 件指定商品</span>
      </div>
      <el-empty
        v-if="!models.length"
        class="policy-model-empty"
        :image-size="52"
        :description="
          selectedPlatforms.length ? '可一次选择多个机型配置' : '请先在基础信息中选择平台'
        "
      />
      <div v-else class="policy-model-list">
        <PolicyModelCard
          v-for="model in models"
          :key="model.id ?? 'standard'"
          :model="model"
          :platforms="selectedPlatforms"
          :confirming="confirming || locked"
          @set-model-platforms="setModelPlatforms($event, model.id)"
          @update-model-platform="
            (code, field, value) => updateModelPlatform(code, field, value, model.id)
          "
          @remove-model="removeModel(model.id)"
          @remove-goods="removeGoods"
          @add-subsidy="addSubsidy(model.id)"
          @update-subsidy="(key, field, value) => updateSubsidy(key, field, value, model.id)"
          @remove-subsidy="removeSubsidy($event, model.id)"
        />
      </div>
      <PolicyGoodsPicker
        v-if="goodsPicker"
        :special="goodsPicker === 'special'"
        :added-keys="
          goodsPicker === 'special'
            ? form.specialGoods.map(({ id }) => id)
            : (form.model?.goods.map(({ key }) => key) ?? [])
        "
        @close="goodsPicker = ''"
        @confirm="confirmGoods"
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
      :type="noticeType"
      show-icon
      :closable="false"
      role="status"
    />
    <footer class="form-actions">
      <span>请确认政策信息填写准确后提交</span>
      <el-button v-if="!locked" :disabled="confirming" @click="resetForm">
        {{ record ? '新建申请' : '清空表单' }}
      </el-button>
      <el-button
        v-if="!readOnly"
        :disabled="confirming || conflict"
        :loading="writing === 'save'"
        @click="saveDraft"
      >
        保存草稿
      </el-button>
      <el-button
        v-if="!readOnly"
        native-type="submit"
        type="primary"
        :disabled="confirming || conflict"
        :loading="writing === 'submit'"
      >
        提交申请
        <AppIcon name="arrow" :size="15" />
      </el-button>
    </footer>
  </el-form>
  <div v-if="loadError || conflict || readOnly" class="form-actions">
    <el-button v-if="loadError || conflict" :disabled="busy || confirming" @click="reload">
      读取服务端版本
    </el-button>
    <el-button v-if="loadError || readOnly" :disabled="busy || confirming" @click="resetForm">
      新建申请
    </el-button>
  </div>
</template>
