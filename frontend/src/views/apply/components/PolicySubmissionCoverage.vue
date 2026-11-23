<script setup>
import { computed, onScopeDispose, reactive, ref, watch } from 'vue'
import { deleteAttachment } from '../../../api/attachments'
import AppIcon from '../../../components/AppIcon.vue'
import { PLATFORM_LABELS } from '../../../constants/policy'
import { groupSubmissionScopes } from '../../../utils/policy-applicability'
import PolicyEndAdjustmentEditor from './PolicyEndAdjustmentEditor.vue'
import PolicyScopeSegment from './PolicyScopeSegment.vue'
import ScopeProductList from './ScopeProductList.vue'

const props = defineProps({ preview: { type: Object, required: true } })
const emit = defineEmits(['confirm', 'edit'])
const recalculated = ref(null)
const current = computed(() => recalculated.value ?? props.preview)
const changes = ref(props.preview.previousAdjustments ?? [])
let submittedFiles = false
function discardRemovedFiles(previous, next) {
  const fileIds = (items) =>
    new Set(items.flatMap((item) => (item.attachmentInfo ?? []).map((file) => file.id)))
  const keep = fileIds(next)
  // 预览尚未绑定文件。已被草稿或历史轮次引用的文件由服务端保留。
  return Promise.allSettled(
    [...fileIds(previous)].filter((id) => !keep.has(id)).map((id) => deleteAttachment(id)),
  )
}
onScopeDispose(() => {
  if (!submittedFiles) {
    void discardRemovedFiles(changes.value, [])
  }
})
const needsAdjustmentReview = ref(Boolean(changes.value.length))
const editingAdjustments = ref(Boolean(props.preview.openEndEditor) || needsAdjustmentReview.value)
const refreshedOptions = ref(null)
const options = computed(
  () => refreshedOptions.value ?? current.value.options ?? props.preview.options ?? [],
)
const staleChanges = computed(() =>
  changes.value.some(
    ({ target }) =>
      !options.value.some(
        (option) =>
          option.target.policyId === target.policyId &&
          option.target.auditLogId === target.auditLogId &&
          option.target.previousEnd === target.previousEnd,
      ),
  ),
)
const direct = computed(() => !current.value.ranges.length && !changes.value.length)
async function recalculate(rows) {
  let next
  try {
    next = await props.preview.recalculate(rows)
  } catch (error) {
    if (error.code === 'POLICY_ADJUSTMENT_CHANGED') {
      const latest = await props.preview.recalculate([])
      refreshedOptions.value = latest.options
    }
    throw error
  }
  const unchanged = new Map(next.ranges.map((range) => [range.key, range.confirmationBasis]))
  for (const range of current.value.ranges) {
    if (!unchanged.has(range.key) || unchanged.get(range.key) !== range.confirmationBasis) {
      choices.delete(range.key)
    }
  }
  recalculated.value = next
  refreshedOptions.value = next.options ?? refreshedOptions.value
  void discardRemovedFiles(changes.value, rows)
  changes.value = rows
  needsAdjustmentReview.value = false
  page.value = Math.max(1, Math.min(page.value, Math.ceil(cards.value.length / pageSize)))
}
const choices = reactive(new Map())
const showSubmitConfirmation = ref(false)
const page = ref(1)
const pageSize = 10
const cards = computed(() => groupSubmissionScopes(current.value.scopes))
const decisionGroups = computed(() => cards.value.flatMap((card) => card.ranges))
const confirmedCount = computed(
  () =>
    decisionGroups.value.filter((range) => range.members.every((member) => choices.has(member.key)))
      .length,
)
const visiblePlatforms = computed(() => {
  const grouped = new Map()
  for (const scope of cards.value.slice((page.value - 1) * pageSize, page.value * pageSize)) {
    const code = scope.scope.platformCode
    if (!grouped.has(code)) {
      grouped.set(code, [])
    }
    grouped.get(code).push(scope)
  }
  return grouped
})
watch(
  () => props.preview,
  () => {
    showSubmitConfirmation.value = false
    recalculated.value = null
    submittedFiles = false
    changes.value = props.preview.previousAdjustments ?? []
    needsAdjustmentReview.value = Boolean(changes.value.length)
    editingAdjustments.value = Boolean(props.preview.openEndEditor) || needsAdjustmentReview.value
    refreshedOptions.value = null
    choices.clear()
    page.value = 1
  },
)
function closeEndEditor() {
  editingAdjustments.value = false
  if (props.preview.openEndEditor && !recalculated.value) {
    emit('edit')
  }
}
function choose(members, value) {
  for (const { key } of members) {
    if (value) {
      choices.set(key, value)
    } else {
      choices.delete(key)
    }
  }
}
function groupChoice(members) {
  const first = choices.get(members[0].key)
  return members.every(({ key }) => JSON.stringify(choices.get(key)) === JSON.stringify(first))
    ? first
    : ''
}
function selectRetail(members, groupIds, id, checked) {
  for (const member of members) {
    const previous = choices.get(member.key) || []
    const selected = checked
      ? [...previous.filter((value) => !groupIds.includes(value)), id].sort()
      : previous.filter((value) => value !== id)
    if (selected.length) {
      choices.set(member.key, selected)
    } else {
      choices.delete(member.key)
    }
  }
}
function confirm() {
  if (
    editingAdjustments.value ||
    needsAdjustmentReview.value ||
    staleChanges.value ||
    (!direct.value && !showSubmitConfirmation.value) ||
    choices.size !== current.value.ranges.length
  ) {
    return
  }
  showSubmitConfirmation.value = false
  const decisions = current.value.ranges.map(({ key, scope, through }) => {
    const choice = choices.get(key)
    if (Array.isArray(choice)) {
      return { scope, through, choice: 'SELECT', selectedPolicyIds: choice }
    }
    return choice === 'STACK'
      ? { scope, through, choice }
      : { scope, through, choice: 'CHOOSE_ONE', policyId: choice }
  })
  submittedFiles = true
  emit(
    'confirm',
    recalculated.value
      ? {
          decisions,
          confirmationToken: current.value.confirmationToken,
          endAdjustments: changes.value,
        }
      : decisions,
  )
}
</script>

<template>
  <el-dialog
    v-if="!preview.openEndEditor || recalculated"
    :model-value="true"
    title="确认政策适用范围"
    class="submission-coverage-dialog"
    :width="
      current.ranges.length ? 'min(1000px, calc(100% - 20px))' : 'min(600px, calc(100% - 24px))'
    "
    align-center
    :close-on-click-modal="false"
    :close-on-press-escape="!editingAdjustments && !showSubmitConfirmation"
    @update:model-value="emit('edit')"
  >
    <el-alert
      v-if="preview.changed"
      :title="
        needsAdjustmentReview
          ? '政策已变化，请重新核对时间调整和适用范围。'
          : '适用范围已变化，请按最新结果重新确认各时段。'
      "
      type="warning"
      :closable="false"
      show-icon
    />
    <div v-if="options.length || changes.length" class="scope-adjustment-action">
      <div>
        <strong>其他政策</strong>
        <p v-if="staleChanges || needsAdjustmentReview" role="alert">
          已选政策发生变化，请重新核对时间调整。
        </p>
        <p v-else-if="changes.length" aria-live="polite">
          本次申请调整 {{ changes.length }} 张政策的截止时间
        </p>
        <p v-else>可一并申请调整负责平台上已通过政策的截止时间。</p>
      </div>
      <el-button type="primary" plain @click="editingAdjustments = true">
        <AppIcon name="stop" :size="16" />
        {{ changes.length ? '修改时间调整' : '调整政策时间' }}
      </el-button>
    </div>
    <section v-for="[code, scopes] in visiblePlatforms" :key="code" class="scope-platform">
      <h3>{{ PLATFORM_LABELS[code] || code }}</h3>
      <div v-for="scope in scopes" :key="scope.key" class="scope-card">
        <header class="scope-heading">
          <strong>{{ scope.title }}</strong>
          <ScopeProductList v-if="scope.commonConfiguration" :products="scope.products" />
          <span v-else>{{ scope.target }}</span>
        </header>
        <PolicyScopeSegment
          v-for="range in scope.ranges"
          :key="range.key"
          :range="range"
          :common-configuration="Boolean(scope.commonConfiguration)"
          :show-subset="
            Boolean(scope.commonConfiguration) && range.members.length < scope.products.length
          "
          :choice="groupChoice(range.members)"
          :retail-selections="range.members.map((member) => choices.get(member.key) || [])"
          :partly-confirmed="
            range.members.some((member) => choices.has(member.key)) && !groupChoice(range.members)
          "
          @choose="choose(range.members, $event)"
          @select-retail="
            (groupIds, id, checked) => selectRetail(range.members, groupIds, id, checked)
          "
        />
      </div>
    </section>
    <p v-if="!current.ranges.length" class="scope-direct">
      <AppIcon name="check" :size="18" />
      {{
        changes.length ? '调整后没有交叉时段，可确认提交。' : '本次政策没有交叉时段，可直接提交。'
      }}
    </p>
    <el-pagination
      v-if="cards.length > pageSize"
      v-model:current-page="page"
      :page-size="pageSize"
      :total="cards.length"
      :pager-count="5"
      layout="prev, pager, next"
      size="small"
    />
    <template #footer>
      <div class="scope-footer">
        <span v-if="current.ranges.length" aria-live="polite">
          整单已确认
          <strong>{{ confirmedCount }} / {{ decisionGroups.length }}</strong>
          项
        </span>
        <div>
          <el-button @click="emit('edit')">返回编辑</el-button>
          <el-button
            type="primary"
            :disabled="
              needsAdjustmentReview || staleChanges || choices.size !== current.ranges.length
            "
            @click="direct ? confirm() : (showSubmitConfirmation = true)"
          >
            {{ direct ? '直接提交' : '确认整单并提交' }}
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
  <PolicyEndAdjustmentEditor
    v-if="editingAdjustments"
    :options="options"
    :changes="changes"
    :apply="recalculate"
    @close="closeEndEditor"
  />
  <el-dialog
    v-model="showSubmitConfirmation"
    title="确认提交整单？"
    width="min(420px, calc(100% - 24px))"
    align-center
    :close-on-click-modal="false"
  >
    提交后，申请将进入待审核状态，本轮选择将无法修改。
    <template v-if="changes.length">
      <p>本轮共 {{ changes.length }} 条政策时间调整，审核通过后生效。</p>
      <ul>
        <li v-for="change in changes" :key="change.target.policyId">
          ZC-{{ change.target.policyId.padStart(6, '0') }} ·
          {{ change.endTime > change.target.previousEnd ? '延长至' : '缩短至' }}
          {{ change.endTime.slice(0, 19).replace('T', ' ') }}
        </li>
      </ul>
    </template>
    <template #footer>
      <el-button @click="showSubmitConfirmation = false">返回核对</el-button>
      <el-button type="primary" @click="confirm">确认提交</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.scope-adjustment-action {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px 12px;
  border: 1px solid #dce6f4;
  border-radius: 8px;
  padding: 12px 14px;
  margin-bottom: 14px;
}
.scope-adjustment-action strong {
  color: #173a65;
}
.scope-adjustment-action p {
  margin: 3px 0 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--policy-muted);
}
.scope-adjustment-action .el-button svg {
  margin-inline-end: 5px;
}
.scope-direct {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--el-text-color-regular);
  margin: 6px 0;
}
.scope-direct svg {
  color: var(--el-color-success);
  flex: none;
}
.scope-footer > div {
  margin-inline-start: auto;
}

.scope-platform h3 {
  margin: 16px 0 8px;
  color: #1d4778;
  font-size: 16px;
}
.scope-card {
  border: 1px solid var(--policy-line);
  border-radius: 8px;
  overflow: hidden;
  margin: 8px 0 14px;
}
.scope-heading,
.scope-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 4px 12px;
}
.scope-heading {
  padding: 10px 18px;
  background: #edf4fc;
  color: #173a65;
  overflow-wrap: anywhere;
}
.scope-heading span,
.scope-footer span {
  color: var(--policy-muted);
  font-size: 13px;
}
.scope-footer strong {
  color: #173a65;
}
@media (max-width: 660px) {
  .scope-heading {
    padding: 9px 12px;
  }
  .scope-footer {
    gap: 8px;
  }
}
</style>
