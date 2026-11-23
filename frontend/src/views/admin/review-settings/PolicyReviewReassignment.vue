<script setup>
import { computed, onScopeDispose, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import { reassignReviewers } from '../../../api/policy-review-settings'
import { useReviewerSelectionWrite } from '../../../composables/useReviewerSelectionWrite'
import ReviewerPicker from './ReviewerPicker.vue'

const props = defineProps({ round: { type: Object, default: null }, disabled: Boolean })
const emit = defineEmits(['reload', 'busy'])
const source = ref(null)
const target = ref('')
const selection = ref([])
const reason = ref('')
const eligibleRound = computed(() => {
  const r = props.round
  return (
    Number.isInteger(r?.settingsVersion) &&
    r.status === 'PENDING' &&
    r.submissionVersion === r.currentSubmissionVersion &&
    ['PRODUCT_REVIEW', 'PURCHASE_REVIEW'].includes(r.reviewStage)
  )
})
const { saving, error, needsReload, reset, run: write } = useReviewerSelectionWrite()
const group = computed(() => (target.value === 'PRODUCT_REVIEW' ? 'PRODUCT' : 'PURCHASE'))
const original = computed(() => {
  if (!source.value) {
    return []
  }
  const members =
    group.value === 'PRODUCT' ? source.value.productReviewers : source.value.purchaseReviewers
  return members
    .filter((member) => member.memberStatus === 'ACTIVE')
    .map((member) => ({
      id: member.userId,
      name: member.name,
      department: member.department,
    }))
})
const changes = computed(() => {
  const before = new Set(original.value.map((person) => person.id))
  const after = new Set(selection.value.map((person) => person.id))
  return {
    added: selection.value.filter((person) => !before.has(person.id)),
    removed: original.value.filter((person) => !after.has(person.id)),
  }
})
const reasonLength = computed(() => [...reason.value.trim()].length)
const canSave = computed(() =>
  Boolean(
    source.value &&
    !props.disabled &&
    !saving.value &&
    !needsReload.value &&
    reasonLength.value &&
    reasonLength.value <= 512 &&
    selection.value.length &&
    (changes.value.added.length || changes.value.removed.length),
  ),
)
const personLabel = (person) => `${person.name}（${person.department || '部门未填写'}）`

function chooseStage(stage) {
  target.value = stage
  selection.value = [...original.value]
}
function open() {
  if (!eligibleRound.value || props.disabled) {
    return
  }
  reset()
  source.value = props.round
  reason.value = ''
  chooseStage(source.value.reviewStage)
  emit('busy', true)
}
function close() {
  source.value = null
  emit('busy', false)
  if (needsReload.value) {
    emit('reload')
  }
}
watch(
  () => [props.round?.id, props.round?.submissionVersion],
  ([id, version]) => {
    if (source.value && (id !== source.value.id || version !== source.value.submissionVersion)) {
      close()
    }
  },
)
onScopeDispose(() => {
  if (source.value) {
    emit('busy', false)
  }
})

function save() {
  if (!canSave.value) {
    return
  }
  const { id, recordVersion, submissionVersion, reviewStage } = source.value
  return write(
    (signal) =>
      reassignReviewers(
        id,
        {
          expectedStage: reviewStage,
          targetStage: target.value,
          recordVersion,
          submissionVersion,
          assigneeUserIds: selection.value.map((person) => person.id),
          reason: reason.value.trim(),
        },
        signal,
      ),
    (result) => {
      if (
        result?.id !== id ||
        result.submissionVersion !== submissionVersion ||
        result.recordVersion !== recordVersion + 1 ||
        result.reviewStage !== reviewStage
      ) {
        throw new Error('请重新读取本轮审核成员。')
      }
      close()
      ElMessage.success('本轮审核成员已更新')
      emit('reload')
    },
  )
}
</script>

<template>
  <el-button v-if="eligibleRound" size="small" :disabled="disabled" @click="open">
    改派本轮成员
  </el-button>
  <el-dialog
    :model-value="Boolean(source)"
    title="改派本轮审核成员"
    width="min(580px, calc(100% - 24px))"
    append-to-body
    align-center
    destroy-on-close
    :close-on-click-modal="false"
    :close-on-press-escape="!saving"
    :show-close="!saving"
    @update:model-value="!$event && close()"
  >
    <div v-if="source" class="reassign-editor">
      <p class="reassign-meta">
        {{ source.policyCode }} · 第 {{ source.submissionVersion }} 轮 ·
        {{ source.reviewStage === 'PRODUCT_REVIEW' ? '产品运营初审中' : '采购终审中' }}
      </p>
      <el-radio-group
        v-if="source.reviewStage === 'PRODUCT_REVIEW'"
        :model-value="target"
        aria-label="改派环节"
        :disabled="saving"
        @update:model-value="chooseStage"
      >
        <el-radio value="PRODUCT_REVIEW">本轮初审成员</el-radio>
        <el-radio value="PURCHASE_REVIEW">后续终审成员</el-radio>
      </el-radio-group>
      <ReviewerPicker :key="group" v-model="selection" :group="group" :disabled="saving" />
      <div class="reassign-diff" aria-label="改派变化">
        <p>新增：{{ changes.added.map(personLabel).join('、') || '无' }}</p>
        <p>移除：{{ changes.removed.map(personLabel).join('、') || '无' }}</p>
        <small>
          {{
            target === source.reviewStage
              ? '保留成员沿用原待办。'
              : '新增采购本轮先知悉，转入终审后收到待办。'
          }}
        </small>
      </div>
      <label class="reason-field">
        改派原因
        <el-input
          v-model="reason"
          type="textarea"
          aria-label="改派原因"
          :rows="2"
          :disabled="saving"
          placeholder="填写本次成员调整原因，最多 512 字"
        />
      </label>
      <p v-if="reasonLength > 512" role="alert">改派原因最多 512 字。</p>
      <el-alert v-if="error" type="error" :closable="false" :title="error">
        <el-button v-if="needsReload" link @click="close">重新读取本轮详情</el-button>
      </el-alert>
    </div>
    <template #footer>
      <el-button :disabled="saving" @click="close">取消</el-button>
      <el-button type="primary" :disabled="!canSave" :loading="saving" @click="save">
        保存改派
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.reassign-editor {
  display: grid;
  gap: 12px;
  max-height: 63vh;
  overflow-y: auto;
  padding: 2px;
}
.reassign-meta,
.reassign-diff p {
  margin: 0;
}
.reassign-meta,
.reassign-diff small {
  color: #586a82;
}
.reassign-diff {
  display: grid;
  gap: 4px;
  padding: 10px;
  border-radius: 6px;
  background: #f4f7fc;
  overflow-wrap: anywhere;
}
.reason-field {
  display: grid;
  gap: 6px;
}
</style>
