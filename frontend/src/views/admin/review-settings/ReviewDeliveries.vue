<script setup>
import { computed, ref } from 'vue'
import {
  readDeliveries,
  readExecutionNotices,
  resolveDelivery,
} from '../../../api/policy-review-settings'
import { useLatestQuery } from '../../../composables/useLatestQuery'

const actions = {
  GROUP_MESSAGE: '群通知',
  SEND_CARD: '发送初审卡',
  UPDATE_CARD: '更新初审卡',
  CREATE_TASK: '创建待办',
  CLOSE_TASK: '结办待办',
}
const statuses = {
  PENDING: '等待投递',
  SENDING: '投递中',
  SENT: '已送达',
  RETRY: '等待重试',
  UNKNOWN: '结果待核对',
  FAILED: '投递失败',
  CANCELLED: '已失效',
}
const policy = ref('')
const version = ref('1')
const filter = ref(null)
const validation = ref('')
const { data, error, loading, run } = useLatestQuery()
const selected = ref(null)
const operation = ref('')
const reason = ref('')
const receipt = ref('')
const saveError = ref('')
const saving = ref(false)
const changed = ref(false)
const canConfirm = computed(
  () =>
    selected.value?.status === 'UNKNOWN' &&
    ['CREATE_TASK', 'GROUP_MESSAGE', 'SEND_CARD'].includes(selected.value.action),
)

function refresh() {
  const query = filter.value
  if (query?.notices) {
    return run((signal) => readExecutionNotices(query.before, signal))
  }
  if (query) {
    return run((signal) => readDeliveries(query.id, query.version, signal))
  }
}
function notices(before) {
  validation.value = ''
  filter.value = { notices: true, before }
  return refresh()
}
function search() {
  const id = policy.value.trim().replace(/^ZC-/i, '').replace(/^0+/, '')
  if (!/^[1-9]\d{0,18}$/.test(id) || !/^[1-9]\d{0,9}$/.test(version.value)) {
    validation.value = '请填写有效的政策单号及提交轮次。'
    return
  }
  validation.value = ''
  filter.value = { id, version: version.value }
  return refresh()
}
function open(row) {
  selected.value = row
  operation.value = canConfirm.value ? 'CONFIRM_RECEIPT' : 'RETRY_CONFIRMED'
  reason.value = ''
  receipt.value = ''
  saveError.value = ''
  changed.value = false
}
async function save() {
  const row = selected.value
  if (!row || saving.value || changed.value) {
    return
  }
  if (!reason.value.trim() || (operation.value === 'CONFIRM_RECEIPT' && !receipt.value.trim())) {
    saveError.value = '请填写核对依据及所选操作需要的回执。'
    return
  }
  saving.value = true
  saveError.value = ''
  try {
    await resolveDelivery(row.id, {
      expectedStatus: row.status,
      expectedAttempts: row.attempts,
      expectedRevision: row.revision,
      operation: operation.value,
      reason: reason.value.trim(),
      receiptId: operation.value === 'CONFIRM_RECEIPT' ? receipt.value.trim() : null,
    })
    selected.value = null
    await refresh()
  } catch (cause) {
    changed.value = cause.code === 'POLICY_DELIVERY_CHANGED' || !cause.status || cause.status >= 500
    saveError.value = cause.message
    if (changed.value) {
      await refresh()
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="delivery-panel" aria-label="飞书投递记录">
    <h2>飞书投递记录</h2>
    <form class="delivery-query" @submit.prevent="search">
      <label>
        政策单号
        <el-input v-model="policy" placeholder="例如 ZC-000057" />
      </label>
      <label>
        提交轮次
        <el-input v-model="version" inputmode="numeric" />
      </label>
      <el-button native-type="submit" type="primary" :loading="loading">查询投递</el-button>
      <el-button :loading="loading" @click="notices()">查询业务提醒</el-button>
    </form>
    <p v-if="validation" role="alert">{{ validation }}</p>
    <el-alert v-if="error" type="error" :closable="false" :title="error.message">
      <el-button link @click="refresh">重试查询</el-button>
    </el-alert>
    <p v-else-if="data && !data.length" role="status">暂无投递记录。</p>
    <ul v-else-if="data?.length">
      <li v-for="row in data" :key="row.id">
        <div>
          <strong>{{ actions[row.action] }} · {{ row.recipient }}</strong>
          <span>{{ statuses[row.status] }}</span>
        </div>
        <div v-if="row.assignmentId">指派 {{ row.assignmentId }}</div>
        <div v-if="row.deliveryKey">投递编号 {{ row.deliveryKey }}</div>
        <div v-if="row.nextAttemptAt">可投递时间 {{ row.nextAttemptAt }}</div>
        <div v-if="row.receiptId">飞书回执 {{ row.receiptId }}</div>
        <div v-if="row.error">{{ row.error }}</div>
        <el-button v-if="row.canResolve" link type="primary" @click="open(row)">
          核对并处理
        </el-button>
      </li>
    </ul>
    <el-button v-if="filter?.notices && data?.length === 50" @click="notices(data.at(-1).id)">
      下一页
    </el-button>
    <el-dialog
      :model-value="!!selected"
      title="核对飞书投递"
      width="min(480px, 94vw)"
      :close-on-click-modal="!saving"
      :close-on-press-escape="!saving"
      :show-close="!saving"
      @close="selected = null"
    >
      <template v-if="selected">
        <p>
          {{ actions[selected.action] }} · {{ selected.recipient }} ·
          {{ statuses[selected.status] }}
        </p>
        <el-radio-group v-if="canConfirm" v-model="operation" :disabled="saving || changed">
          <el-radio value="CONFIRM_RECEIPT">已送达，补录回执</el-radio>
          <el-radio value="RETRY_CONFIRMED">已核实未送达，重新投递</el-radio>
        </el-radio-group>
        <p v-else>核实故障已排除后，重新投递这一个动作。</p>
        <label v-if="operation === 'CONFIRM_RECEIPT'">
          飞书消息 ID 或任务 GUID
          <el-input v-model="receipt" maxlength="128" :disabled="saving || changed" />
        </label>
        <label>
          核对依据
          <el-input
            v-model="reason"
            type="textarea"
            maxlength="512"
            :disabled="saving || changed"
          />
        </label>
        <el-alert v-if="saveError" type="error" :title="saveError" :closable="false" />
      </template>
      <template #footer>
        <el-button :disabled="saving" @click="selected = null">关闭</el-button>
        <el-button type="primary" :disabled="changed" :loading="saving" @click="save">
          {{ operation === 'CONFIRM_RECEIPT' ? '核对并保存回执' : '确认重新投递' }}
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.delivery-panel {
  padding: 20px;
  border: 1px solid #dfe7f3;
  border-radius: 10px;
  background: white;
  min-width: 0;
}
h2 {
  margin: 0 0 14px;
  font-size: 18px;
  color: #203651;
}
.delivery-query {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: end;
}
label {
  display: grid;
  gap: 6px;
  font-size: 14px;
}
.delivery-query label {
  width: min(100%, 210px);
}
.delivery-query label:nth-child(2) {
  width: 88px;
}
ul {
  padding: 0;
  list-style: none;
  margin: 14px 0 0;
}
li {
  padding: 10px 0;
  border-top: 1px solid #dfe7f3;
  overflow-wrap: anywhere;
  line-height: 1.6;
  font-size: 14px;
}
li span {
  color: #526782;
  margin-left: 8px;
}
.delivery-panel :deep(.el-dialog__body) {
  display: grid;
  gap: 12px;
}
</style>
