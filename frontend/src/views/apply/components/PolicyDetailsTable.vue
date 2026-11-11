<script setup>
import { PRICE_BASES } from '../../../constants/policy'
import { changeSubsidy } from '../../../utils/policy-form'

defineProps({
  platformName: { type: String, default: '' },
  hasPlatform: { type: Boolean, default: false },
})
const rows = defineModel({ type: Array, required: true })
</script>

<template>
  <el-table :data="rows" row-key="key" stripe border class="details-table">
    <el-table-column type="index" label="序号" width="60" align="center" />
    <el-table-column label="涉及型号 *" min-width="160">
      <template #default="{ row, $index }">
        <el-input
          v-model="row.model"
          :aria-label="`${platformName}第${$index + 1}行涉及型号`"
          placeholder="请输入型号"
        />
      </template>
    </el-table-column>
    <el-table-column label="单型号 *" min-width="130">
      <template #default="{ row, $index }">
        <el-input
          v-model="row.singleModel"
          :aria-label="`第${$index + 1}行单型号`"
          placeholder="单型号"
        />
      </template>
    </el-table-column>
    <el-table-column label="配置 *" min-width="110">
      <template #default="{ row, $index }">
        <el-input v-model="row.spec" :aria-label="`第${$index + 1}行配置`" placeholder="配置" />
      </template>
    </el-table-column>
    <el-table-column label="颜色" min-width="100">
      <template #default="{ row, $index }">
        <el-input v-model="row.color" :aria-label="`第${$index + 1}行颜色`" placeholder="选填" />
      </template>
    </el-table-column>
    <el-table-column label="数量（选填）" min-width="120">
      <template #default="{ row, $index }">
        <el-input
          v-model="row.quantity"
          type="number"
          min="0"
          step="1"
          :aria-label="`第${$index + 1}行数量`"
          placeholder="选填"
        />
      </template>
    </el-table-column>
    <el-table-column label="补贴方式 *" min-width="165">
      <template #default="{ row, $index }">
        <el-select
          :model-value="row.mode"
          :aria-label="`第${$index + 1}行补贴方式`"
          @update:model-value="changeSubsidy(row, $event)"
        >
          <el-option value="FIXED" label="固定补贴金额" />
          <el-option value="RATIO" label="补贴比例" />
        </el-select>
      </template>
    </el-table-column>
    <el-table-column label="补贴详情 *" min-width="255">
      <template #default="{ row, $index }">
        <div class="subsidy-inputs">
          <template v-if="row.mode === 'FIXED'">
            <label>
              补贴金额
              <el-input
                v-model="row.fixedAmount"
                inputmode="decimal"
                placeholder="0.00"
                :aria-label="`第${$index + 1}行固定补贴金额`"
              >
                <template #append>元</template>
              </el-input>
            </label>
            <label>
              补贴上限
              <el-input
                v-model="row.maxAmount"
                inputmode="decimal"
                placeholder="0.00"
                :aria-label="`第${$index + 1}行补贴上限`"
              >
                <template #append>元</template>
              </el-input>
            </label>
          </template>
          <template v-else>
            <label>
              补贴比例
              <el-input
                v-model="row.ratio"
                inputmode="decimal"
                placeholder="0.00"
                :aria-label="`第${$index + 1}行补贴比例`"
              >
                <template #append>%</template>
              </el-input>
            </label>
            <label>
              计算基数
              <el-select
                v-model="row.priceBase"
                :aria-label="`第${$index + 1}行计算基数`"
                placeholder="请选择"
              >
                <el-option v-for="base in PRICE_BASES" :key="base" :value="base" :label="base" />
              </el-select>
            </label>
          </template>
        </div>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="75" fixed="right" align="center">
      <template #default="{ $index }">
        <el-button
          link
          type="danger"
          :aria-label="`删除第${$index + 1}行机型`"
          @click="rows.splice($index, 1)"
        >
          删除
        </el-button>
      </template>
    </el-table-column>
    <template #empty>
      <el-empty
        :image-size="60"
        :description="
          hasPlatform
            ? '还未添加机型，点击右上方「添加机型」开始填写'
            : '请先选择平台，再填写对应的机型与补贴信息'
        "
      />
    </template>
  </el-table>
</template>
