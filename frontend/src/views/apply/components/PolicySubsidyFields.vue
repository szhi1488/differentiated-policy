<script setup>
import { PRICE_BASES } from '../../../constants/policy'

defineProps({
  subsidy: { type: Object, required: true },
  position: { type: Number, required: true },
  platformNames: { type: Array, required: true },
})
defineEmits(['update-subsidy', 'remove-subsidy'])

const fixedFields = [
  ['fixedAmount', '补贴金额', true],
  ['maxAmount', '补贴上限', false],
]
</script>

<template>
  <div class="policy-subsidy">
    <div class="policy-subsidy-meta">
      <strong>方案 {{ position }}</strong>
      <div class="policy-subsidy-platforms" aria-label="方案适用平台">
        <el-tag v-for="name in platformNames" :key="name" size="small" effect="light">
          {{ name }}
        </el-tag>
        <span v-if="!platformNames.length">未分配平台</span>
      </div>
      <el-button
        v-if="position > 1"
        link
        type="danger"
        :aria-label="`删除方案 ${position}`"
        @click="$emit('remove-subsidy')"
      >
        删除
      </el-button>
    </div>
    <div class="policy-subsidy-fields">
      <el-form-item label="补贴方式" required>
        <el-select
          :model-value="subsidy.mode"
          :aria-label="`方案 ${position} 补贴方式`"
          @update:model-value="$emit('update-subsidy', 'mode', $event)"
        >
          <el-option value="FIXED" label="固定补贴金额" />
          <el-option value="RATIO" label="补贴比例" />
        </el-select>
      </el-form-item>
      <template v-if="subsidy.mode === 'FIXED'">
        <el-form-item
          v-for="[field, label, required] in fixedFields"
          :key="field"
          :label="label"
          :required="required"
        >
          <el-input
            :model-value="subsidy[field]"
            :aria-label="`方案 ${position} ${label}`"
            inputmode="decimal"
            placeholder="0.00"
            @update:model-value="$emit('update-subsidy', field, $event)"
          >
            <template #append>元</template>
          </el-input>
        </el-form-item>
      </template>
      <template v-else>
        <el-form-item label="补贴比例" required>
          <el-input
            :model-value="subsidy.ratio"
            :aria-label="`方案 ${position} 补贴比例`"
            inputmode="decimal"
            placeholder="0.00"
            @update:model-value="$emit('update-subsidy', 'ratio', $event)"
          >
            <template #append>%</template>
          </el-input>
        </el-form-item>
        <el-form-item label="计算基数" required>
          <el-select
            :model-value="subsidy.priceBase"
            :aria-label="`方案 ${position} 计算基数`"
            placeholder="请选择"
            @update:model-value="$emit('update-subsidy', 'priceBase', $event)"
          >
            <el-option
              v-for="[code, label] in PRICE_BASES"
              :key="code"
              :value="code"
              :label="label"
            />
          </el-select>
        </el-form-item>
      </template>
    </div>
  </div>
</template>
