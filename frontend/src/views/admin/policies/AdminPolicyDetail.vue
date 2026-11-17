<script setup>
import { policyAttachmentDownloadUrl } from '../../../api/attachments'
import { computed } from 'vue'
import AppIcon from '../../../components/AppIcon.vue'
import AttachmentDownload from '../../../components/AttachmentDownload.vue'
import {
  CHANNEL_LABELS,
  PLATFORM_LABELS,
  SCENE_LABELS,
  VERIFY_LABELS,
} from '../../../constants/policy'
import { policyTime } from '../../../utils/policy-adapter'
import { catalogConfigLabel } from '../../../utils/catalog-dictionary'
import { subsidyText } from '../../../utils/policy-coverage'
import AdminPolicyCoverage from './AdminPolicyCoverage.vue'

const props = defineProps({ detail: { type: Object, required: true } })
defineEmits(['show-reference'])
const policy = computed(() => props.detail.policy)
const form = computed(() => props.detail.snapshot.form)
const audit = computed(() => props.detail.audit)
const reviewed = computed(() => ['APPROVED', 'REJECTED'].includes(audit.value.auditStatus))
const auditComment = computed(() =>
  audit.value.auditComment?.trim()
    ? audit.value.auditComment
    : audit.value.auditStatus === 'REJECTED'
      ? '未填写审核意见'
      : '',
)
const goods = computed(
  () => new Map(props.detail.snapshot.goods.map((item) => [item.goodsId, item])),
)
const time = (value) => policyTime(value).replace('T', ' ')

function product(target) {
  const item = goods.value.get(target.goodsId)
  return {
    id: target.goodsId,
    model: [item?.modelName, item?.edition].filter(Boolean).join(' · ') || `商品 ${target.goodsId}`,
    config: catalogConfigLabel(`${target.ram.replace(/GB$/, '')}+${target.rom.replace(/GB$/, '')}`),
    color: item?.colorName ?? '—',
  }
}

const commonRows = computed(() => {
  const rows = []
  for (const card of form.value.goodsForm.cards.filter(({ cardType }) => cardType === 'COMMON')) {
    const configurations = new Map()
    for (const target of card.targets) {
      const key = `${target.spuId}/${target.ram}/${target.rom}`
      if (!configurations.has(key)) {
        configurations.set(key, [])
      }
      configurations.get(key).push(product(target))
    }
    for (const [key, selectedProducts] of configurations) {
      for (const subsidy of card.subsidyPlans) {
        const platformCodes = card.platformAssignments
          .filter(({ planKey }) => planKey === subsidy.key)
          .map(({ platformCode }) => platformCode)
        if (platformCodes.length) {
          rows.push({
            id: `${card.key}/${key}/${subsidy.key}`,
            model: selectedProducts[0].model,
            config: selectedProducts[0].config,
            selectedProducts,
            platformCodes,
            subsidy,
          })
        }
      }
    }
  }
  return rows
})
const specifiedRows = computed(() =>
  form.value.goodsForm.cards
    .filter(({ cardType }) => cardType === 'SPECIFIED')
    .flatMap((card) => {
      const item = product(card.targets[0])
      return card.platformAssignments.map(({ platformCode, quantity, planKey }) => ({
        ...item,
        key: `${card.key}/${platformCode}`,
        platformCode,
        quantity,
        subsidy: card.subsidyPlans.find(({ key }) => key === planKey),
      }))
    }),
)

function platformNames(codes) {
  return codes.map((code) => PLATFORM_LABELS[code]).join('、')
}

const basicFields = computed(() => {
  const { perspective, selectedPlatforms, verifyType, sceneType, estimated, startTime, endTime } =
    form.value
  return [
    ['渠道', CHANNEL_LABELS[perspective]],
    ['整单平台', platformNames(selectedPlatforms)],
    ['核销类型', VERIFY_LABELS[verifyType] ?? '—'],
    ['涉及场景', SCENE_LABELS[sceneType] ?? '—'],
    ['预估政策', typeof estimated === 'boolean' ? (estimated ? '是' : '否') : '—'],
    ['开始时间', time(startTime)],
    ['到期时间', time(endTime)],
  ]
})
</script>

<template>
  <div class="admin-policy-detail-grid">
    <section class="admin-policy-detail-section">
      <h3>基本信息</h3>
      <dl class="admin-policy-detail-fields">
        <div v-for="[label, value] in basicFields" :key="label">
          <dt>{{ label }}</dt>
          <dd>{{ value }}</dd>
        </div>
      </dl>
    </section>

    <section class="admin-policy-detail-section">
      <h3>机型及补贴信息</h3>
      <section v-if="commonRows.length" class="admin-policy-goods-group">
        <h4>普通机型</h4>
        <el-table :data="commonRows" row-key="id" class="admin-policy-table" size="small">
          <el-table-column prop="model" label="型号" min-width="116" />
          <el-table-column label="配置" min-width="148">
            <template #default="{ row }">
              {{ row.config }}
              <details class="admin-policy-colors">
                <summary :aria-label="`查看 ${row.model} ${row.config} 的已选颜色`">
                  查看颜色（{{ row.selectedProducts.length }}）
                </summary>
                <ul aria-label="已选颜色">
                  <li v-for="product in row.selectedProducts" :key="product.id">
                    <el-tag size="small" effect="plain">{{ product.color }}</el-tag>
                  </li>
                </ul>
              </details>
            </template>
          </el-table-column>
          <el-table-column label="适用平台" min-width="96">
            <template #default="{ row }">
              {{ platformNames(row.platformCodes ?? policy.platforms) }}
            </template>
          </el-table-column>
          <el-table-column label="补贴" min-width="186">
            <template #default="{ row }">{{ subsidyText(row.subsidy) }}</template>
          </el-table-column>
        </el-table>
      </section>

      <section v-if="specifiedRows.length" class="admin-policy-goods-group">
        <h4>指定适用机型</h4>
        <el-table :data="specifiedRows" row-key="key" class="admin-policy-table" size="small">
          <el-table-column prop="model" label="型号" min-width="106" />
          <el-table-column prop="config" label="配置" min-width="114" />
          <el-table-column prop="color" label="颜色" min-width="90" />
          <el-table-column label="适用平台" min-width="92">
            <template #default="{ row }">{{ PLATFORM_LABELS[row.platformCode] }}</template>
          </el-table-column>
          <el-table-column label="数量" min-width="76">
            <template #default="{ row }">{{ row.quantity ?? '未填写' }}</template>
          </el-table-column>
          <el-table-column label="补贴" min-width="186">
            <template #default="{ row }">{{ subsidyText(row.subsidy) }}</template>
          </el-table-column>
        </el-table>
      </section>
    </section>

    <section class="admin-policy-detail-section admin-policy-support">
      <h3>其他信息</h3>
      <dl class="admin-policy-support-fields">
        <div>
          <dt>特别备注</dt>
          <dd class="admin-policy-note">{{ form.specialRemark || '无' }}</dd>
        </div>
        <div>
          <dt>
            附件及链接
            <span v-if="form.attachmentInfo.length" class="admin-policy-attachment-count">
              {{ form.attachmentInfo.length }} 项
            </span>
          </dt>
          <dd>
            <ul v-if="form.attachmentInfo.length" class="admin-policy-attachments">
              <li
                v-for="attachment in form.attachmentInfo"
                :key="attachment.id ?? attachment.url"
                class="admin-policy-attachment"
              >
                <AppIcon name="document" :size="16" />
                <AttachmentDownload
                  v-if="attachment.type === 'FILE'"
                  :url="policyAttachmentDownloadUrl(policy.id, attachment.id, audit.id, true)"
                  :filename="attachment.originalName"
                />
                <span v-else class="admin-policy-attachment-url">{{ attachment.url }}</span>
              </li>
            </ul>
            <span v-else class="admin-policy-support-empty">无</span>
          </dd>
        </div>
      </dl>
      <section v-if="reviewed || audit.auditTime || audit.auditComment" class="admin-policy-audit">
        <h4>审核信息</h4>
        <dl class="admin-policy-support-fields">
          <div v-if="audit.auditTime" class="admin-policy-audit-meta">
            <dt>审核人</dt>
            <dd>{{ audit.auditorName }}</dd>
          </div>
          <div v-if="audit.auditTime" class="admin-policy-audit-meta">
            <dt>审核时间</dt>
            <dd>{{ time(audit.auditTime) }}</dd>
          </div>
          <div v-if="auditComment">
            <dt>审核意见</dt>
            <dd class="admin-policy-note">{{ auditComment }}</dd>
          </div>
        </dl>
      </section>
    </section>
  </div>
  <AdminPolicyCoverage
    v-if="detail.coverage"
    :detail="detail"
    @show-reference="$emit('show-reference', $event)"
  />
</template>
