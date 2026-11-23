<script setup>
import { computed, onMounted, onScopeDispose, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import AppIcon from '../../../components/AppIcon.vue'
import PolicyBanner from '../../../layouts/components/PolicyBanner.vue'
import { readAdminPolicy, reviewAdminPolicy } from '../../../api/admin-policies'
import { REVIEW_STAGE_LABELS } from '../../../constants/review-workflow'
import { useAdminPolicies } from '../../../composables/useAdminPolicies'
import { useLatestQuery } from '../../../composables/useLatestQuery'
import {
  CHANNEL_LABELS,
  PLATFORM_LABELS,
  POLICY_STATUS_LABELS,
  POLICY_STATUS_TYPES,
  SCENE_LABELS,
  VERIFY_LABELS,
} from '../../../constants/policy'
import { policySceneLabel, policyTime } from '../../../utils/policy-adapter'
import AdminPolicyDetail from './AdminPolicyDetail.vue'
import AdminPolicyHistory from './AdminPolicyHistory.vue'
import PolicyReviewReassignment from '../review-settings/PolicyReviewReassignment.vue'
import './admin-policies.css'

const {
  filters,
  status,
  tabs,
  page,
  pageSize,
  total,
  visibleRows,
  search,
  reset,
  reload,
  listBusy,
  listError,
} = useAdminPolicies()
const filterFields = [
  { key: 'perspective', label: '渠道', options: CHANNEL_LABELS },
  { key: 'platformCode', label: '平台', options: PLATFORM_LABELS },
  { key: 'verifyType', label: '核销类型', options: VERIFY_LABELS },
  { key: 'sceneType', label: '涉及场景', options: SCENE_LABELS },
  { key: 'reviewStage', label: '审核阶段', options: REVIEW_STAGE_LABELS },
]
const detailOpen = ref(false)
const detailTab = ref('current')
// 办理目标取自用户点击的列表行。重新聚焦、并发读取或失败重试均不可把它换成新轮。
const reviewTarget = ref(null)
const selectedId = ref('')
const { data: detail, error: detailError, loading: detailBusy, run: runDetail } = useLatestQuery()
const selectedPolicy = computed(() => detail.value?.policy)
const roundChanged = computed(() => {
  const target = reviewTarget.value
  const policy = selectedPolicy.value
  return Boolean(
    target && policy && (policy.id !== target.id || policy.submissionVersion !== target.version),
  )
})
const canReview = computed(() => {
  const round = detail.value?.round
  return (
    reviewTarget.value?.active &&
    !roundChanged.value &&
    selectedPolicy.value?.reviewable &&
    (!round ||
      (round.reviewStage === 'PURCHASE_REVIEW' &&
        round.allowedActions.includes('APPROVE') &&
        round.allowedActions.includes('REJECT')))
  )
})
const rejectionOpen = ref(false)
const rejectionReason = ref('')
const rejectionInput = ref(null)
const reviewBusy = ref(false)
const reassignBusy = ref(false)
const reviewError = ref(null)
const reviewNeedsReload = computed(
  () =>
    [401, 403, 404, 409].includes(reviewError.value?.status) ||
    reviewError.value?.status >= 500 ||
    ['network', 'timeout', 'parse'].includes(reviewError.value?.kind),
)
let reviewRequest
watch(
  detail,
  (latest) => {
    if (latest && reviewTarget.value?.active && !canReview.value) {
      rejectionOpen.value = false
      reviewTarget.value.active = false
    }
  },
  { flush: 'sync' },
)
onMounted(() => window.addEventListener('focus', refreshOnReturn))
onScopeDispose(() => {
  reviewRequest?.abort()
  window.removeEventListener('focus', refreshOnReturn)
})

function refreshOnReturn() {
  if (reviewBusy.value || reassignBusy.value) {
    return
  }
  void reload()
  if (detailOpen.value) {
    void loadDetail()
  }
}

function showDetail(row, mode = 'view') {
  if (reviewBusy.value || reassignBusy.value) {
    return
  }
  selectedId.value = row.id
  detailTab.value = 'current'
  reviewTarget.value =
    mode === 'review' ? { id: row.id, version: row.submissionVersion, active: true } : null
  rejectionOpen.value = false
  rejectionReason.value = ''
  reviewError.value = null
  detailOpen.value = true
  void loadDetail()
}

function loadDetail() {
  return runDetail((signal) => readAdminPolicy(selectedId.value, signal))
}

async function submitReview(decision) {
  const policy = selectedPolicy.value
  if (
    !detailOpen.value ||
    reviewBusy.value ||
    reassignBusy.value ||
    reviewNeedsReload.value ||
    !canReview.value
  ) {
    return
  }
  reviewBusy.value = true
  reviewError.value = null
  const controller = new AbortController()
  reviewRequest = controller
  try {
    await reviewAdminPolicy(
      policy.id,
      {
        recordVersion: policy.recordVersion,
        submissionVersion: policy.submissionVersion,
        decision,
        reviewRemark: decision === 'REJECT' ? rejectionReason.value : null,
      },
      controller.signal,
    )
    if (!controller.signal.aborted) {
      ElMessage.success(decision === 'APPROVE' ? '政策已通过' : '政策已驳回')
      rejectionOpen.value = false
      reviewTarget.value.active = false
      void reload()
      await loadDetail()
    }
  } catch (error) {
    if (!controller.signal.aborted) {
      reviewError.value = error
      if (error.status === 409) {
        await loadDetail()
        void reload()
      }
    }
  } finally {
    reviewBusy.value = false
  }
}

function reloadReview() {
  rejectionOpen.value = false
  reviewError.value = null
  void loadDetail()
  void reload()
}

function returnToList() {
  detailOpen.value = false
  void reload()
}
</script>

<template>
  <section class="admin-policies" aria-label="差异化政策管理">
    <PolicyBanner class="admin-policy-banner" />

    <section class="admin-policy-surface" aria-label="政策列表">
      <el-tabs v-model="status" class="admin-policy-tabs" aria-label="审核状态">
        <el-tab-pane
          v-for="tab in tabs"
          :key="tab.value"
          :name="tab.value"
          :label="`${tab.label} (${tab.count})`"
        />
      </el-tabs>
      <div class="admin-policy-list">
        <form class="admin-policy-filters" aria-label="筛选政策" @submit.prevent="search">
          <div class="admin-policy-filter-grid">
            <div v-for="field in filterFields" :key="field.key" class="admin-policy-filter">
              <span>{{ field.label }}</span>
              <el-select
                v-model="filters[field.key]"
                :aria-label="field.label"
                placeholder="全部"
                clearable
              >
                <el-option
                  v-for="(label, value) in field.options"
                  :key="value"
                  :label="label"
                  :value="value"
                />
              </el-select>
            </div>
          </div>
          <div class="admin-policy-search-row">
            <div class="admin-policy-filter admin-policy-keyword-filter">
              <span>关键词</span>
              <el-input
                v-model="filters.keyword"
                clearable
                aria-label="政策关键词"
                placeholder="输入政策编号或机型"
                maxlength="128"
              >
                <template #prefix><AppIcon name="search" :size="16" /></template>
              </el-input>
            </div>
            <div class="admin-policy-filter">
              <span>提交人</span>
              <el-input
                v-model="filters.applicant"
                aria-label="提交人"
                placeholder="输入姓名"
                clearable
                maxlength="128"
              />
            </div>
            <div class="admin-policy-filter admin-policy-date-filter">
              <span>提交日期</span>
              <el-date-picker
                v-model="filters.submittedDates"
                class="admin-policy-dates"
                type="daterange"
                :editable="false"
                start-label="提交开始日期"
                end-label="提交结束日期"
                start-placeholder="开始日期"
                end-placeholder="结束日期"
                value-format="YYYY-MM-DD"
                range-separator="至"
                popper-class="admin-policy-date-popper"
              />
            </div>
            <div class="admin-policy-filter-actions">
              <el-button @click="reset">重置</el-button>
              <el-button type="primary" native-type="submit">
                <AppIcon name="search" :size="16" />
                查询
              </el-button>
            </div>
          </div>
        </form>

        <el-alert
          v-if="listError"
          :title="listError.message"
          type="error"
          :closable="false"
          show-icon
        >
          <el-button link type="primary" @click="reload">重试查询</el-button>
        </el-alert>
        <el-table
          v-else
          v-loading="listBusy"
          :data="visibleRows"
          row-key="id"
          class="admin-policy-table"
        >
          <el-table-column prop="policyCode" label="政策编号" min-width="154" />
          <el-table-column label="渠道" min-width="92">
            <template #default="{ row }">{{ CHANNEL_LABELS[row.perspective] }}</template>
          </el-table-column>
          <el-table-column label="平台" min-width="92">
            <template #default="{ row }">
              {{ row.platforms.map((code) => PLATFORM_LABELS[code]).join('、') }}
            </template>
          </el-table-column>
          <el-table-column label="核销类型" min-width="92">
            <template #default="{ row }">{{ VERIFY_LABELS[row.verifyType] ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="涉及场景" min-width="84">
            <template #default="{ row }">{{ policySceneLabel(row) }}</template>
          </el-table-column>
          <el-table-column prop="applicant" label="提交人" min-width="76" />
          <el-table-column prop="submittedAt" label="提交时间" min-width="164" />
          <el-table-column label="审核状态" min-width="94">
            <template #default="{ row }">
              <el-tag :type="POLICY_STATUS_TYPES[row.status]" size="small" effect="light">
                {{ POLICY_STATUS_LABELS[row.status] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="当前阶段" min-width="110">
            <template #default="{ row }">
              {{ REVIEW_STAGE_LABELS[row.reviewStage] ?? '—' }}
            </template>
          </el-table-column>
          <el-table-column label="初审办理人" min-width="168">
            <template #default="{ row }">
              <template v-if="row.productReviewerName">
                {{ row.productReviewerName }}
                <small class="admin-policy-signed-at">
                  {{ policyTime(row.productReviewedAt).replace('T', ' ') }}
                </small>
              </template>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="104" fixed="right" :resizable="false">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                :aria-label="`查看政策 ${row.policyCode}`"
                @click="showDetail(row)"
              >
                查看
              </el-button>
              <el-button
                v-if="row.reviewable"
                link
                type="primary"
                :aria-label="`审核政策 ${row.policyCode}`"
                @click="showDetail(row, 'review')"
              >
                审核
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty :image-size="66" description="没有符合条件的政策" />
          </template>
        </el-table>

        <footer class="admin-policy-footer">
          <span v-if="!listError" aria-live="polite">共 {{ total }} 条政策</span>
          <el-pagination
            v-if="total && !listError"
            v-model:current-page="page"
            v-model:page-size="pageSize"
            :total="total"
            :page-sizes="[5, 10, 20]"
            :pager-count="5"
            layout="prev, pager, next, sizes"
            size="small"
            background
          />
        </footer>
      </div>
    </section>

    <el-dialog
      key="policy-detail"
      v-model="detailOpen"
      class="admin-policy-dialog"
      width="min(1280px, calc(100% - 24px))"
      align-center
      destroy-on-close
      :close-on-click-modal="!reviewBusy && !reassignBusy"
      :close-on-press-escape="!reviewBusy && !reassignBusy"
      :show-close="!reviewBusy && !reassignBusy"
    >
      <template #header="{ titleId, titleClass }">
        <div class="admin-policy-detail-header">
          <AppIcon name="document" :size="20" />
          <h2 :id="titleId" :class="titleClass">
            {{ reviewTarget?.active ? '政策审核' : '政策详情' }}
          </h2>
          <span class="admin-policy-detail-code">{{ selectedPolicy?.policyCode }}</span>
        </div>
      </template>
      <div
        v-if="detailBusy || detailError || (reviewError && !rejectionOpen)"
        class="admin-policy-detail-section"
      >
        <p v-if="detailBusy" role="status">正在读取政策…</p>
        <el-alert
          v-if="detailError || reviewError"
          :title="(detailError ?? reviewError).message"
          type="error"
          :closable="false"
          show-icon
        >
          <el-button
            v-if="detailError || reviewNeedsReload"
            link
            type="primary"
            @click="reloadReview"
          >
            重新读取政策
          </el-button>
          <p
            v-if="reviewError && rejectionReason.trim() && !rejectionOpen"
            class="admin-policy-note"
          >
            本次填写的意见：{{ rejectionReason }}
          </p>
        </el-alert>
      </div>
      <el-alert
        v-if="roundChanged"
        title="所选提交轮次已变化，请从最新列表重新打开政策。"
        type="warning"
        :closable="false"
        show-icon
      >
        <p v-if="rejectionReason.trim() && !reviewError" class="admin-policy-note">
          本次填写的意见：{{ rejectionReason }}
        </p>
        <el-button link type="primary" @click="returnToList">返回列表并刷新</el-button>
      </el-alert>
      <div v-else-if="detail">
        <el-tabs v-model="detailTab" class="admin-policy-detail-tabs" aria-label="政策详情内容">
          <el-tab-pane label="当前申请" name="current" />
          <el-tab-pane label="审核记录" name="history" />
        </el-tabs>
        <AdminPolicyDetail :key="selectedId" v-if="detailTab === 'current'" :detail="detail">
          <template #member-actions>
            <PolicyReviewReassignment
              :round="detail.round"
              :disabled="reviewBusy || detailBusy || rejectionOpen || reviewNeedsReload"
              @busy="reassignBusy = $event"
              @reload="reloadReview"
            />
          </template>
        </AdminPolicyDetail>
        <AdminPolicyHistory v-else-if="detailOpen" :key="selectedId" :policy-id="selectedId" />
      </div>
      <template v-if="detailTab === 'current' && canReview" #footer>
        <el-button
          type="danger"
          plain
          :disabled="reviewBusy || reassignBusy || reviewNeedsReload"
          @click="rejectionOpen = true"
        >
          驳回
        </el-button>
        <el-button
          type="primary"
          :loading="reviewBusy"
          :disabled="reassignBusy || reviewNeedsReload"
          @click="submitReview('APPROVE')"
        >
          通过
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      key="policy-rejection"
      v-model="rejectionOpen"
      class="admin-policy-rejection"
      title="驳回政策"
      width="min(440px, calc(100% - 24px))"
      align-center
      destroy-on-close
      :close-on-click-modal="false"
      :close-on-press-escape="!reviewBusy"
      :show-close="!reviewBusy"
      :lock-scroll="false"
      @opened="rejectionInput?.focus()"
    >
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="驳回原因">
          <el-input
            ref="rejectionInput"
            v-model="rejectionReason"
            type="textarea"
            :rows="4"
            maxlength="512"
            show-word-limit
            placeholder="请输入驳回原因"
          />
        </el-form-item>
      </el-form>
      <el-alert
        v-if="reviewError"
        :title="reviewError.message"
        type="error"
        :closable="false"
        show-icon
      >
        <el-button v-if="reviewNeedsReload" link type="primary" @click="reloadReview">
          重新读取政策
        </el-button>
      </el-alert>
      <template #footer>
        <el-button :disabled="reviewBusy" @click="rejectionOpen = false">取消</el-button>
        <el-button
          type="danger"
          :loading="reviewBusy"
          :disabled="reviewNeedsReload || !canReview"
          @click="submitReview('REJECT')"
        >
          确认驳回
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>
