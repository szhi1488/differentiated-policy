<script setup>
import { computed, ref } from 'vue'
import PolicySubsidyFields from './PolicySubsidyFields.vue'
import { catalogConfigLabel } from '../../../utils/catalog-dictionary'
import { policyModelKey } from '../../../utils/policy-form'

const props = defineProps({
  model: { type: Object, required: true },
  platforms: { type: Array, required: true },
  confirming: { type: Boolean, default: false },
})
const emit = defineEmits([
  'set-model-platforms',
  'update-model-platform',
  'remove-model',
  'remove-goods',
  'add-subsidy',
  'update-subsidy',
  'remove-subsidy',
])
const firstGoods = computed(() => props.model.goods[0])
const special = computed(() => props.model.id !== undefined)
const titleId = computed(() => `policy-model-title-${props.model.id ?? 'standard'}`)
const editingPlatforms = ref(false)
const applicablePlatforms = computed(() =>
  props.platforms.filter(([code]) => props.model.platforms[code]),
)
function restoreOrderPlatforms() {
  emit('set-model-platforms', null)
  editingPlatforms.value = false
}
const modelGroups = computed(() => {
  const groups = new Map()
  for (const item of props.model.goods) {
    const key = policyModelKey(item)
    if (!groups.has(key)) {
      groups.set(key, {
        key,
        path: [item.brand, item.series, item.model].join(' / '),
        version: item.version,
        configurations: [],
      })
    }
    groups.get(key).configurations.push(item)
  }
  return [...groups.values()]
})
</script>

<template>
  <article class="policy-model-card" :aria-labelledby="titleId">
    <header class="policy-model-header">
      <h3 :id="titleId">{{ special ? firstGoods.name : '已选机型配置' }}</h3>
      <el-tag v-if="special" type="warning" size="small">特殊商品</el-tag>
      <el-tag
        v-if="model.goods.some((item) => item.status === 'inactive')"
        type="danger"
        size="small"
      >
        含不可用商品
      </el-tag>
      <el-button link type="danger" @click="$emit('remove-model')">
        {{ special ? '移除商品' : '移除全部' }}
      </el-button>
    </header>
    <div class="policy-model-fields">
      <dl v-for="group in modelGroups" :key="group.key" class="policy-model-summary-row">
        <div>
          <dt>品牌 / 系列 / 型号</dt>
          <dd>{{ group.path }}</dd>
        </div>
        <div>
          <dt>适用配置</dt>
          <dd class="policy-model-configs">
            <el-tag
              v-for="item in group.configurations"
              :key="item.key"
              :closable="model.goods.length > 1 && !confirming"
              effect="plain"
              :type="item.status === 'inactive' ? 'danger' : 'info'"
              @close="$emit('remove-goods', item.key)"
            >
              {{ catalogConfigLabel(item.config)
              }}{{ !special && item.color ? ` · ${item.color}` : '' }}
              {{ item.status === 'inactive' ? '（不可用）' : '' }}
            </el-tag>
          </dd>
        </div>
        <div v-if="group.version">
          <dt>版本</dt>
          <dd>{{ group.version }}</dd>
        </div>
        <div v-if="special">
          <dt>颜色</dt>
          <dd>{{ firstGoods.color }}</dd>
        </div>
      </dl>
    </div>

    <div class="policy-model-rules">
      <div class="policy-platform-panel">
        <div class="policy-platform-heading">
          <h4>适用平台</h4>
          <div v-if="platforms.length" class="policy-platform-actions">
            <el-button
              v-if="!model.followsOrderPlatforms"
              link
              type="primary"
              :disabled="confirming"
              @click="restoreOrderPlatforms"
            >
              恢复整单平台
            </el-button>
            <el-button
              link
              type="primary"
              :aria-expanded="editingPlatforms"
              :aria-controls="`${titleId}-platform-options`"
              :disabled="confirming"
              @click="editingPlatforms = !editingPlatforms"
            >
              {{ editingPlatforms ? '收起' : '调整平台' }}
            </el-button>
          </div>
        </div>
        <div v-if="applicablePlatforms.length" class="policy-applicable-platforms">
          <el-tag
            v-for="[code, name] in applicablePlatforms"
            :key="code"
            size="small"
            effect="plain"
          >
            {{ name }}
          </el-tag>
        </div>
        <p v-else class="policy-detail-hint">
          {{ platforms.length ? '未选择适用平台' : '请在基础信息中选择平台。' }}
        </p>
        <div
          v-show="editingPlatforms && platforms.length"
          :id="`${titleId}-platform-options`"
          class="policy-platform-editor"
        >
          <el-checkbox-group
            :model-value="Object.keys(model.platforms)"
            aria-label="适用平台"
            :disabled="confirming"
            class="policy-model-platforms"
            @update:model-value="$emit('set-model-platforms', $event)"
          >
            <el-checkbox v-for="[code, name] in platforms" :key="code" :value="code">
              {{ name }}
            </el-checkbox>
          </el-checkbox-group>
        </div>
        <div
          v-if="(special || model.subsidies.length > 1) && Object.keys(model.platforms).length"
          class="policy-platform-rows"
        >
          <div
            v-for="[code, name] in applicablePlatforms"
            :key="code"
            class="policy-platform-row"
            :class="{ 'policy-platform-row--with-scheme': special && model.subsidies.length > 1 }"
          >
            <strong>{{ name }}</strong>
            <el-form-item v-if="special" label="数量（选填）">
              <el-input
                :model-value="model.platforms[code].quantity"
                :aria-label="`${name} 数量`"
                type="number"
                min="0"
                step="1"
                placeholder="请输入数量"
                @update:model-value="$emit('update-model-platform', code, 'quantity', $event)"
              />
            </el-form-item>
            <el-form-item v-if="model.subsidies.length > 1" label="补贴方案">
              <el-select
                :model-value="model.platforms[code].subsidyKey"
                :aria-label="`${name} 补贴方案`"
                @update:model-value="$emit('update-model-platform', code, 'subsidyKey', $event)"
              >
                <el-option
                  v-for="(subsidy, position) in model.subsidies"
                  :key="subsidy.key"
                  :value="subsidy.key"
                  :label="`方案 ${position + 1}`"
                />
              </el-select>
            </el-form-item>
          </div>
        </div>
      </div>

      <div class="policy-subsidy-panel">
        <div class="policy-subsidy-heading">
          <h4>补贴方案</h4>
          <el-button link type="primary" @click="$emit('add-subsidy')">＋ 添加方案</el-button>
        </div>
        <PolicySubsidyFields
          v-for="(subsidy, position) in model.subsidies"
          :key="subsidy.key"
          :subsidy="subsidy"
          :position="position + 1"
          :platform-names="
            applicablePlatforms
              .filter(([code]) => model.platforms[code]?.subsidyKey === subsidy.key)
              .map(([, name]) => name)
          "
          @update-subsidy="(field, value) => $emit('update-subsidy', subsidy.key, field, value)"
          @remove-subsidy="$emit('remove-subsidy', subsidy.key)"
        />
        <p v-if="model.subsidies.length > 1" class="policy-detail-hint">
          可在适用平台下为各平台选择补贴方案。
        </p>
      </div>
    </div>
  </article>
</template>
