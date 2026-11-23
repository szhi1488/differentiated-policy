<script setup>
import { inject, onScopeDispose, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import PolicyAdjustmentEvidence from './components/PolicyAdjustmentEvidence.vue'
import { createRequestId } from '../../utils/request-id'
import { createPolicyTimeAdjustment } from '../../api/policies'
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
import PolicySubmissionDrawer from './components/PolicySubmissionDrawer.vue'
import PolicyAdjustmentDate from './components/PolicyAdjustmentDate.vue'

const router = useRouter()
const { busy, confirming, writing, preparePolicyEdit, deleteDraft } = inject('policyForm')
const { filters, data, error, loading, refresh, setFilter } = usePolicySubmissions()
const selected = ref(null)
const detailOpen = ref(false)
const deleteTarget = ref(null)
const adjustmentSource = ref(null)
const evidence = ref(null)
const reason = ref('')
const proofFiles = ref([])
const proofStatus = ref({})
const validTime = ref(false)
let createKey
const endTime = ref('')
const creatingAdjustment = ref(false)
const adjustmentError = ref('')
let active = true
onBeforeRouteLeave(() => !creatingAdjustment.value || !adjustmentSource.value)
onScopeDispose(() => {
  active = false
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

function openAdjustment(row) {
  createKey = createRequestId()
  reason.value = ''
  proofFiles.value = []
  proofStatus.value = {}
  validTime.value = false
  adjustmentSource.value = row
  endTime.value = ''
  adjustmentError.value = ''
}

async function createAdjustment() {
  const source = adjustmentSource.value
  if (
    !source ||
    !validTime.value ||
    !reason.value.trim() ||
    proofStatus.value.busy ||
    proofStatus.value.failed ||
    creatingAdjustment.value
  ) {
    return
  }
  creatingAdjustment.value = true
  adjustmentError.value = ''
  try {
    const draft = await createPolicyTimeAdjustment(
      source,
      endTime.value,
      {
        reason: reason.value.trim(),
        attachmentInfo: proofFiles.value,
      },
      createKey,
    )
    evidence.value?.retainFiles()
    if (active) {
      adjustmentSource.value = null
      ElMessage.success(`时间调整草稿 ${draft.policyCode} 已保存`)
      void refresh()
      await edit(draft)
    }
  } catch (cause) {
    if (active) {
      adjustmentError.value = cause.message
      if (cause.code === 'POLICY_EXTENSION_SOURCE_CHANGED') {
        void refresh()
      }
    }
  } finally {
    creatingAdjustment.value = false
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
    </div>

    <p v-if="loading" class="submissions-message" role="status">正在读取申请…</p>
    <el-alert v-else-if="error" type="error" :closable="false" show-icon :title="error.message" />
    <el-empty v-else-if="!data?.items.length" description="没有符合条件的申请" :image-size="90" />
    <template v-else>
      <el-table :data="data.items" row-key="id">
        <el-table-column prop="policyCode" label="申请单号" min-width="196">
          <template #default="{ row }">
            {{ row.policyCode }}
            <div v-if="row.sourcePolicyCode">延期自 {{ row.sourcePolicyCode }}</div>
            <div v-else-if="row.adjustmentOnly">提前结束申请</div>
          </template>
        </el-table-column>
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
              v-if="row.status === 'APPROVED' && (row.canExtend || row.canShorten)"
              link
              type="primary"
              :disabled="busy || confirming"
              @click="openAdjustment(row)"
            >
              调整政策时间
            </el-button>
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

    <el-dialog
      :model-value="Boolean(adjustmentSource)"
      title="调整政策时间"
      width="min(500px, calc(100% - 24px))"
      align-center
      :show-close="!creatingAdjustment"
      :close-on-press-escape="!creatingAdjustment"
      :close-on-click-modal="false"
      @update:model-value="adjustmentSource = null"
    >
      <template v-if="adjustmentSource">
        <p>
          原政策 {{ adjustmentSource.policyCode }} 于
          {{ policyTime(adjustmentSource.endTime).replace('T', ' ') }}
          到期。调整申请将生成新单号，审核通过后生效。
        </p>
        <el-form
          label-position="top"
          :disabled="creatingAdjustment"
          @submit.prevent="createAdjustment"
        >
          <PolicyAdjustmentDate
            v-model="endTime"
            :previous-end="adjustmentSource.endTime"
            :earliest-end="adjustmentSource.earliestEnd"
            :can-extend="adjustmentSource.canExtend"
            :can-shorten="adjustmentSource.canShorten"
            @validity="validTime = $event"
          />
          <PolicyAdjustmentEvidence
            ref="evidence"
            v-model:reason="reason"
            v-model:attachment-info="proofFiles"
            :disabled="creatingAdjustment"
            @status-change="proofStatus = $event"
          />
        </el-form>
        <el-alert v-if="adjustmentError" :title="adjustmentError" type="error" :closable="false" />
      </template>
      <template #footer>
        <el-button :disabled="creatingAdjustment" @click="adjustmentSource = null">取消</el-button>
        <el-button
          type="primary"
          :loading="creatingAdjustment"
          :disabled="!validTime || !reason.trim() || proofStatus.busy || proofStatus.failed"
          @click="createAdjustment"
        >
          创建调整草稿
        </el-button>
      </template>
    </el-dialog>
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
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(94px, 156px));
  gap: 8px;
  margin: 18px 0;
}
.submissions-footer,
.submissions-message {
  margin-top: 14px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
