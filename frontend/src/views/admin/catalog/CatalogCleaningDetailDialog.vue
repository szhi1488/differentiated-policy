<script setup>
import { computed, reactive, watch } from 'vue'
import {
  CATEGORY_PATH_FIELD,
  GOODS_CLEAN_RESULTS,
  RAW_GOODS_FIELDS,
} from '../../../constants/catalog'

const props = defineProps({
  open: Boolean,
  loading: Boolean,
  busy: Boolean,
  conflict: Boolean,
  detail: { type: Object, default: null },
  error: { type: String, default: '' },
  reviewError: { type: String, default: '' },
})
const emit = defineEmits(['close', 'reload', 'review', 'history'])
const originalFields = RAW_GOODS_FIELDS.flatMap((field) =>
  field[0] === 'cateName' ? [field, CATEGORY_PATH_FIELD] : [field],
)
const targetFields = [
  ['seriesName', '系列'],
  ['modelName', '型号'],
  ['spec', '配置'],
  ['colorName', '颜色'],
]
const target = reactive({})
const row = computed(() => props.detail?.result)
const reviewable = computed(() => row.value?.status === 'REVIEW_REQUIRED')
const exclusionOnly = computed(() => row.value?.suggestedAction === 'EXCLUDE')
const canReview = computed(
  () => reviewable.value && !props.loading && !props.conflict && !props.busy,
)
const canApprove = computed(
  () =>
    canReview.value && !exclusionOnly.value && targetFields.every(([key]) => target[key]?.trim()),
)

watch(
  () => props.detail,
  (value) => {
    if (value) {
      for (const [key] of targetFields) {
        target[key] = value.candidate?.[key] ?? ''
      }
    }
  },
)

function approve() {
  if (canApprove.value) {
    emit('review', {
      decision: 'APPROVE',
      target: Object.fromEntries(targetFields.map(([key]) => [key, target[key].trim()])),
    })
  }
}

function reject() {
  if (canReview.value) {
    emit('review', { decision: 'EXCLUDE' })
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
      <el-descriptions title="来源原文" :column="1" border size="small" class="cleaning-original">
        <el-descriptions-item v-for="[key, label] in originalFields" :key="key" :label="label">
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
        <el-descriptions-item v-if="detail.candidate?.edition" label="商品版本">
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
      <section v-if="reviewable" class="cleaning-detail-section" aria-label="审核">
        <el-form
          class="cleaning-review-fields"
          label-position="top"
          :disabled="busy"
          @submit.prevent
        >
          <el-form-item v-for="[key, label] in targetFields" :key="key" :label="label" required>
            <el-input v-model="target[key]" :aria-label="label" maxlength="64" />
          </el-form-item>
        </el-form>
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
      </section>
    </template>
    <template #footer>
      <div class="cleaning-dialog-footer">
        <div class="cleaning-page-actions">
          <el-button v-if="row" :disabled="busy" @click="emit('history')">处理记录</el-button>
          <el-button :disabled="busy" @click="emit('close')">关闭</el-button>
        </div>
        <div v-if="reviewable" class="cleaning-page-actions">
          <el-button type="danger" plain :disabled="!canReview" @click="reject">
            审核不通过
          </el-button>
          <el-button type="primary" :disabled="!canApprove" @click="approve">审核通过</el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>
