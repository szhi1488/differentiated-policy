<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import WorkspacePageHeading from '../../../components/WorkspacePageHeading.vue'
import { readReviewSettings, saveReviewSettings } from '../../../api/policy-review-settings'
import { useLatestQuery } from '../../../composables/useLatestQuery'
import { useReviewerSelectionWrite } from '../../../composables/useReviewerSelectionWrite'
import ReviewerPicker from './ReviewerPicker.vue'

const { data: settings, loading, error, run } = useLatestQuery()
const { saving, error: saveError, needsReload, reset, run: write } = useReviewerSelectionWrite()
const product = ref([])
const purchase = ref([])
watch(
  settings,
  (latest) => {
    if (latest) {
      product.value = latest.productReviewers
      purchase.value = latest.purchaseReviewers
    }
  },
  { flush: 'sync' },
)

function load() {
  reset()
  return run(readReviewSettings)
}
void load()

function save() {
  if (!settings.value || loading.value) {
    return
  }
  const expectedVersion = settings.value.version
  return write(
    (signal) =>
      saveReviewSettings(
        {
          expectedVersion,
          productReviewerUserIds: product.value.map((person) => person.id),
          purchaseReviewerUserIds: purchase.value.map((person) => person.id),
        },
        signal,
      ),
    (result) => {
      if (
        result?.version !== expectedVersion + 1 ||
        !Array.isArray(result.productReviewers) ||
        !Array.isArray(result.purchaseReviewers)
      ) {
        throw new Error('请重新读取两组审核名单。')
      }
      settings.value = result
      ElMessage.success('默认审核名单已保存')
    },
  )
}
</script>

<template>
  <section class="review-settings" aria-labelledby="review-settings-title">
    <header class="workspace-page-banner">
      <WorkspacePageHeading
        heading-id="review-settings-title"
        title="政策审核设置"
        icon="settings"
      />
    </header>
    <section class="settings-card" aria-label="默认审核名单">
      <h2>默认审核名单</h2>
      <p class="settings-hint">
        保存后，新提交和重新提交的申请使用更新后的名单。在途申请可在「差异化政策」的当前申请详情中改派。
      </p>
      <p v-if="loading" role="status">正在读取名单…</p>
      <el-alert v-else-if="error" type="error" :closable="false" :title="error.message">
        <el-button link @click="load">重新读取名单</el-button>
      </el-alert>
      <template v-else-if="settings">
        <el-alert
          v-if="settings.policyChatConfigured === false"
          title="飞书业务群尚未配置，请部署人员核对服务端环境变量 FEISHU_POLICY_CHAT_ID。"
          type="warning"
          :closable="false"
          show-icon
        />
        <div class="settings-groups">
          <ReviewerPicker v-model="product" group="PRODUCT" :disabled="saving" />
          <ReviewerPicker v-model="purchase" group="PURCHASE" :disabled="saving" />
        </div>
        <el-alert v-if="saveError" type="error" :closable="false" :title="saveError">
          <el-button v-if="needsReload" link @click="load">载入最新名单并替换当前选择</el-button>
        </el-alert>
        <footer>
          <span>配置版本 {{ settings.version }}</span>
          <el-button type="primary" :loading="saving" :disabled="needsReload" @click="save">
            保存默认名单
          </el-button>
        </footer>
      </template>
    </section>
  </section>
</template>

<style scoped>
.review-settings {
  display: grid;
  gap: 16px;
  min-width: 0;
}
.review-settings header {
  display: flex;
  align-items: center;
}
.settings-card {
  background: white;
  padding: 22px;
  border: 1px solid #dfe7f3;
  border-radius: 10px;
  box-shadow: 0 3px 12px #254b7709;
}
.settings-card h2 {
  color: #203651;
  font-size: 18px;
  margin: 0;
}
.settings-hint,
.settings-card footer span {
  color: #586a82;
  font-size: 14px;
  line-height: 1.6;
}
.settings-hint {
  margin: 8px 0 18px;
}
.settings-groups {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 340px), 1fr));
  gap: 12px;
  align-items: start;
}
.settings-groups > section {
  padding: 16px;
  border: 1px solid #dfe8f5;
  border-radius: 8px;
}
.settings-card :deep(.el-alert) {
  margin-top: 14px;
}
.settings-card footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 18px;
  gap: 10px;
}
@media (max-width: 600px) {
  .settings-card {
    padding: 14px;
  }
  .settings-groups > section {
    padding: 12px;
  }
}
</style>
