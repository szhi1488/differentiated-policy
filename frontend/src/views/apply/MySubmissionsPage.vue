<script setup>
import { inject, onScopeDispose, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import PolicyEndAdjustmentEditor from './components/PolicyEndAdjustmentEditor.vue'
import { createRequestId } from '../../utils/request-id'
import { RequestError } from '../../api/request'
import {
  applyPolicyTimeAdjustments,
  listPolicyTimeAdjustmentOptions,
  previewPolicyTimeAdjustments,
} from '../../api/policies'
import { usePolicySubmissions } from '../../composables/usePolicySubmissions'
import {
  CHANNEL_LABELS,
  PLATFORM_LABELS,
  POLICY_STATUS_LABELS,
  POLICY_STATUS_TYPES,
  policyStatusLabel,
  policyIsEditable,
} from '../../constants/policy'
import { policyTime } from '../../utils/policy-adapter'
import { readSubmissionPreview } from '../../utils/policy-applicability'
import PolicySubmissionCoverage from './components/PolicySubmissionCoverage.vue'
import PolicySubmissionDrawer from './components/PolicySubmissionDrawer.vue'

const router = useRouter()
const { busy, confirming, writing, preparePolicyEdit, deleteDraft } = inject('policyForm')
const { filters, data, error, loading, refresh, setFilter } = usePolicySubmissions()
const selected = ref(null)
const detailOpen = ref(false)
const deleteTarget = ref(null)
const adjustmentOptions = ref(null)
const loadingOptions = ref(false)
const applying = ref(false)
const optionsError = ref('')
const scopePreview = ref(null)
let finishScope
let approvalRequest
let active = true
let optionsController
let previewController
onBeforeRouteLeave(() => !applying.value)
onScopeDispose(() => {
  active = false
  optionsController?.abort()
  previewController?.abort()
  finishScope?.(null)
})
watch(writing, (value, previous) => {
  if (previous === 'delete' && value !== 'delete') {
    void refresh()
  }
})
const filterOptions = [
  { key: 'status', label: '状态', options: POLICY_STATUS_LABELS },
  { key: 'perspective', label: '渠道', options: CHANNEL_LABELS },
  { key: 'platformCode', label: '整单平台', options: PLATFORM_LABELS },
]

function openDetail(row) {
  selected.value = row
  detailOpen.value = true
}

async function loadAdjustmentOptions() {
  optionsController = new AbortController()
  const result = await listPolicyTimeAdjustmentOptions(optionsController.signal)
  if (!Array.isArray(result)) {
    throw new Error('未能读取可调整的政策，请重试。')
  }
  if (active) {
    adjustmentOptions.value = result
  }
}

async function openAdjustment() {
  if (loadingOptions.value || adjustmentOptions.value) {
    return
  }
  approvalRequest = null
  loadingOptions.value = true
  optionsError.value = ''
  try {
    await loadAdjustmentOptions()
  } catch (cause) {
    if (active) {
      optionsError.value = cause.message
    }
  } finally {
    loadingOptions.value = false
  }
}

async function reviewAdjustments(changes) {
  approvalRequest = null
  previewController = new AbortController()
  try {
    const preview = await previewPolicyTimeAdjustments(changes, previewController.signal)
    if (!active) {
      return false
    }
    const coverage = readSubmissionPreview(preview)
    if (!preview.confirmationToken) {
      throw new Error('未收到本次调整的确认凭证，请重新核对。')
    }
    const decisions = coverage
      ? await new Promise((resolve) => {
          finishScope = resolve
          scopePreview.value = coverage
        })
      : []
    if (!decisions || !active) {
      return false
    }
    approvalRequest = {
      body: { endAdjustments: changes, confirmationToken: preview.confirmationToken, decisions },
      key: createRequestId(),
    }
    return true
  } catch (cause) {
    if (cause.status === 409 && active) {
      await loadAdjustmentOptions()
    }
    throw cause
  }
}

function closeScope(decisions) {
  scopePreview.value = null
  finishScope(decisions)
  finishScope = null
}

async function applyAdjustments() {
  applying.value = true
  try {
    const { body, key } = approvalRequest
    const approved = await applyPolicyTimeAdjustments(body, key)
    if (
      !Array.isArray(approved) ||
      approved.length !== body.endAdjustments.length ||
      !body.endAdjustments.every(({ target }) =>
        approved.some((record) => record?.id === target.policyId && record.status === 'APPROVED'),
      )
    ) {
      throw new RequestError('调整回执不完整。', 'parse')
    }
    ElMessage.success(`已通过 ${body.endAdjustments.length} 张政策的时间调整`)
    void refresh()
  } catch (cause) {
    if (cause.status === 409) {
      await loadAdjustmentOptions()
    }
    if (['network', 'timeout', 'parse'].includes(cause.kind) || cause.status >= 500) {
      throw new Error('调整结果待核对。再次确认将使用同一个提交编号。')
    }
    throw cause
  } finally {
    applying.value = false
  }
}

async function edit(row) {
  if (await preparePolicyEdit(row.id, row.recordVersion)) {
    await router.push({ name: 'apply', query: { policyId: row.id } })
  }
}

async function removeDraft(row) {
  if (busy.value || confirming.value || deleteTarget.value) {
    return
  }
  deleteTarget.value = row.id
  try {
    const deleted = await deleteDraft(row, () =>
      ElMessageBox.confirm(
        `确定永久删除草稿「${row.policyCode}」吗？删除后无法恢复。`,
        '删除草稿',
        {
          type: 'warning',
          confirmButtonText: '删除草稿',
          cancelButtonText: '取消',
        },
      ).then(
        () => active,
        () => false,
      ),
    )
    if (active && deleted) {
      if (selected.value?.id === row.id) {
        detailOpen.value = false
        selected.value = null
      }
      ElMessage.success('草稿已删除')
    }
  } catch (cause) {
    if (active) {
      const unknown = ['network', 'timeout', 'parse'].includes(cause.kind) || cause.status >= 500
      ElMessage.error(unknown ? '删除结果待核对，请刷新列表确认。' : cause.message)
    }
  } finally {
    deleteTarget.value = null
  }
}
</script>

<template>
  <section class="submissions-panel" aria-labelledby="submissions-title">
    <header class="submissions-header">
      <h2 id="submissions-title">我的提交</h2>
      <el-button @click="refresh">刷新</el-button>
    </header>

    <div class="submissions-filters">
      <el-select
        v-for="{ key, label, options } in filterOptions"
        :key="key"
        :model-value="filters[key]"
        :aria-label="label"
        :placeholder="`全部${label}`"
        clearable
        @update:model-value="setFilter(key, $event)"
      >
        <el-option v-for="(name, value) in options" :key="value" :label="name" :value="value" />
      </el-select>
      <el-button
        class="submissions-adjust"
        type="primary"
        :loading="loadingOptions"
        :disabled="busy || confirming"
        @click="openAdjustment"
      >
        调整政策时间
      </el-button>
    </div>
    <el-alert v-if="optionsError" :title="optionsError" type="error" :closable="false" show-icon />

    <p v-if="loading" class="submissions-message" role="status">正在读取申请…</p>
    <el-alert v-else-if="error" type="error" :closable="false" show-icon :title="error.message" />
    <el-empty v-else-if="!data?.items.length" description="没有符合条件的申请" :image-size="90" />
    <template v-else>
      <el-table :data="data.items" row-key="id">
        <el-table-column prop="policyCode" label="申请单号" min-width="196" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="POLICY_STATUS_TYPES[row.status]">
              {{ policyStatusLabel(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="渠道" width="110">
          <template #default="{ row }">{{ CHANNEL_LABELS[row.perspective] }}</template>
        </el-table-column>
        <el-table-column label="整单平台" min-width="135">
          <template #default="{ row }">
            {{
              row.selectedPlatforms.map((code) => PLATFORM_LABELS[code] ?? code).join('、') || '—'
            }}
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="174">
          <template #default="{ row }">{{ policyTime(row.updatedAt).replace('T', ' ') }}</template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="195">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button
              v-if="policyIsEditable(row.status)"
              link
              type="primary"
              :disabled="busy || confirming"
              @click="edit(row)"
            >
              继续编辑
            </el-button>
            <el-button
              v-if="row.status === 'DRAFT' && row.submissionVersion === 0"
              link
              type="danger"
              :disabled="busy || confirming"
              :loading="writing === 'delete' && deleteTarget === row.id"
              :aria-label="`删除草稿 ${row.policyCode}`"
              @click="removeDraft(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <footer class="submissions-footer">
        <span>共 {{ data.total }} 条</span>
        <el-pagination
          v-if="data.total > data.pageSize"
          v-model:current-page="filters.page"
          :page-size="data.pageSize"
          :total="data.total"
          :pager-count="5"
          layout="prev, pager, next"
        />
      </footer>
    </template>

    <PolicyEndAdjustmentEditor
      v-if="adjustmentOptions"
      :prepare-direct-approval="reviewAdjustments"
      :options="adjustmentOptions"
      :changes="[]"
      :apply="applyAdjustments"
      @close="adjustmentOptions = null"
    />
    <PolicySubmissionCoverage
      v-if="scopePreview"
      choosing-adjustment-scope
      :preview="scopePreview"
      @confirm="closeScope"
      @edit="closeScope(null)"
    />
    <el-drawer
      v-if="selected"
      v-model="detailOpen"
      title="申请详情"
      class="policy-submission-drawer"
      size="min(720px, 100%)"
      destroy-on-close
    >
      <PolicySubmissionDrawer
        :key="selected.id"
        :id="selected.id"
        :edit-disabled="busy || confirming"
        @edit="edit"
      />
    </el-drawer>
  </section>
</template>

<style scoped>
.submissions-panel {
  padding: clamp(14px, 2vw, 24px);
  min-width: 0;
  min-height: 400px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  background: #fff;
  overflow: hidden;
}
.submissions-header,
.submissions-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.submissions-header h2 {
  margin: 0;
  font-size: 18px;
}
.submissions-filters {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin: 18px 0;
}
.submissions-filters .el-select {
  flex: 0 1 156px;
  max-width: 100%;
}
.submissions-adjust {
  margin-inline-start: auto;
}
@media (max-width: 660px) {
  .submissions-filters .el-select {
    flex: 1 1 120px;
  }
}
.submissions-footer,
.submissions-message {
  margin-top: 14px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
