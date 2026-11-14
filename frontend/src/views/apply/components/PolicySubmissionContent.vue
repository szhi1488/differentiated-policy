<script setup>
import { computed } from 'vue'
import {
  CHANNEL_LABELS,
  PLATFORM_LABELS,
  PRICE_BASE_LABELS,
  SCENE_LABELS,
  VERIFY_LABELS,
} from '../../../constants/policy'
import { displayValue, policyTime } from '../../../utils/policy-adapter'

const props = defineProps({ detail: { type: Object, required: true } })
const form = computed(() => props.detail.form)
const goods = computed(() => new Map(props.detail.goods.map((item) => [item.goodsId, item])))
const platformName = (code) => PLATFORM_LABELS[code] ?? code
const time = (value) => policyTime(value).replace('T', ' ')

function targetName(target) {
  const item = goods.value.get(target.goodsId)
  if (!item || item.spuId !== target.spuId || item.ram !== target.ram || item.rom !== target.rom) {
    return `商品 ${target.goodsId} · ${target.ram}+${target.rom}`
  }
  const model = [item.brandName, item.seriesName, item.modelName, item.edition]
    .filter(Boolean)
    .join(' / ')
  return `${[item.goodsName, model, `${item.ram}+${item.rom}`, item.colorName].filter(Boolean).join(' · ')}`
}

function planName(plans, key) {
  const position = plans.findIndex((plan) => plan.key === key)
  return position >= 0 ? `方案 ${position + 1}` : '未选择方案'
}
</script>

<template>
  <div class="submission-content">
    <el-descriptions :column="1" border>
      <el-descriptions-item label="申请人">{{ detail.applicantName }}</el-descriptions-item>
      <el-descriptions-item label="渠道">
        {{ CHANNEL_LABELS[form.perspective] }}
      </el-descriptions-item>
      <el-descriptions-item label="整单平台">
        {{ form.selectedPlatforms.map(platformName).join('、') || '未选择' }}
      </el-descriptions-item>
      <template v-if="form.perspective === 'RETAIL'">
        <el-descriptions-item label="核销类型">
          {{ VERIFY_LABELS[form.verifyType] ?? '未填写' }}
        </el-descriptions-item>
        <el-descriptions-item label="补贴场景">
          {{ SCENE_LABELS[form.sceneType] ?? '未填写' }}
        </el-descriptions-item>
        <el-descriptions-item label="是否预估">
          {{ displayValue(form.estimated) }}
        </el-descriptions-item>
      </template>
      <el-descriptions-item label="开始时间">
        {{ time(form.startTime) || '未填写' }}
      </el-descriptions-item>
      <el-descriptions-item label="结束时间">
        {{ time(form.endTime) || '未填写' }}
      </el-descriptions-item>
      <el-descriptions-item label="特别备注">
        {{ displayValue(form.specialRemark) }}
      </el-descriptions-item>
      <el-descriptions-item label="其他说明">
        {{ displayValue(form.otherExplanation) }}
      </el-descriptions-item>
      <el-descriptions-item label="附件链接">
        <span v-if="!form.attachmentInfo.length">未填写</span>
        <div v-for="attachment in form.attachmentInfo" :key="attachment.url">
          {{ attachment.url }}
        </div>
      </el-descriptions-item>
    </el-descriptions>

    <h3>商品及补贴</h3>
    <el-empty v-if="!form.goodsForm.cards.length" description="暂无商品" :image-size="64" />
    <article v-for="card in form.goodsForm.cards" :key="card.key" class="submission-goods">
      <h4>{{ card.cardType === 'SPECIFIED' ? '指定适用机型' : '普通机型' }}</h4>
      <ul class="submission-targets">
        <li v-for="target in card.targets" :key="target.goodsId">{{ targetName(target) }}</li>
      </ul>
      <p>平台范围 · {{ card.followsOrderPlatforms ? '沿用整单平台' : '指定平台' }}</p>
      <ul class="submission-rules">
        <li v-for="entry in card.platformAssignments ?? []" :key="entry.platformCode">
          {{ platformName(entry.platformCode) }} ·
          {{ planName(card.subsidyPlans ?? [], entry.planKey) }}
          <template v-if="card.cardType === 'SPECIFIED'">
            · 数量 {{ displayValue(entry.quantity) }}
          </template>
        </li>
      </ul>
      <ul class="submission-rules">
        <li v-for="(plan, index) in card.subsidyPlans" :key="plan.key">
          <strong>方案 {{ index + 1 }}</strong>
          <template v-if="plan.subsidyMode === 'FIXED'">
            · 固定金额 {{ displayValue(plan.fixedAmount) }}
            <template v-if="plan.fixedAmount != null">元</template>
            · 上限 {{ displayValue(plan.maxSubsidyAmount) }}
            <template v-if="plan.maxSubsidyAmount != null">元</template>
          </template>
          <template v-else-if="plan.subsidyMode === 'RATIO'">
            · 比例 {{ displayValue(plan.ratio) }}
            <template v-if="plan.ratio != null">%</template>
            · 基数 {{ PRICE_BASE_LABELS[plan.priceBase] ?? '未填写' }}
          </template>
        </li>
      </ul>
    </article>
  </div>
</template>

<style scoped>
.submission-content {
  overflow-wrap: anywhere;
}
.submission-content :deep(.el-descriptions__content) {
  white-space: pre-wrap;
}
.submission-content :deep(.el-descriptions__label) {
  width: 98px;
}
.submission-content h3 {
  margin: 18px 0 10px;
  font-size: 15px;
}
.submission-goods {
  padding: 12px;
  margin-bottom: 10px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
}
.submission-goods h4,
.submission-goods p {
  margin: 0;
}
.submission-targets,
.submission-rules {
  display: grid;
  gap: 5px;
  padding-left: 20px;
  margin: 8px 0;
}
</style>
