<script setup>
import { computed, nextTick, shallowRef } from 'vue'
import AppIcon from '../../../components/AppIcon.vue'
import ReviewerPicker from './ReviewerPicker.vue'

const props = defineProps({
  brand: { type: Object, required: true },
  disabled: Boolean,
})
const emit = defineEmits(['assign', 'updateMode'])
const fixedMode = computed(() => ['OPPO手机', '一加手机'].includes(props.brand.brandName))
const stages = computed(() => {
  const brand = props.brand
  const bySeries = brand.productMode === 'BY_SERIES'
  const productRows = brand.series.map((series) => ({
    seriesId: series.seriesId,
    scope: series.seriesName,
    reviewer: bySeries ? series.productReviewer : brand.brandProductReviewer,
  }))
  if (!bySeries && !productRows.length) {
    productRows.push({ scope: '暂无已建档系列', reviewer: brand.brandProductReviewer })
  }
  return [
    {
      group: 'PRODUCT',
      label: '产品运营初审',
      bySeries,
      reviewerSpan: bySeries ? 1 : productRows.length,
      rows: productRows,
    },
    {
      group: 'PURCHASE',
      label: '采购终审',
      bySeries: false,
      reviewerSpan: 1,
      rows: [{ scope: '全部系列', reviewer: brand.purchaseReviewer }],
    },
  ]
})

const editing = shallowRef(null)
function assignmentTitle(stage, row) {
  return [props.brand.brandName, stage.bySeries ? row.scope : '全部系列', stage.label].join(' · ')
}
function openPicker(stage, row, trigger) {
  editing.value = {
    title: assignmentTitle(stage, row),
    group: stage.group,
    seriesId: stage.bySeries ? row.seriesId : undefined,
    reviewer: row.reviewer,
    trigger,
  }
}
function closePicker() {
  const trigger = editing.value?.trigger
  editing.value = null
  nextTick(() => trigger?.focus({ preventScroll: true }))
}
function confirmReviewer(reviewer) {
  const { group, seriesId } = editing.value
  emit('assign', { group, seriesId, reviewer })
  closePicker()
}
</script>

<template>
  <div class="assignment-sheet">
    <div v-if="!fixedMode" class="mode-setting">
      <span>初审分工</span>
      <el-radio-group
        :model-value="brand.productMode"
        :aria-label="brand.brandName + '初审分工'"
        :disabled="disabled"
        @update:model-value="emit('updateMode', $event)"
      >
        <el-radio value="BY_SERIES">按系列</el-radio>
        <el-radio value="BY_BRAND">按品牌</el-radio>
      </el-radio-group>
    </div>

    <div class="table-frame">
      <table :aria-label="brand.brandName + '审核分工'">
        <colgroup>
          <col class="stage-column" />
          <col class="scope-column" />
          <col />
          <col class="action-column" />
        </colgroup>
        <thead>
          <tr>
            <th scope="col">审核环节</th>
            <th scope="col">适用范围</th>
            <th scope="col">审核人</th>
            <th scope="col">操作</th>
          </tr>
        </thead>
        <tbody
          v-for="stage in stages"
          :key="stage.group"
          :style="{ '--reviewer-span': stage.reviewerSpan }"
        >
          <tr
            v-for="(row, index) in stage.rows.length ? stage.rows : [null]"
            :key="row?.seriesId ?? stage.group"
          >
            <th
              v-if="index === 0"
              class="stage-cell"
              scope="rowgroup"
              :rowspan="stage.rows.length || 1"
            >
              <div class="stage-info">
                <span class="stage-icon">
                  <AppIcon :name="stage.group === 'PRODUCT' ? 'users' : 'shield'" :size="17" />
                </span>
                <div>
                  <strong>{{ stage.label }}</strong>
                  <small>{{ stage.bySeries ? '按系列' : '按品牌' }}</small>
                </div>
              </div>
            </th>
            <template v-if="row">
              <th class="scope-cell" scope="row">{{ row.scope }}</th>
              <template v-if="index === 0 || stage.bySeries">
                <td class="reviewer-cell" :rowspan="stage.reviewerSpan">
                  <template v-if="row.reviewer">
                    <strong>{{ row.reviewer.name }}</strong>
                    <small v-if="row.reviewer.department">{{ row.reviewer.department }}</small>
                  </template>
                  <span v-else class="unassigned">未指定</span>
                </td>
                <td class="row-action" :rowspan="stage.reviewerSpan">
                  <el-button
                    link
                    type="primary"
                    :disabled="disabled"
                    :aria-label="`${assignmentTitle(stage, row)}，${row.reviewer ? '更换' : '指定'}审核人`"
                    @click="openPicker(stage, row, $event.currentTarget)"
                  >
                    {{ row.reviewer ? '更换' : '指定' }}
                  </el-button>
                </td>
              </template>
            </template>
            <td v-else colspan="3" class="empty-series">该品牌尚无标准系列</td>
          </tr>
        </tbody>
      </table>
    </div>

    <ReviewerPicker
      v-if="editing"
      :group="editing.group"
      :model-value="editing.reviewer"
      :title="editing.title"
      @select="confirmReviewer"
      @close="closePicker"
    />
  </div>
</template>

<style scoped>
.assignment-sheet {
  min-width: 0;
  container-type: inline-size;
}
.mode-setting {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 18px;
  margin-bottom: 12px;
  color: #465b74;
}
.table-frame {
  border: 1px solid #dfe6ef;
  border-radius: 8px;
  overflow: hidden;
}
table {
  width: 100%;
  table-layout: fixed;
  border-collapse: collapse;
  color: #293e58;
  font-size: 14px;
  line-height: 1.45;
}
.stage-column {
  width: 25%;
}
.scope-column {
  width: 25%;
}
.action-column {
  width: 84px;
}
th,
td {
  padding: 11px 16px;
  text-align: left;
  overflow-wrap: anywhere;
}
thead th {
  background: #f4f7fb;
  color: #52647c;
  font-size: 13px;
  font-weight: 500;
}
tbody th,
tbody td {
  border-top: 1px solid #e6ebf2;
}
.stage-cell {
  vertical-align: top;
  background: #fafbfd;
  border-right: 1px solid #e6ebf2;
  padding-top: 14px;
}
.stage-info {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}
.stage-icon {
  flex: none;
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 7px;
  color: #416ba6;
  background: #edf3fc;
}
.stage-info strong,
.reviewer-cell strong {
  font-weight: 600;
}
.stage-info small,
.reviewer-cell small {
  display: block;
  margin-top: 2px;
  font-size: 12px;
  font-weight: 400;
  color: #67788c;
}
.scope-cell {
  font-weight: 500;
}
.unassigned,
.empty-series {
  color: #68798b;
}
.row-action,
thead th:last-child {
  text-align: center;
}
.row-action .el-button {
  min-height: 28px;
}
@container (max-width: 520px) {
  table,
  thead {
    display: block;
  }
  colgroup,
  thead th:first-child {
    display: none;
  }
  thead tr,
  tbody {
    display: grid;
    grid-template-columns: minmax(0, 0.95fr) minmax(0, 1.15fr) 56px;
    align-items: stretch;
  }
  tbody tr {
    display: contents;
  }
  .scope-cell {
    grid-column: 1;
  }
  .reviewer-cell {
    grid-column: 2;
    grid-row: span var(--reviewer-span);
  }
  .row-action {
    grid-column: 3;
    grid-row: span var(--reviewer-span);
  }
  th,
  td {
    align-content: center;
    padding: 8px 10px;
  }
  .stage-cell,
  .empty-series {
    grid-column: 1 / -1;
  }
  .stage-cell {
    padding: 8px 10px;
    border-right: 0;
  }
  .stage-info,
  .stage-info > div {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .stage-icon {
    width: 24px;
    height: 24px;
  }
  .stage-info small {
    margin: 0;
  }
  .row-action .el-button {
    min-height: 34px;
  }
}
</style>
