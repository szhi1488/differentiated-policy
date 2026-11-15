<script setup>
import { computed } from 'vue'
import { PLATFORM_LABELS, PRICE_BASE_LABELS } from '../../../constants/policy'
import { catalogConfigLabel } from '../../../utils/catalog-dictionary'
import { displayValue } from '../../../utils/policy-adapter'

const props = defineProps({
  cards: { type: Array, required: true },
  goods: { type: Array, required: true },
})
const goodsById = computed(() => new Map(props.goods.map((item) => [item.goodsId, item])))
const platformName = (code) => PLATFORM_LABELS[code] ?? code
const amountText = (value, unit) =>
  value === null || value === undefined || value === '' ? '未填写' : `${value}${unit}`

function groupModels(targets) {
  const models = new Map()
  for (const target of targets) {
    const candidate = goodsById.value.get(target.goodsId)
    // 草稿目录缺失或规格已变化时，不借用其他商品的名称、颜色展示。
    const item =
      candidate?.spuId === target.spuId &&
      candidate.ram === target.ram &&
      candidate.rom === target.rom
        ? candidate
        : null
    const modelKey = item ? target.spuId : `missing/${target.goodsId}`
    if (!models.has(modelKey)) {
      models.set(modelKey, {
        key: modelKey,
        name:
          [item?.modelName, item?.edition].filter(Boolean).join(' · ') || `商品 ${target.goodsId}`,
        description: [item?.brandName, item?.seriesName].filter(Boolean).join(' / '),
        configurations: new Map(),
      })
    }
    const configurations = models.get(modelKey).configurations
    const configKey = `${target.ram}/${target.rom}`
    if (!configurations.has(configKey)) {
      configurations.set(configKey, {
        key: configKey,
        label: catalogConfigLabel(
          [target.ram, target.rom].map((value) => value.replace(/GB$/, '')).join('+'),
        ),
        colors: [],
      })
    }
    configurations.get(configKey).colors.push({
      id: target.goodsId,
      name: item?.colorName || '颜色信息不可用',
    })
  }
  return [...models.values()].map((model) => ({
    ...model,
    configurations: [...model.configurations.values()],
  }))
}

function groupSubsidies(card) {
  const assignments = card.platformAssignments ?? []
  const plans = card.subsidyPlans ?? []
  const rows = plans.map((plan) => ({
    key: `plan/${plan.key}`,
    plan,
    platforms: assignments.filter(({ planKey }) => planKey === plan.key),
  }))
  const unassigned = assignments.filter(({ planKey }) => !plans.some(({ key }) => key === planKey))
  if (unassigned.length) {
    rows.push({ key: 'unassigned', plan: null, platforms: unassigned })
  }
  // 未分配平台的草稿方案仍保留展示，避免误认为已经适用。
  return [
    ...rows.filter((row) => row.platforms.length),
    ...rows.filter((row) => !row.platforms.length),
  ]
}

const displayCards = computed(() =>
  props.cards.map((card) => {
    const models = groupModels(card.targets)
    return {
      ...card,
      models,
      configurationCount: models.reduce((count, model) => count + model.configurations.length, 0),
      subsidies: groupSubsidies(card),
    }
  }),
)
</script>

<template>
  <section class="submission-goods-section" aria-label="商品及补贴">
    <h3>商品及补贴</h3>
    <el-empty v-if="!displayCards.length" description="暂无商品" :image-size="64" />
    <article v-for="card in displayCards" :key="card.key" class="submission-goods">
      <header class="submission-goods-heading">
        <div class="submission-goods-title">
          <h4>{{ card.cardType === 'SPECIFIED' ? '指定适用机型' : '普通机型' }}</h4>
          <span>{{ card.configurationCount }} 个配置 · {{ card.targets.length }} 件商品</span>
        </div>
        <span class="submission-platform-scope">
          {{ card.followsOrderPlatforms ? '沿用整单平台' : '指定平台' }}
        </span>
      </header>

      <section v-for="model in card.models" :key="model.key" class="submission-model">
        <header class="submission-model-heading">
          <h5>{{ model.name }}</h5>
          <span v-if="model.description">{{ model.description }}</span>
        </header>
        <dl class="submission-configurations">
          <div
            v-for="configuration in model.configurations"
            :key="configuration.key"
            class="submission-configuration"
          >
            <dt>{{ configuration.label }}</dt>
            <dd>
              <span class="submission-colors-label">已选颜色</span>
              <div class="submission-colors">
                <el-tag
                  v-for="color in configuration.colors"
                  :key="color.id"
                  type="info"
                  effect="plain"
                  size="small"
                >
                  {{ color.name }}
                </el-tag>
              </div>
            </dd>
          </div>
        </dl>
      </section>

      <section class="submission-subsidies" aria-label="适用平台与补贴">
        <h5>适用平台与补贴</h5>
        <p v-if="!card.subsidies.length" class="submission-incomplete">尚未设置适用平台和补贴</p>
        <div v-for="row in card.subsidies" :key="row.key" class="submission-subsidy-row">
          <div class="submission-subsidy-platforms">
            <span v-if="!row.platforms.length" class="submission-incomplete">尚未分配平台</span>
            <div
              v-for="entry in row.platforms"
              :key="entry.platformCode"
              class="submission-platform"
              :class="{ 'submission-platform-quantity': card.cardType === 'SPECIFIED' }"
            >
              <el-tag size="small" effect="light">{{ platformName(entry.platformCode) }}</el-tag>
              <span v-if="card.cardType === 'SPECIFIED'" class="submission-quantity">
                数量 {{ displayValue(entry.quantity) }}
              </span>
            </div>
          </div>
          <div class="submission-subsidy-value">
            <template v-if="row.plan?.subsidyMode === 'FIXED'">
              <p>
                <span>固定补贴</span>
                <strong>{{ amountText(row.plan.fixedAmount, ' 元') }}</strong>
              </p>
              <p class="submission-subsidy-extra">
                补贴上限 {{ amountText(row.plan.maxSubsidyAmount, ' 元') }}
              </p>
            </template>
            <template v-else-if="row.plan?.subsidyMode === 'RATIO'">
              <p>
                <span>比例补贴</span>
                <strong>{{ amountText(row.plan.ratio, '%') }}</strong>
              </p>
              <p class="submission-subsidy-extra">
                计算基数 {{ PRICE_BASE_LABELS[row.plan.priceBase] ?? '未填写' }}
              </p>
            </template>
            <p v-else class="submission-incomplete">
              {{ row.plan ? '未选择补贴方式' : '未选择补贴方案' }}
            </p>
          </div>
        </div>
      </section>
    </article>
  </section>
</template>

<style scoped>
.submission-goods-section {
  overflow-wrap: anywhere;
}
.submission-goods-section h3 {
  margin: 20px 0 12px;
  font-size: 15px;
}
.submission-goods {
  margin-bottom: 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  overflow: hidden;
}
.submission-goods-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px 16px;
  padding: 12px 16px;
  background: var(--el-fill-color-light);
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.submission-goods-title {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 10px;
}
.submission-goods-section h4,
.submission-goods-section h5,
.submission-goods-section p {
  margin: 0;
}
.submission-goods-section h4,
.submission-goods-section h5 {
  font-size: 14px;
}
.submission-goods-title > span,
.submission-platform-scope,
.submission-model-heading > span,
.submission-colors-label,
.submission-incomplete,
.submission-quantity,
.submission-subsidy-extra {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.submission-model {
  padding: 16px;
}
.submission-model + .submission-model {
  border-top: 1px solid var(--el-border-color-lighter);
}
.submission-model-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px 12px;
  margin-bottom: 12px;
}
.submission-configurations {
  display: grid;
  gap: 10px;
  margin: 0;
}
.submission-configuration {
  display: grid;
  grid-template-columns: 122px minmax(0, 1fr);
  gap: 6px 12px;
  align-items: start;
}
.submission-configuration dt {
  font-size: 13px;
  line-height: 22px;
  font-weight: 500;
}
.submission-configuration dd {
  display: flex;
  align-items: baseline;
  gap: 6px 10px;
  margin: 0;
  min-width: 0;
}
.submission-colors-label {
  flex-shrink: 0;
}
.submission-colors,
.submission-subsidy-platforms {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.submission-colors :deep(.el-tag) {
  height: auto;
  min-height: 20px;
  max-width: 100%;
  white-space: normal;
}
.submission-subsidies {
  padding: 12px 16px 4px;
  border-top: 1px solid var(--el-border-color-light);
}
.submission-subsidies > h5 {
  color: var(--el-text-color-regular);
  font-size: 12px;
  font-weight: 500;
  margin-bottom: 4px;
}
.submission-subsidies > .submission-incomplete {
  padding: 8px 0 12px;
}
.submission-subsidy-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  align-items: center;
  gap: 10px 16px;
  padding: 12px 0;
}
.submission-subsidy-row + .submission-subsidy-row {
  border-top: 1px dashed var(--el-border-color-light);
}
.submission-platform {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 8px;
}
.submission-platform-quantity {
  flex-basis: 100%;
}
.submission-subsidy-value > p:first-child {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px 10px;
  font-size: 13px;
}
.submission-subsidy-value strong {
  color: var(--el-color-primary);
  font-size: 17px;
  font-variant-numeric: tabular-nums;
}
.submission-subsidy-value .submission-subsidy-extra {
  margin-top: 4px;
}
@media (max-width: 600px) {
  .submission-goods-heading,
  .submission-model,
  .submission-subsidies {
    padding-left: 12px;
    padding-right: 12px;
  }
  .submission-configuration,
  .submission-subsidy-row {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
