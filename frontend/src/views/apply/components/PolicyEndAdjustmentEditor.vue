<script setup>
import { computed, ref } from 'vue'
import AppIcon from '../../../components/AppIcon.vue'
import PolicyAdjustmentEvidence from './PolicyAdjustmentEvidence.vue'
import AttachmentDownload from '../../../components/AttachmentDownload.vue'
import { attachmentDownloadUrl } from '../../../api/attachments'
import { CHANNEL_LABELS } from '../../../constants/policy'
import { policyApiTime, policyTime } from '../../../utils/policy-adapter'
import { timelineTimeFromValue } from '../../../utils/subsidy-timeline'

const props = defineProps({
  options: { type: Array, required: true },
  changes: { type: Array, required: true },
  apply: { type: Function, required: true },
})
const emit = defineEmits(['close'])
const rows = ref(
  props.changes.map((change) => ({
    ...change,
    endTime: policyTime(change.endTime),
    attachmentInfo: change.attachmentInfo ?? [],
  })),
)
const evidenceFields = ref([])
const selected = ref([])
const keyword = ref('')
const reviewing = ref(false)
const busy = ref(false)
const error = ref('')
const optionsById = computed(() => new Map(props.options.map((o) => [o.target.policyId, o])))
const rowIds = computed(() => new Set(rows.value.map((row) => row.target.policyId)))
const available = computed(() =>
  props.options.filter((option) => !rowIds.value.has(option.target.policyId)),
)
const matching = computed(() => {
  const search = keyword.value.trim().toLowerCase()
  return available.value.filter((option) => option.policyCode.toLowerCase().includes(search))
})
const pending = computed(() =>
  selected.value.filter((id) => !rowIds.value.has(id) && optionsById.value.has(id)),
)
const selectedCount = computed(() => {
  const ids = new Set(pending.value)
  return matching.value.filter((option) => ids.has(option.target.policyId)).length
})
function selectMatching(checked) {
  const ids = new Set(matching.value.map((option) => option.target.policyId))
  selected.value = checked
    ? [...new Set([...pending.value, ...ids])]
    : pending.value.filter((id) => !ids.has(id))
}
const removed = computed(() =>
  props.changes.filter(
    (change) => !rows.value.some((row) => row.target.policyId === change.target.policyId),
  ),
)
const optionFor = (row) => optionsById.value.get(row.target.policyId)
const code = (row) => optionFor(row)?.policyCode ?? 'ZC-' + row.target.policyId.padStart(6, '0')
const time = (value) => policyTime(value).replace('T', ' ')
const canShorten = (option) => option.canShorten !== false && option.earliestEnd && option.latestEnd
function extensionMinimum(option) {
  if (!option.canExtend) {
    return ''
  }
  // 延期段从原实际截止的下一秒起算，新截止还须晚于延长段开始时刻。
  const start = Math.max(Date.parse(option.target.previousEnd) + 2000, Date.now())
  if (start > Date.parse('9999-12-31T23:59:59+08:00')) {
    return ''
  }
  return timelineTimeFromValue(start)
}
function allowedPeriod(option, from, through) {
  const extension = extensionMinimum(option)
  return (
    (canShorten(option) &&
      from <= policyTime(option.latestEnd) &&
      through >= policyTime(option.earliestEnd)) ||
    Boolean(extension && through >= extension)
  )
}
function add() {
  for (const id of pending.value) {
    rows.value.push({
      target: optionsById.value.get(id).target,
      endTime: '',
      reason: '',
      attachmentInfo: [],
    })
  }
  selected.value = []
}
function remove(row) {
  rows.value = rows.value.filter((r) => r.target.policyId !== row.target.policyId)
}
function disabledDay(day, row) {
  const date = [
    day.getFullYear(),
    String(day.getMonth() + 1).padStart(2, '0'),
    String(day.getDate()).padStart(2, '0'),
  ].join('-')
  const option = optionFor(row)
  return !allowedPeriod(option, date + 'T00:00:00', date + 'T23:59:59')
}
function endOutside(row) {
  const option = optionFor(row)
  return row.endTime && option && !allowedPeriod(option, row.endTime, row.endTime)
}
const canReview = computed(
  () =>
    pending.value.length === 0 &&
    (rows.value.length > 0 || removed.value.length > 0) &&
    rows.value.every(
      (row) =>
        optionFor(row) &&
        row.endTime &&
        !endOutside(row) &&
        row.reason.trim() &&
        row.reason.trim().length <= 512 &&
        !row.uploadStatus?.busy &&
        !row.uploadStatus?.failed,
    ),
)
function review() {
  if (!canReview.value) {
    return
  }
  error.value = ''
  reviewing.value = true
}
async function applyChanges() {
  if (busy.value || !reviewing.value || !canReview.value) {
    return
  }
  busy.value = true
  error.value = ''
  try {
    await props.apply(
      rows.value.map((row) => ({
        target: optionFor(row).target,
        endTime: policyApiTime(row.endTime),
        reason: row.reason.trim(),
        ...(row.attachmentInfo.length ? { attachmentInfo: row.attachmentInfo } : {}),
      })),
    )
    evidenceFields.value.forEach((field) => field.retainFiles())
    emit('close')
  } catch (cause) {
    error.value = cause.message
    if (cause.code === 'POLICY_ADJUSTMENT_CHANGED') {
      reviewing.value = false
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="true"
    title="调整政策时间"
    modal-class="policy-end-layer"
    class="policy-end-dialog"
    width="min(1120px, calc(100% - 24px))"
    align-center
    :close-on-click-modal="false"
    :close-on-press-escape="!reviewing"
    @update:model-value="!reviewing && emit('close')"
  >
    <div class="end-adjustments end-editor-content">
      <el-alert
        v-if="error && !reviewing"
        :title="error"
        type="error"
        :closable="false"
        show-icon
      />
      <div class="end-workspace">
        <section class="end-picker" aria-label="可选政策">
          <header class="end-section-header">
            <h3>可选政策</h3>
            <span>{{ available.length }} 张</span>
          </header>
          <div class="end-search">
            <el-input
              v-model="keyword"
              clearable
              aria-label="搜索政策单号"
              placeholder="搜索政策单号"
            >
              <template #prefix><AppIcon name="search" :size="16" /></template>
            </el-input>
            <el-checkbox
              :model-value="matching.length > 0 && selectedCount === matching.length"
              :indeterminate="selectedCount > 0 && selectedCount < matching.length"
              :disabled="!matching.length"
              @change="selectMatching"
            >
              全选当前结果
            </el-checkbox>
          </div>
          <div class="end-option-list">
            <el-checkbox-group
              v-if="matching.length"
              v-model="selected"
              aria-label="候选政策"
              class="end-options"
            >
              <el-checkbox
                v-for="option in matching"
                :key="option.target.policyId"
                :value="option.target.policyId"
                class="end-option"
              >
                <span class="end-option-title">
                  <strong>{{ option.policyCode }}</strong>
                  <span class="end-context">{{ CHANNEL_LABELS[option.perspective] }}</span>
                </span>
                <span class="end-option-date">原截止 {{ time(option.target.previousEnd) }}</span>
              </el-checkbox>
            </el-checkbox-group>
            <p v-else class="end-no-options">
              {{ available.length ? '没有匹配的政策单号' : '没有更多可添加的政策' }}
            </p>
          </div>
          <div class="end-picker-actions">
            <span aria-live="polite">已勾选 {{ pending.length }} 张</span>
            <el-button v-if="pending.length" link @click="selected = []">清空勾选</el-button>
            <el-button type="primary" plain :disabled="!pending.length" @click="add">
              添加所选政策
            </el-button>
          </div>
        </section>
        <section class="end-detail" aria-label="调整明细">
          <header class="end-section-header">
            <h3>调整明细</h3>
            <span aria-live="polite">已添加 {{ rows.length }} 张</span>
          </header>
          <div class="end-rows">
            <div v-if="!rows.length" class="end-empty">
              <AppIcon name="list" :size="36" />
              <p>勾选并添加政策后，可逐张填写截止时间和原因。</p>
            </div>
            <article v-for="row in rows" :key="row.target.policyId" class="end-card">
              <header>
                <strong>{{ code(row) }}</strong>
                <span v-if="CHANNEL_LABELS[optionFor(row)?.perspective]" class="end-context">
                  {{ CHANNEL_LABELS[optionFor(row).perspective] }}
                </span>
                <el-button link type="danger" @click="remove(row)">移除调整</el-button>
              </header>
              <template v-if="optionFor(row)">
                <p class="end-hint">原截止 {{ time(optionFor(row).target.previousEnd) }}</p>
                <el-form label-position="top" class="end-fields" @submit.prevent>
                  <div>
                    <el-form-item
                      label="新截止时间（北京时间）"
                      required
                      :error="endOutside(row) ? '请选择允许范围内的截止时间' : ''"
                    >
                      <el-date-picker
                        v-model="row.endTime"
                        type="datetime"
                        format="YYYY-MM-DD HH:mm:ss"
                        value-format="YYYY-MM-DDTHH:mm:ss"
                        :default-value="
                          new Date(
                            policyTime(
                              optionFor(row).latestEnd || extensionMinimum(optionFor(row)),
                            ),
                          )
                        "
                        :disabled-date="(day) => disabledDay(day, row)"
                        aria-label="新截止时间"
                        placeholder="选择日期和时间"
                      />
                    </el-form-item>
                    <p v-if="canShorten(optionFor(row))" class="end-bounds">
                      缩短范围 {{ time(optionFor(row).earliestEnd) }} 至
                      {{ time(optionFor(row).latestEnd) }}
                    </p>
                    <p v-if="extensionMinimum(optionFor(row))" class="end-bounds">
                      可延长至 {{ time(extensionMinimum(optionFor(row))) }} 或以后
                    </p>
                  </div>
                  <PolicyAdjustmentEvidence
                    ref="evidenceFields"
                    v-model:reason="row.reason"
                    v-model:attachment-info="row.attachmentInfo"
                    :disabled="reviewing || busy"
                    @status-change="row.uploadStatus = $event"
                  />
                </el-form>
              </template>
              <el-alert
                v-else
                type="warning"
                title="这张政策已不可调整，请移除后重新核对。"
                :closable="false"
              />
            </article>
          </div>
        </section>
      </div>
    </div>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :disabled="!canReview || reviewing" @click="review">
        核对调整
      </el-button>
    </template>
  </el-dialog>
  <el-dialog
    v-model="reviewing"
    title="确认政策时间调整"
    class="policy-end-confirm-dialog"
    modal-class="policy-end-review-layer"
    width="min(960px, calc(100% - 36px))"
    align-center
    destroy-on-close
    :close-on-click-modal="false"
    :close-on-press-escape="!busy"
    :show-close="!busy"
  >
    <div class="end-adjustments">
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <p v-if="rows.length" class="end-notice">
        <AppIcon name="history" :size="18" />
        审核通过后，缩短后的截止时间适用于旧单的全部平台和商品。延长段按本轮确认的适用范围执行。
      </p>
      <div v-if="rows.length" class="end-review-list">
        <div aria-hidden="true" class="end-review-heading">
          <span>政策单号 / 渠道</span>
          <span>原截止</span>
          <span>新截止（北京时间）</span>
          <span>调整原因及证明文件</span>
        </div>
        <article v-for="row in rows" :key="row.target.policyId" class="end-card end-review-row">
          <header>
            <strong>{{ code(row) }}</strong>
            <span class="end-context">{{ CHANNEL_LABELS[optionFor(row)?.perspective] }}</span>
          </header>
          <dl>
            <dt>原截止</dt>
            <dd>{{ time(optionFor(row)?.target.previousEnd ?? row.target.previousEnd) }}</dd>
            <dt>新截止</dt>
            <dd>
              <strong>{{ time(row.endTime) }}</strong>
              <span class="end-context">
                （{{
                  row.endTime >
                  policyTime(optionFor(row)?.target.previousEnd ?? row.target.previousEnd)
                    ? '延长'
                    : '缩短'
                }}）
              </span>
            </dd>
            <dt>调整原因</dt>
            <dd>
              <div class="end-reason">{{ row.reason.trim() }}</div>
              <div v-for="file in row.attachmentInfo" :key="file.id">
                <AttachmentDownload
                  :url="attachmentDownloadUrl(file.id)"
                  :filename="file.originalName"
                />
              </div>
            </dd>
          </dl>
        </article>
      </div>
      <p v-if="removed.length" class="end-hint">
        本次移除 {{ removed.map(code).join('、') }} 的时间调整。
      </p>
    </div>
    <template #footer>
      <el-button :disabled="busy" @click="reviewing = false">返回修改</el-button>
      <el-button type="primary" :loading="busy" @click="applyChanges">确认调整</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.end-adjustments {
  display: grid;
  gap: 14px;
  color: var(--el-text-color-primary);
}
.end-editor-content {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.end-editor-content > .el-alert {
  flex: none;
}
.end-workspace {
  display: grid;
  grid-template-columns: minmax(250px, 29%) minmax(0, 1fr);
  gap: 16px;
  flex: 1;
  min-height: 0;
}
.end-picker,
.end-detail {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--policy-line);
  border-radius: 8px;
  background: white;
}
.end-section-header {
  display: flex;
  flex: none;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  padding: 11px 14px;
  background: #f4f8fe;
  border-bottom: 1px solid var(--policy-line);
}
.end-section-header h3 {
  margin: 0;
  color: #173a65;
  font-size: 15px;
}
.end-section-header > span,
.end-picker-actions > span {
  font-size: 12px;
  color: var(--policy-muted);
}
.end-search {
  flex: none;
  padding: 12px 14px 0;
}
.end-option-list,
.end-rows {
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  flex: 1;
}
.end-options {
  display: grid;
  padding: 0 6px 6px;
}
.end-option.el-checkbox {
  height: auto;
  min-height: 48px;
  margin: 0;
  padding: 9px 8px;
  border-radius: 5px;
  align-items: flex-start;
}
.end-option:hover {
  background: #f6f9fe;
}
.end-option.is-checked {
  background: #edf5ff;
}
.end-option :deep(.el-checkbox__input) {
  margin-top: 2px;
}
.end-option :deep(.el-checkbox__label) {
  display: grid;
  min-width: 0;
  flex: 1;
  gap: 4px;
  white-space: normal;
}
.end-option-title {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  align-items: baseline;
}
.end-option-title strong {
  color: #254b72;
  font-size: 13px;
}
.end-option-date,
.end-context,
.end-hint,
.end-bounds {
  margin: 0;
  color: var(--policy-muted);
  font-size: 12px;
  line-height: 1.5;
}
.end-no-options {
  margin: 12px 14px;
  font-size: 13px;
  color: var(--policy-muted);
}
.end-picker-actions {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  border-top: 1px solid var(--policy-line);
  padding: 10px 12px;
}
.end-picker-actions .el-button + .el-button {
  margin-inline-start: 0;
}
.end-picker-actions .el-button:last-child {
  margin-inline-start: auto;
}
.end-rows {
  padding: 12px;
  background: #fafcff;
}
.end-rows .end-card + .end-card {
  margin-top: 12px;
}
.end-empty {
  display: grid;
  justify-items: center;
  align-content: center;
  min-height: 100%;
  padding: 18px 10px;
  text-align: center;
  color: var(--policy-muted);
  font-size: 13px;
}
.end-empty svg {
  color: #98acc6;
}
.end-empty p {
  margin: 10px 0 0;
}
.end-card {
  min-width: 0;
  background: white;
}
.end-rows .end-card {
  padding: 14px;
  border: 1px solid var(--policy-line);
  border-radius: 8px;
}
.end-card header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 10px;
}
.end-card header strong {
  color: #173a65;
  font-size: 15px;
}
.end-card header .el-button {
  margin-inline-start: auto;
}
.end-fields {
  display: grid;
  gap: 10px 18px;
  margin-top: 10px;
}
.end-fields > * {
  min-width: 0;
}
.end-fields .el-form-item {
  margin: 0;
}
.end-fields :deep(.el-date-editor.el-input) {
  width: 100%;
}
.end-fields .is-error {
  margin-bottom: 14px;
}
.end-bounds {
  margin-top: 6px;
}
.end-notice {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 0;
  background: #eff5fc;
  padding: 10px 12px;
  color: #244a76;
  border-radius: 6px;
  line-height: 1.6;
}
.end-notice svg {
  flex: none;
  margin-top: 2px;
}
.end-review-list {
  border: 1px solid var(--policy-line);
  border-radius: 8px;
  overflow: hidden;
}
.end-review-heading,
.end-review-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.15fr) minmax(0, 1.15fr) minmax(0, 1.9fr);
  gap: 12px;
  padding: 14px 16px;
}
.end-review-heading {
  background: #f4f8fe;
  font-size: 13px;
  font-weight: 600;
  color: #345375;
}
.end-review-row {
  border-top: 1px solid var(--policy-line);
  align-items: start;
}
.end-review-row header {
  flex-direction: column;
  align-items: flex-start;
}
.end-review-row dl {
  display: contents;
}
.end-review-row dt {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip-path: inset(50%);
}
.end-review-row dd {
  margin: 0;
  overflow-wrap: anywhere;
  line-height: 1.6;
}
.end-review-row .end-reason {
  white-space: pre-wrap;
}
@media (min-width: 1024px) {
  .end-fields {
    grid-template-columns: minmax(200px, 0.86fr) minmax(0, 1.14fr);
  }
}
@media (max-width: 760px) {
  .end-editor-content {
    height: auto;
  }
  .end-workspace {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }
  .end-search {
    padding-top: 8px;
  }
  .end-option-list {
    flex: auto;
    max-height: min(25dvh, 172px);
  }
  .end-rows {
    flex: auto;
    overflow-y: visible;
  }
  .end-empty {
    min-height: 90px;
  }
  .end-review-list {
    border: 0;
    overflow: visible;
  }
  .end-review-heading {
    display: none;
  }
  .end-review-row {
    display: block;
    padding: 12px;
    border: 1px solid var(--policy-line);
    border-radius: 8px;
  }
  .end-review-row + .end-review-row {
    margin-top: 10px;
  }
  .end-review-row header {
    flex-direction: row;
    align-items: baseline;
  }
  .end-review-row dl {
    display: grid;
    grid-template-columns: max-content minmax(0, 1fr);
    gap: 5px 14px;
    margin: 10px 0 0;
  }
  .end-review-row dt {
    position: static;
    width: auto;
    height: auto;
    clip-path: none;
    color: var(--policy-muted);
  }
}
</style>
