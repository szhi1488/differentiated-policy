<script setup>
import { computed, ref, toRaw, watch } from 'vue'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import WorkspacePageHeading from '../../../components/WorkspacePageHeading.vue'
import { readReviewSettings, saveReviewSettings } from '../../../api/policy-review-settings'
import { useLatestQuery } from '../../../composables/useLatestQuery'
import { useReviewerSelectionWrite } from '../../../composables/useReviewerSelectionWrite'
import BrandReviewAssignments from './BrandReviewAssignments.vue'

const { data: settings, loading, error, run } = useLatestQuery()
const { saving, error: saveError, needsReload, reset, run: write } = useReviewerSelectionWrite()
const brands = ref([])
const activeBrandId = ref('')
watch(
  settings,
  (latest) => {
    if (!latest) {
      return
    }
    brands.value = structuredClone(toRaw(latest.brands))
    if (!brands.value.some((brand) => brand.brandId === activeBrandId.value)) {
      activeBrandId.value = brands.value[0]?.brandId ?? ''
    }
  },
  { flush: 'sync' },
)

function load() {
  reset()
  return run(readReviewSettings)
}
void load()

// 未分配任何人的品牌无需提交，按系列分工允许逐步补齐初审人。
function assignmentsFor(list) {
  return list
    .map((brand) => {
      const bySeries = brand.productMode === 'BY_SERIES'
      return {
        brandId: brand.brandId,
        productMode: brand.productMode,
        purchaseUserId: brand.purchaseReviewer?.id ?? null,
        brandProductUserId: bySeries ? null : (brand.brandProductReviewer?.id ?? null),
        series: bySeries
          ? brand.series
              .filter((item) => item.productReviewer)
              .map((item) => ({ seriesId: item.seriesId, productUserId: item.productReviewer.id }))
          : [],
      }
    })
    .filter((brand) => brand.purchaseUserId || brand.brandProductUserId || brand.series.length)
}
const assignments = computed(() => assignmentsFor(brands.value))
const savedAssignments = computed(() =>
  JSON.stringify(assignmentsFor(settings.value?.brands ?? [])),
)
const changed = computed(() => JSON.stringify(assignments.value) !== savedAssignments.value)
const assignmentError = computed(() => {
  const purchaseBrands = new Map()
  for (const { brandId, purchaseUserId, productMode, brandProductUserId } of assignments.value) {
    const { brandName, purchaseReviewer } = brands.value.find((brand) => brand.brandId === brandId)
    if (!purchaseUserId) {
      return `请为${brandName}选择品牌终审采购。`
    }
    const otherBrand = purchaseBrands.get(purchaseUserId)
    if (otherBrand) {
      return `${purchaseReviewer.name}只能负责一个品牌，请为「${otherBrand}」和「${brandName}」选择不同的采购。`
    }
    purchaseBrands.set(purchaseUserId, brandName)
    if (productMode === 'BY_BRAND' && !brandProductUserId) {
      return `请为${brandName}选择品牌初审人。`
    }
  }
  return ''
})
const canSave = computed(
  () =>
    settings.value &&
    changed.value &&
    !loading.value &&
    !saving.value &&
    !needsReload.value &&
    !assignmentError.value,
)

function assignReviewer(brand, { group, seriesId, reviewer }) {
  if (group === 'PURCHASE') {
    brand.purchaseReviewer = reviewer
  } else if (brand.productMode === 'BY_BRAND') {
    brand.brandProductReviewer = reviewer
  } else {
    brand.series.find((item) => item.seriesId === seriesId).productReviewer = reviewer
  }
}

function save() {
  if (!canSave.value) {
    return
  }
  const expectedVersion = settings.value.version
  return write(
    (signal) => saveReviewSettings({ expectedVersion, brands: assignments.value }, signal),
    (result) => {
      if (result?.version !== expectedVersion + 1 || !Array.isArray(result.brands)) {
        throw new Error('请重新读取品牌与系列审核分工。')
      }
      settings.value = result
      ElMessage.success('预设审核分工已保存')
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

    <section class="settings-card" aria-labelledby="assignments-title">
      <div class="settings-heading">
        <h2 id="assignments-title">审核分工</h2>
        <div v-if="settings" class="save-controls">
          <span v-if="changed" class="save-status" role="status">有未保存的修改</span>
          <el-button type="primary" :loading="saving" :disabled="!canSave" @click="save">
            保存审核分工
          </el-button>
        </div>
      </div>

      <p v-if="loading" class="loading-hint" role="status">正在读取审核分工…</p>
      <el-alert v-else-if="error" type="error" :closable="false" :title="error.message" show-icon>
        <el-button link @click="load">重新读取名单</el-button>
      </el-alert>
      <template v-else-if="settings">
        <el-alert
          v-if="!settings.policyChatConfigured"
          title="飞书业务群尚未配置，请部署人员核对服务端环境变量 FEISHU_POLICY_CHAT_ID。"
          type="warning"
          :closable="false"
          show-icon
        />
        <el-alert
          v-if="assignmentError"
          type="warning"
          :closable="false"
          :title="assignmentError"
          show-icon
        />
        <el-alert v-if="saveError" type="error" :closable="false" :title="saveError" show-icon>
          <el-button v-if="needsReload" link @click="load">载入最新名单并替换当前选择</el-button>
        </el-alert>
        <el-empty v-if="!brands.length" description="请先在商品中心的数据字典中添加品牌" />
        <el-tabs v-else v-model="activeBrandId" class="brand-tabs">
          <el-tab-pane
            v-for="brand in brands"
            :key="brand.brandId"
            :name="brand.brandId"
            :label="brand.brandName"
          >
            <BrandReviewAssignments
              v-if="activeBrandId === brand.brandId"
              :brand="brand"
              :disabled="saving || needsReload"
              @assign="assignReviewer(brand, $event)"
              @update-mode="brand.productMode = $event"
            />
          </el-tab-pane>
        </el-tabs>
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
.workspace-page-banner {
  display: flex;
  align-items: center;
}
.settings-card {
  min-width: 0;
  padding: 22px 24px 24px;
  background: #fff;
  border: 1px solid #dfe7f3;
  border-radius: 10px;
  box-shadow: 0 3px 12px #254b7709;
}
.settings-heading,
.save-controls {
  display: flex;
  align-items: center;
  gap: 12px;
}
.settings-heading {
  justify-content: space-between;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.settings-heading h2 {
  font-size: 18px;
  color: #203651;
  line-height: 1.5;
}
.loading-hint {
  margin-top: 4px;
  color: #596b80;
  line-height: 1.65;
}
.save-controls {
  flex-wrap: wrap;
  justify-content: flex-end;
}
.save-status {
  color: #956218;
  font-size: 12px;
}
.settings-card > .el-alert {
  margin: 12px 0;
  overflow-wrap: anywhere;
}
.brand-tabs {
  --el-tabs-header-height: 44px;
}
.brand-tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}
.brand-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: #e4eaf2;
}
.brand-tabs :deep(.el-tabs__item) {
  font-weight: 600;
}
@media (max-width: 600px) {
  .settings-card {
    padding: 16px 14px;
  }
}
</style>
