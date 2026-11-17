<script setup>
import { computed, ref, watch } from 'vue'
import { readAdminPolicyAuditLog } from '../../../api/admin-policies'
import { useLatestQuery } from '../../../composables/useLatestQuery'
import { PLATFORM_LABELS } from '../../../constants/policy'
import { policyTime } from '../../../utils/policy-adapter'
import { subsidyChanges, subsidyForGroup, subsidyText } from '../../../utils/policy-coverage'
import AdminPolicyDetail from './AdminPolicyDetail.vue'

defineProps({
  originCode: { type: String, required: true },
})
const row = ref(null)
const opened = ref(false)
const { data, error, loading, run } = useLatestQuery()
const showWhole = ref(false)
function open(value) {
  row.value = value
  showWhole.value = value.showWhole
  opened.value = true
}
function close() {
  opened.value = false
}
defineExpose({ open, close })
function load() {
  const winner = row.value?.winner
  return run(async (signal) => {
    if (!opened.value) {
      return null
    }
    if (!winner.auditLogId) {
      throw new Error('对照轮次信息缺失，请刷新原申请后重试。')
    }
    const result = await readAdminPolicyAuditLog(winner.id, winner.auditLogId, signal)
    if (
      result.log.submissionVersion !== winner.submissionVersion ||
      result.snapshot.id !== winner.id
    ) {
      throw new Error('对照轮次与预览不一致，请刷新原申请后重试。')
    }
    if (
      row.value.members?.length > 1 &&
      !subsidyForGroup(result.snapshot, row.value.members, row.value.platformCode)
    ) {
      throw new Error('本组商品的对照补贴缺失或不一致，请刷新预览后逐项核对。')
    }
    return result
  })
}
watch(
  [opened, row],
  () => {
    void load()
  },
  { immediate: true },
)
const existing = computed(() =>
  subsidyForGroup(data.value?.snapshot, row.value.members ?? [row.value], row.value.platformCode),
)
const changes = computed(() => subsidyChanges(existing.value, row.value.proposedSubsidy))
const detail = computed(() =>
  data.value
    ? {
        policy: { id: row.value.winner.id, status: data.value.log.auditStatus, reviewable: false },
        snapshot: data.value.snapshot,
        audit: data.value.log,
        coverage: null,
      }
    : null,
)
</script>

<template>
  <el-dialog
    v-if="row"
    v-model="opened"
    class="admin-policy-dialog admin-policy-reference-dialog"
    width="min(1280px, calc(100% - 24px))"
    title="补贴与对照政策"
    align-center
    append-to-body
    destroy-on-close
    :close-on-click-modal="false"
  >
    <div class="admin-policy-detail-section reference-comparison" aria-label="补贴对照">
      <h3>{{ row.name }} · {{ PLATFORM_LABELS[row.platformCode] }}</h3>
      <p v-if="row.members?.length > 1">
        本组包含已选 {{ row.colorCount }} 色、{{ row.members.length }}
        件商品，以下补贴对照适用于本组全部商品。
      </p>
      <p>对照第 {{ row.winner.submissionVersion }} 轮提交内容，关闭后返回 {{ originCode }}。</p>
      <p v-if="row.comparisonAt">
        对照时点：{{ policyTime(row.comparisonAt).replace('T', ' ') }}（北京时间）
      </p>
      <p v-if="loading" role="status">正在读取对照政策…</p>
      <el-alert v-else-if="error" :title="error.message" type="error" :closable="false">
        <el-button link type="primary" @click="load">重试读取对照政策</el-button>
      </el-alert>
      <template v-else-if="data">
        <dl class="reference-plans">
          <div>
            <dt>对照政策 · {{ row.winner.policyCode }}</dt>
            <dd>{{ subsidyText(existing) }}</dd>
          </div>
          <div>
            <dt>本申请 · {{ originCode }}</dt>
            <dd>{{ subsidyText(row.proposedSubsidy) }}</dd>
          </div>
        </dl>
        <ul class="reference-changes">
          <li v-for="change in changes" :key="change">{{ change }}</li>
        </ul>
        <el-alert
          v-if="row.outcome === 'RETAINED'"
          title="较新的政策将继续适用，本申请补贴不会替换该范围。"
          type="warning"
          :closable="false"
        />
        <el-alert
          v-else-if="row.outcome === 'REPLACES'"
          title="以上为审核通过后的预览，实际适用结果以审核通过时间及生效条件为准。"
          type="info"
          :closable="false"
        />
        <el-button class="reference-toggle" @click="showWhole = !showWhole">
          {{ showWhole ? '收起整单内容' : '查看对照政策整单内容' }}
        </el-button>
      </template>
    </div>
    <AdminPolicyDetail v-if="detail && showWhole" :detail="detail" />
    <template #footer>
      <el-button type="primary" @click="close">返回原申请</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.reference-comparison p {
  color: var(--policy-muted);
  overflow-wrap: anywhere;
}
.reference-plans {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin: 18px 0;
}
.reference-plans > div {
  padding: 16px;
  border: 1px solid var(--policy-line);
  background: #f8fafd;
  border-radius: 8px;
  min-width: 0;
}
.reference-plans dt {
  color: var(--policy-muted);
  font-size: 12px;
}
.reference-plans dd {
  margin: 10px 0 0;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.reference-changes {
  padding-left: 20px;
  line-height: 1.8;
}
.reference-toggle {
  margin-top: 16px;
}
@media (max-width: 660px) {
  .reference-plans {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
