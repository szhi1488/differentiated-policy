<script setup>
import { computed } from 'vue'
import { useAdminPolicyApproval } from '../../../composables/useAdminPolicyApproval'
import { CHANNEL_LABELS, PLATFORM_LABELS } from '../../../constants/policy'
import { policyTime } from '../../../utils/policy-adapter'
import AdminPolicyCoverage from './AdminPolicyCoverage.vue'

const props = defineProps({
  busy: Boolean,
  reviewError: { type: Object, default: null },
  reviewNeedsReload: Boolean,
})
const emit = defineEmits(['confirm', 'reload', 'show-reference'])
const {
  opened,
  original,
  latest,
  error,
  loading,
  changed,
  acknowledged,
  canConfirm,
  needsReload,
  open,
  close,
  load,
} = useAdminPolicyApproval()
defineExpose({ open, close })
const items = computed(() => latest.value?.coverage.items ?? [])
const goodsCount = computed(() => new Set(items.value.map(({ goodsId }) => goodsId)).size)
const platforms = computed(() => [...new Set(items.value.map(({ platformCode }) => platformCode))])
const outcomes = { NEW: '生效时新增', REPLACES: '生效时覆盖', RETAINED: '较新的申请继续适用' }
const counts = computed(() =>
  items.value.reduce((result, item) => {
    result[item.outcome] = (result[item.outcome] ?? 0) + 1
    return result
  }, {}),
)
function confirm() {
  if (!props.busy && !props.reviewNeedsReload && canConfirm.value) {
    emit('confirm', latest.value.policy)
  }
}
function beforeClose(done) {
  if (!props.busy) {
    close()
    done()
  }
}
</script>

<template>
  <el-dialog
    v-model="opened"
    class="admin-policy-dialog admin-policy-approval-dialog"
    title="确认审核通过"
    :width="changed ? 'min(1120px, calc(100% - 24px))' : 'min(560px, calc(100% - 24px))'"
    align-center
    append-to-body
    destroy-on-close
    :close-on-click-modal="false"
    :close-on-press-escape="!busy"
    :show-close="!busy"
    :before-close="beforeClose"
  >
    <div class="approval-content">
      <p>即将通过整份申请 {{ original?.policy.policyCode }}，覆盖区的筛选和分页不影响批准范围。</p>
      <p v-if="loading" role="status">正在重新核对覆盖结果…</p>
      <el-alert v-else-if="error" :title="error.message" type="error" :closable="false" show-icon>
        <el-button v-if="needsReload" link type="primary" @click="$emit('reload')">
          重新读取政策
        </el-button>
        <el-button v-else link type="primary" @click="load">重新核对覆盖结果</el-button>
      </el-alert>
      <template v-else-if="latest">
        <dl class="approval-scope" aria-label="整份申请批准范围">
          <div>
            <dt>渠道</dt>
            <dd>{{ CHANNEL_LABELS[latest.snapshot.form.perspective] }}</dd>
          </div>
          <div>
            <dt>实际适用平台</dt>
            <dd>{{ platforms.map((code) => PLATFORM_LABELS[code] ?? code).join('、') }}</dd>
          </div>
          <div>
            <dt>批准范围</dt>
            <dd>{{ goodsCount }} 件商品 · {{ items.length }} 项商品平台范围</dd>
          </div>
        </dl>
        <div class="approval-counts" aria-label="批准覆盖汇总">
          <el-tag v-for="(label, code) in outcomes" :key="code" effect="plain">
            {{ label }} {{ counts[code] ?? 0 }} 项
          </el-tag>
        </div>
        <p>核对时点：{{ policyTime(latest.coverage.at).replace('T', ' ') }}（北京时间）</p>
        <template v-if="changed">
          <el-alert
            title="覆盖结果已变化，请查看下方最新结果，核对后再确认通过。"
            type="warning"
            :closable="false"
            show-icon
          />
          <AdminPolicyCoverage :detail="latest" @show-reference="$emit('show-reference', $event)" />
          <el-checkbox v-model="acknowledged">我已核对最新覆盖结果</el-checkbox>
        </template>
        <p v-else>覆盖结果与当前审核窗口一致。</p>
        <p>实际适用结果以审核通过时间及生效条件为准。</p>
      </template>
      <el-alert
        v-if="reviewError"
        :title="reviewError.message"
        type="error"
        :closable="false"
        show-icon
      >
        <el-button v-if="reviewNeedsReload" link type="primary" @click="$emit('reload')">
          重新读取政策
        </el-button>
      </el-alert>
    </div>
    <template #footer>
      <el-button :disabled="busy" @click="close">取消</el-button>
      <el-button
        type="primary"
        :loading="busy"
        :disabled="!canConfirm || reviewNeedsReload"
        @click="confirm"
      >
        确认通过
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.approval-content {
  padding: 0 6px;
}
.approval-content p {
  line-height: 1.7;
}
.approval-scope {
  display: grid;
  gap: 12px;
}
.approval-scope > div {
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr);
  gap: 12px;
}
.approval-scope dt {
  color: var(--el-text-color-secondary);
}
.approval-scope dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.approval-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.approval-content :deep(.el-checkbox) {
  height: auto;
  margin-top: 12px;
  white-space: normal;
}
.approval-content :deep(.el-checkbox__label) {
  white-space: normal;
}
</style>
