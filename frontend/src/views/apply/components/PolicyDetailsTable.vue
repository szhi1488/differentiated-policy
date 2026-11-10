<script setup>
import AppIcon from '../../../components/AppIcon.vue'
import { PRICE_BASES } from '../../../constants/policy'
import { changeSubsidy } from '../../../utils/policy-form'

defineProps({
  platformName: { type: String, default: '' },
  hasPlatform: { type: Boolean, default: false },
})
const rows = defineModel({ type: Array, required: true })
</script>

<template>
  <div class="details-table-wrap">
    <table class="details-table">
      <thead>
        <tr>
          <th class="number-col">序号</th>
          <th>
            涉及型号
            <b>*</b>
          </th>
          <th>
            单型号
            <b>*</b>
          </th>
          <th>
            配置
            <b>*</b>
          </th>
          <th>颜色</th>
          <th>数量（选填）</th>
          <th>
            补贴方式
            <b>*</b>
          </th>
          <th class="subsidy-col">
            补贴详情
            <b>*</b>
          </th>
          <th class="operation-col">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, index) in rows" :key="row.key">
          <td class="row-number">{{ index + 1 }}</td>
          <td>
            <input
              v-model="row.model"
              :aria-label="`${platformName}第${index + 1}行涉及型号`"
              placeholder="请输入型号"
            />
          </td>
          <td>
            <input
              v-model="row.singleModel"
              :aria-label="`第${index + 1}行单型号`"
              placeholder="单型号"
            />
          </td>
          <td>
            <input v-model="row.spec" :aria-label="`第${index + 1}行配置`" placeholder="配置" />
          </td>
          <td>
            <input v-model="row.color" :aria-label="`第${index + 1}行颜色`" placeholder="选填" />
          </td>
          <td>
            <input
              v-model="row.quantity"
              type="number"
              min="0"
              step="1"
              :aria-label="`第${index + 1}行数量`"
              placeholder="选填"
            />
          </td>
          <td>
            <select
              :value="row.mode"
              :aria-label="`第${index + 1}行补贴方式`"
              @change="changeSubsidy(row, $event.target.value)"
            >
              <option value="FIXED">固定补贴金额</option>
              <option value="RATIO">补贴比例</option>
            </select>
          </td>
          <td class="subsidy-inputs">
            <template v-if="row.mode === 'FIXED'">
              <label>
                补贴金额
                <input
                  v-model="row.fixedAmount"
                  inputmode="decimal"
                  placeholder="0.00"
                  :aria-label="`第${index + 1}行固定补贴金额`"
                />
                <span>元</span>
              </label>
              <label>
                补贴上限
                <input
                  v-model="row.maxAmount"
                  inputmode="decimal"
                  placeholder="0.00"
                  :aria-label="`第${index + 1}行补贴上限`"
                />
                <span>元</span>
              </label>
            </template>
            <template v-else>
              <label>
                补贴比例
                <input
                  v-model="row.ratio"
                  inputmode="decimal"
                  placeholder="0.00"
                  :aria-label="`第${index + 1}行补贴比例`"
                />
                <span>%</span>
              </label>
              <label>
                计算基数
                <select v-model="row.priceBase" :aria-label="`第${index + 1}行计算基数`">
                  <option disabled value="">请选择</option>
                  <option v-for="base in PRICE_BASES" :key="base">{{ base }}</option>
                </select>
              </label>
            </template>
          </td>
          <td>
            <button
              type="button"
              class="text-button"
              :aria-label="`删除第${index + 1}行机型`"
              @click="rows.splice(index, 1)"
            >
              删除
            </button>
          </td>
        </tr>
        <tr v-if="!rows.length">
          <td colspan="9" class="table-empty">
            <AppIcon name="list" :size="28" />
            <p>
              {{
                hasPlatform
                  ? '还未添加机型，点击右上方「添加机型」开始填写'
                  : '请先选择平台，再填写对应的机型与补贴信息'
              }}
            </p>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
