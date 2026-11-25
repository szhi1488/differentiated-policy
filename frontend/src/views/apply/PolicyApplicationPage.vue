<script setup>
import './policy-form.css'
import { computed, inject, nextTick, ref, watch, onMounted, onScopeDispose } from 'vue'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import AppIcon from '../../components/AppIcon.vue'
import { policyStatusLabel } from '../../constants/policy'
import { occupiedCommonConfigurations } from '../../utils/policy-form'
import PolicyGoodsPicker from './components/PolicyGoodsPicker.vue'
import PolicyModelCard from './components/PolicyModelCard.vue'
import PolicyBasicSection from './components/PolicyBasicSection.vue'
import PolicyTimeSection from './components/PolicyTimeSection.vue'
import PolicyAttachmentsSection from './components/PolicyAttachmentsSection.vue'
import PolicyRejectionNotice from './components/PolicyRejectionNotice.vue'
import PolicySubmissionCoverage from './components/PolicySubmissionCoverage.vue'
const {
  submissionPreview,
  confirmSubmission,
  form,
  files,
  attachmentNotice,
  retryFile,
  notice,
  noticeType,
  confirming,
  record,
  busy,
  loading,
  writing,
  locked,
  loadError,
  conflict,
  readOnly,
  reloadDraft,
  saveDraft: persistDraft,
  platforms,
  selectedPlatforms,
  removedPlatforms,
  switchChannel,
  togglePlatform,
  selectAll,
  addGoods,
  addSpecialGood,
  addFiles,
  removeFile,
  reset,
  clear,
  submit,
  updateField,
  setModelPlatforms,
  updateModelPlatform,
  addSubsidy,
  updateSubsidy,
  removeSubsidy,
  removeModel,
  removeConfiguration,
} = inject('policyForm')
const { data: assigned, error: platformError, refreshPlatforms } = inject('platformPermissions')
function onVisible() {
  if (!document.hidden) {
    void refreshPlatforms()
  }
}
onMounted(() => {
  void refreshPlatforms()
  window.addEventListener('focus', onVisible)
  document.addEventListener('visibilitychange', onVisible)
})
onScopeDispose(() => {
  window.removeEventListener('focus', onVisible)
  document.removeEventListener('visibilitychange', onVisible)
})
const platformBlocked = computed(() => !assigned.value || removedPlatforms.value.length > 0)
const submissionDisabled = computed(
  () => confirming.value || conflict.value || platformBlocked.value || !platforms.value.length,
)
const route = useRoute()
const router = useRouter()
const revisionRound = computed(() =>
  route.name === 'apply' && route.query.round ? Number(route.query.round) : null,
)
const revisionReady = ref(false)
const revisionError = ref('')
let revisionRequest
async function checkRevision(id, round, force = false) {
  revisionRequest?.abort()
  revisionRequest = new AbortController()
  const { signal } = revisionRequest
  const previouslyReady = revisionReady.value
  revisionReady.value = false
  revisionError.value = ''
  try {
    const loaded = await reloadDraft(id, force, round, signal)
    if (!signal.aborted) {
      revisionReady.value = loaded || (force && previouslyReady)
      if (!revisionReady.value) {
        revisionError.value = '当前填写内容已保留，可重新核对退回修改任务。'
      }
    }
  } catch (cause) {
    if (!signal.aborted) {
      revisionError.value = cause.message
    }
  }
}
watch(
  () => [route.query.policyId, revisionRound.value],
  ([id, round], previous, onCleanup) => {
    if (round) {
      void checkRevision(id, round)
    }
    onCleanup(() => revisionRequest?.abort())
  },
  { immediate: true },
)
// 确认窗口和提交后的清理归属于当前申请页。
onBeforeRouteLeave(() => writing.value !== 'submit')
const goodsPicker = ref(null)
const specifiedIds = computed(() =>
  form.value.cards.flatMap(({ id }) => (id === undefined ? [] : [id])),
)
const commonGoodsCount = computed(() =>
  form.value.cards.reduce(
    (count, model) => count + (model.id === undefined ? model.goods.length : 0),
    0,
  ),
)
const editingCard = computed(() =>
  form.value.cards.find(({ key }) => key === goodsPicker.value?.cardKey),
)
const blockedConfigurations = computed(() =>
  occupiedCommonConfigurations(form.value.cards, editingCard.value?.key),
)
const addedGoodsKeys = computed(() => editingCard.value?.goods.map(({ key }) => key) ?? [])
const initialGoods = computed(
  () =>
    editingCard.value?.goods.filter(({ spuId }) => spuId === goodsPicker.value.initialSpuId) ?? [],
)
watch(
  () => [route.query.policyId, writing.value, revisionRound.value],
  ([id, writingAction]) => {
    if (
      route.name === 'apply' &&
      !revisionRound.value &&
      typeof id === 'string' &&
      !writingAction &&
      (id !== record.value?.id || loadError.value)
    ) {
      void reloadDraft(id, false)
    }
  },
  { immediate: true },
)
watch(
  () => record.value?.id,
  (id) => {
    if (id && route.name === 'apply' && !route.query.policyId) {
      void router.replace({ name: 'apply', query: { ...route.query, policyId: id } })
    }
  },
  { immediate: true },
)
watch(locked, (value) => {
  if (value) {
    goodsPicker.value = null
  }
})

watch(
  [notice, noticeType],
  ([message, type]) => {
    if (message) {
      ElMessage({ message, type, showClose: true })
      notice.value = ''
    }
  },
  { immediate: true, flush: 'post' },
)
watch(
  attachmentNotice,
  (message) => {
    if (message) {
      ElMessage({ message, type: 'warning', showClose: true })
      attachmentNotice.value = ''
    }
  },
  { immediate: true, flush: 'post' },
)
watch(
  loadError,
  (message) => {
    if (message) {
      ElMessage({ message, type: 'error', showClose: true })
    }
  },
  { immediate: true },
)

async function resetForm() {
  if (await reset()) {
    await router.replace({ name: 'apply' })
  }
}

async function saveDraft() {
  if (!platformBlocked.value && (await persistDraft())) {
    ElMessage.success('草稿保存成功')
  }
}

async function submitForm() {
  if (submissionDisabled.value) {
    return
  }
  if (await submit()) {
    // 先移除原申请地址，再清理缓存，避免路由监听重新回填已提交申请。
    await router.replace({ name: 'apply' })
    clear()
    await nextTick()
    window.scrollTo({ top: 0, left: 0, behavior: 'instant' })
  }
}

function reload() {
  const id = route.query.policyId ?? record.value?.id
  if (typeof id === 'string') {
    if (revisionRound.value) {
      void checkRevision(id, revisionRound.value, true)
    } else {
      void reloadDraft(id)
    }
  }
}

watch(form, () => {
  goodsPicker.value = null
})

function editGoods(cardKey, initialSpuId) {
  goodsPicker.value = { cardKey, initialSpuId }
}

async function confirmGoods(goods, removedKeys, mode) {
  const picker = goodsPicker.value
  if (!picker) {
    return
  }
  if (mode === 'SPECIFIED') {
    if (!addSpecialGood(goods[0])) {
      return
    }
  } else if (!(await addGoods(goods, picker.cardKey, removedKeys))) {
    return
  }
  if (goodsPicker.value === picker) {
    goodsPicker.value = null
  }
}

function updateTime(value) {
  const [start = '', end = ''] = value || []
  updateField('startTime', start)
  updateField('endTime', end)
}
</script>
<template>
  <div class="policy-application-page">
    <section v-if="revisionRound && !revisionReady" class="form-section" aria-live="polite">
      <p>{{ revisionError || '正在核对本轮退回修改任务…' }}</p>
      <el-button v-if="!loading" @click="checkRevision(route.query.policyId, revisionRound)">
        重新核对本轮
      </el-button>
      <el-button @click="router.push({ name: 'submissions' })">我的提交</el-button>
    </section>
    <el-form
      v-else
      class="policy-form"
      :model="form"
      :disabled="locked"
      label-position="top"
      @submit.prevent="submitForm()"
    >
      <p v-if="record" class="policy-record-summary">
        申请单号 {{ record.policyCode }} · {{ policyStatusLabel(record) }}
      </p>
      <p v-else-if="loading" class="policy-record-summary" role="status">正在读取申请…</p>
      <el-alert
        v-if="revisionRound && conflict"
        title="服务端版本需重新核对。本地修改和附件已保留，确认读取服务端版本后再继续办理。"
        type="warning"
        :closable="false"
        show-icon
      />
      <PolicyRejectionNotice v-if="record" :policy="record" />
      <PolicyBasicSection
        :form="form"
        :platforms="platforms"
        :assigned="assigned"
        :platform-error="platformError"
        :removed-platforms="removedPlatforms"
        :read-only="readOnly"
        @retry-platforms="refreshPlatforms"
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
            <el-button
              type="primary"
              :disabled="confirming || !selectedPlatforms.length"
              @click="goodsPicker = {}"
            >
              <AppIcon name="plus" :size="15" />
              选择适用商品
            </el-button>
          </div>
        </div>
        <div class="policy-goods-summary">
          <span>配置选品 {{ commonGoodsCount }} 件</span>
          <span v-if="specifiedIds.length">{{ specifiedIds.length }} 件指定商品</span>
        </div>
        <el-empty
          v-if="!form.cards.length"
          class="policy-model-empty"
          :image-size="52"
          :description="
            selectedPlatforms.length ? '请选择整组配置或指定商品' : '请先在基础信息中选择平台'
          "
        />
        <div v-else class="policy-model-list">
          <PolicyModelCard
            v-for="model in form.cards"
            :key="model.key"
            :model="model"
            :platforms="selectedPlatforms"
            :confirming="confirming || locked"
            @set-model-platforms="setModelPlatforms($event, model.key)"
            @update-model-platform="
              (code, field, value) => updateModelPlatform(code, field, value, model.key)
            "
            @edit-goods="editGoods(model.key, $event)"
            @remove-model="removeModel(model.key)"
            @remove-configuration="removeConfiguration($event, model.key)"
            @add-subsidy="addSubsidy(model.key)"
            @update-subsidy="(key, field, value) => updateSubsidy(key, field, value, model.key)"
            @remove-subsidy="removeSubsidy($event, model.key)"
          />
        </div>
        <PolicyGoodsPicker
          v-if="goodsPicker"
          :editing="!!goodsPicker.cardKey"
          :initial-goods="initialGoods"
          :existing-goods="editingCard?.goods ?? []"
          :added-keys="addedGoodsKeys"
          :specified-ids="specifiedIds"
          :blocked-configurations="blockedConfigurations"
          @close="goodsPicker = null"
          @confirm="confirmGoods"
        />
      </section>
      <PolicyAttachmentsSection
        :form="form"
        :files="files"
        :read-only="readOnly"
        @update-field="updateField"
        @add-files="addFiles"
        @remove-file="removeFile"
        @retry-file="retryFile"
      />
      <footer class="form-actions">
        <el-button v-if="!locked" :disabled="confirming" @click="resetForm">
          {{ record ? '新建申请' : '清空表单' }}
        </el-button>
        <el-button
          v-if="!readOnly"
          :disabled="confirming || conflict || platformBlocked"
          :loading="writing === 'save'"
          @click="saveDraft"
        >
          保存草稿
        </el-button>
        <el-button
          v-if="!readOnly"
          native-type="submit"
          type="primary"
          :disabled="submissionDisabled"
          :loading="writing === 'submit'"
        >
          提交申请
          <AppIcon name="arrow" :size="15" />
        </el-button>
      </footer>
    </el-form>
    <PolicySubmissionCoverage
      v-if="submissionPreview"
      :preview="submissionPreview"
      @confirm="confirmSubmission"
      @edit="confirmSubmission()"
    />
    <div
      v-if="(!revisionRound || revisionReady) && (loadError || conflict || readOnly)"
      class="form-actions"
    >
      <el-button v-if="loadError || conflict" :disabled="busy || confirming" @click="reload">
        读取服务端版本
      </el-button>
      <el-button v-if="loadError || readOnly" :disabled="busy || confirming" @click="resetForm">
        新建申请
      </el-button>
    </div>
  </div>
</template>
