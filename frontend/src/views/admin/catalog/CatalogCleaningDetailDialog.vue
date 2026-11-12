<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { GOODS_CLEAN_RESULTS, RAW_GOODS_FIELDS } from '../../../constants/catalog'

const props = defineProps({
  open: Boolean,
  loading: Boolean,
  busy: Boolean,
  conflict: Boolean,
  detail: { type: Object, default: null },
  error: { type: String, default: '' },
  reviewError: { type: String, default: '' },
})
const emit = defineEmits(['close', 'reload', 'review'])
const targetFields = [
  ['seriesName', '系列'],
  ['modelName', '型号'],
  ['spec', '配置'],
  ['colorName', '颜色'],
]
const decision = ref('APPROVE')
const reason = ref('')
const target = reactive({})
const row = computed(() => props.detail?.result)
const reviewable = computed(() => row.value?.status === 'REVIEW_REQUIRED')
const exclusionOnly = computed(() => row.value?.suggestedAction === 'EXCLUDE')
const canSubmit = computed(
  () =>
    reviewable.value &&
    !props.conflict &&
    !props.busy &&
    reason.value.trim() &&
    (decision.value === 'EXCLUDE' || targetFields.every(([key]) => target[key]?.trim())),
)

watch(
  () => props.detail,
  (value) => {
    if (value) {
      decision.value = value.result.suggestedAction === 'EXCLUDE' ? 'EXCLUDE' : 'APPROVE'
      reason.value = ''
      for (const [key] of targetFields) {
        target[key] = value.candidate?.[key] ?? ''
      }
    }
  },
)

function submit() {
  if (canSubmit.value) {
    const body = { decision: decision.value, reason: reason.value.trim() }
    if (decision.value === 'APPROVE') {
      body.target = Object.fromEntries(targetFields.map(([key]) => [key, target[key].trim()]))
    }
    emit('review', body)
  }
}
</script>

<template>
  <el-dialog
    :model-value="open"
    :title="reviewable ? '审核商品' : '查看商品'"
    width="min(680px, calc(100vw - 24px))"
    class="cleaning-detail-dialog"
    :close-on-click-modal="false"
    :close-on-press-escape="!busy"
    :show-close="!busy"
    @update:model-value="(value) => !value && emit('close')"
  >
    <div v-if="loading" class="cleaning-dialog-loading" aria-live="polite">正在读取商品详情…</div>
    <div v-else-if="error">
      <el-alert :title="error" type="error" :closable="false" show-icon />
      <el-button link type="primary" @click="emit('reload')">重新读取</el-button>
    </div>
    <template v-else-if="row">
      <el-descriptions title="来源原文" :column="1" border size="small">
        <el-descriptions-item v-for="[key, label] in RAW_GOODS_FIELDS" :key="key" :label="label">
          {{ detail.original?.[key] || '—' }}
        </el-descriptions-item>
      </el-descriptions>
      <el-descriptions
        title="识别结果"
        :column="1"
        border
        size="small"
        class="cleaning-detail-section"
      >
        <el-descriptions-item label="处理结果">
          {{ GOODS_CLEAN_RESULTS[row.status]?.label ?? row.status }}
        </el-descriptions-item>
        <el-descriptions-item v-for="[key, label] in targetFields" :key="key" :label="label">
          {{ row[key] || (key === 'colorName' && row.colorCandidates?.join(' / ')) || '—' }}
        </el-descriptions-item>
        <el-descriptions-item v-if="detail.candidate?.edition" label="版本">
          {{ detail.candidate.edition }}
        </el-descriptions-item>
        <el-descriptions-item label="识别说明">{{ row.evidence || '—' }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.candidate?.evidence?.length" label="命中证据">
          <ul class="cleaning-evidence">
            <li v-for="item in detail.candidate.evidence" :key="item.field">
              {{ item.field }} · {{ item.value ?? '—' }}
            </li>
          </ul>
        </el-descriptions-item>
      </el-descriptions>
      <el-form
        v-if="reviewable"
        class="cleaning-detail-section"
        label-position="top"
        :disabled="busy"
        @submit.prevent="submit"
      >
        <el-form-item label="审核结果" required>
          <el-radio-group v-model="decision">
            <el-radio value="APPROVE" :disabled="exclusionOnly">通过审核</el-radio>
            <el-radio value="EXCLUDE">确认排除</el-radio>
          </el-radio-group>
        </el-form-item>
        <div v-if="decision === 'APPROVE'" class="cleaning-review-fields">
          <el-form-item v-for="[key, label] in targetFields" :key="key" :label="label" required>
            <el-input v-model="target[key]" :aria-label="label" maxlength="64" />
          </el-form-item>
        </div>
        <el-form-item label="审核原因" required>
          <el-input
            v-model="reason"
            aria-label="审核原因"
            type="textarea"
            :rows="3"
            maxlength="512"
            show-word-limit
          />
        </el-form-item>
        <el-alert
          v-if="reviewError"
          :title="reviewError"
          type="error"
          :closable="false"
          show-icon
        />
        <el-button v-if="conflict" link type="primary" @click="emit('reload')">
          重新读取候选并重填
        </el-button>
      </el-form>
    </template>
    <template #footer>
      <el-button :disabled="busy" @click="emit('close')">关闭</el-button>
      <el-button
        v-if="reviewable"
        type="primary"
        :disabled="!canSubmit"
        :loading="busy"
        @click="submit"
      >
        提交审核
      </el-button>
    </template>
  </el-dialog>
</template>
