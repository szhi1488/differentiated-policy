<script setup>
import { computed, ref, useId, watch } from 'vue'
import { PLATFORM_LABELS } from '../../../constants/policy'
import { policyTime } from '../../../utils/policy-adapter'
import { subsidyText } from '../../../utils/policy-coverage'
import { coverageProducts, groupCoverageProducts } from '../../../utils/policy-coverage-groups'
import AdminPolicyCoverageReference from './AdminPolicyCoverageReference.vue'

const props = defineProps({ detail: { type: Object, required: true } })
defineEmits(['show-reference'])
const outcome = ref(''),
  platform = ref(''),
  keyword = ref(''),
  page = ref(1)
const pageSize = 10
const filterId = useId()
const pending = computed(() => props.detail.policy.status === 'PENDING')
const coverage = computed(() => props.detail.coverage)
const labels = {
  NEW: '生效时新增',
  REPLACES: '生效时覆盖',
  RETAINED: '较新的申请继续适用',
  EFFECTIVE: '当前生效',
  SCHEDULED: '等待开始时间',
  SUPERSEDED: '已被较新申请覆盖',
  PARTIAL: '部分覆盖',
}
const outcomeTypes = {
  NEW: 'primary',
  REPLACES: 'warning',
  RETAINED: 'info',
  EFFECTIVE: 'success',
  SCHEDULED: 'info',
  SUPERSEDED: 'info',
  PARTIAL: 'warning',
}
const outcomes = computed(() =>
  pending.value ? ['NEW', 'REPLACES', 'RETAINED'] : ['EFFECTIVE', 'SCHEDULED', 'SUPERSEDED'],
)
const rows = computed(() => coverageProducts(props.detail))
const counts = computed(() =>
  rows.value.reduce((result, row) => {
    result[row.outcome] = (result[row.outcome] ?? 0) + 1
    return result
  }, {}),
)
const platforms = computed(() => [...new Set(rows.value.map((row) => row.platformCode))])
const filtered = computed(() =>
  rows.value.filter(
    (row) =>
      (!outcome.value || row.outcome === outcome.value) &&
      (!platform.value || row.platformCode === platform.value) &&
      `${row.name} ${row.configurationName ?? ''} ${row.color ?? ''} ${row.goodsId}`
        .toLowerCase()
        .includes(keyword.value.trim().toLowerCase()),
  ),
)
const groups = computed(() => groupCoverageProducts(filtered.value))
const visibleRows = computed(() =>
  groups.value.slice((page.value - 1) * pageSize, page.value * pageSize),
)
watch([outcome, platform, keyword, () => props.detail], () => {
  page.value = 1
})
const time = (value) => policyTime(value).replace('T', ' ')
function reset() {
  outcome.value = ''
  platform.value = ''
  keyword.value = ''
}
const outcomeText = (counts) =>
  Object.entries(counts)
    .map(([value, count]) => `${labels[value]} ${count} 件`)
    .join(' · ')
</script>

<template>
  <section class="admin-policy-detail-section admin-policy-coverage" aria-label="覆盖结果">
    <div class="coverage-heading">
      <h3>{{ pending ? '覆盖预览' : '生效范围' }}</h3>
      <p v-if="!pending" class="coverage-time">
        生效条件满足于
        <time>{{ time(coverage.effectiveFrom) }}</time>
        <span>（北京时间）</span>
      </p>
    </div>
    <div class="coverage-controls">
      <div class="coverage-summary" aria-label="覆盖结果汇总">
        <el-button :type="!outcome ? 'primary' : 'default'" plain @click="outcome = ''">
          全部 {{ rows.length }} 项
        </el-button>
        <el-button
          v-for="value in outcomes"
          :key="value"
          :type="outcome === value ? 'primary' : 'default'"
          plain
          @click="outcome = value"
        >
          {{ labels[value] }} {{ counts[value] ?? 0 }} 项
        </el-button>
      </div>
      <div class="coverage-filters">
        <div class="coverage-filter-platform">
          <label :for="`${filterId}-platform`">平台</label>
          <el-select
            :id="`${filterId}-platform`"
            v-model="platform"
            aria-label="覆盖平台"
            placeholder="全部平台"
            clearable
          >
            <el-option
              v-for="code in platforms"
              :key="code"
              :value="code"
              :label="PLATFORM_LABELS[code] ?? code"
            />
          </el-select>
        </div>
        <div class="coverage-filter-keyword">
          <label :for="`${filterId}-keyword`">商品关键词</label>
          <el-input
            :id="`${filterId}-keyword`"
            v-model="keyword"
            aria-label="覆盖商品关键词"
            placeholder="机型、配置、颜色或商品 ID"
            clearable
          />
        </div>
        <el-button @click="reset">重置</el-button>
        <span v-if="!pending" class="coverage-query-time">查询时间 {{ time(coverage.at) }}</span>
      </div>
    </div>
    <el-table
      :data="visibleRows"
      row-key="key"
      size="small"
      class="admin-policy-table coverage-main-table"
    >
      <el-table-column type="expand" width="44">
        <template #default="{ row }">
          <section class="coverage-product-details" :aria-label="`${row.name} 商品覆盖明细`">
            <div class="coverage-products-heading">
              <h4>商品覆盖明细</h4>
              <span>{{ PLATFORM_LABELS[row.platformCode] }} · {{ row.members.length }} 件商品</span>
            </div>
            <el-table :data="row.members" row-key="key" size="small" class="admin-policy-table">
              <el-table-column prop="color" label="颜色" min-width="100">
                <template #default="{ row: member }">{{ member.color ?? '颜色未记录' }}</template>
              </el-table-column>
              <el-table-column prop="goodsId" label="商品 ID" min-width="100" />
              <el-table-column prop="name" label="商品名称" min-width="180" />
              <el-table-column label="适用结果" min-width="170">
                <template #default="{ row: member }">
                  <el-tag :type="outcomeTypes[member.outcome]" size="small" effect="light">
                    {{ labels[member.outcome] }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="本申请补贴" min-width="210">
                <template #default="{ row: member }">
                  {{ subsidyText(member.proposedSubsidy) }}
                </template>
              </el-table-column>
              <el-table-column :label="pending ? '政策及补贴对照' : '当前适用政策'" min-width="200">
                <template #default="{ row: member }">
                  <AdminPolicyCoverageReference
                    :row="member"
                    :policy-id="detail.policy.id"
                    @show-reference="$emit('show-reference', $event)"
                  />
                </template>
              </el-table-column>
            </el-table>
          </section>
        </template>
      </el-table-column>
      <el-table-column label="型号及配置" min-width="240">
        <template #default="{ row }">
          <div class="coverage-model-name">{{ row.name }}</div>
          <div class="coverage-selection">
            <span>已选 {{ row.colorCount }} 色 · {{ row.members.length }} 件商品</span>
            <el-tag v-if="row.cardType === 'SPECIFIED'" size="small" effect="plain">
              指定单品
            </el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="平台" width="90">
        <template #default="{ row }">{{ PLATFORM_LABELS[row.platformCode] }}</template>
      </el-table-column>
      <el-table-column label="适用结果" min-width="200">
        <template #default="{ row }">
          <el-tag :type="outcomeTypes[row.outcome]" size="small" effect="light">
            {{ labels[row.outcome] }}
          </el-tag>
          <p v-if="row.outcome === 'PARTIAL'" class="coverage-result-counts">
            {{ outcomeText(row.outcomeCounts) }}
          </p>
        </template>
      </el-table-column>
      <el-table-column label="本申请补贴" min-width="230">
        <template #default="{ row }">
          {{ row.subsidyVaries ? '补贴不同，展开逐项查看' : subsidyText(row.proposedSubsidy) }}
        </template>
      </el-table-column>
      <el-table-column :label="pending ? '政策及补贴对照' : '当前适用政策'" min-width="190">
        <template #default="{ row }">
          <AdminPolicyCoverageReference
            :row="row"
            :policy-id="detail.policy.id"
            @show-reference="$emit('show-reference', $event)"
          />
        </template>
      </el-table-column>
      <template #empty><el-empty :image-size="60" description="没有符合条件的覆盖结果" /></template>
    </el-table>
    <footer class="admin-policy-footer coverage-footer">
      <span aria-live="polite">
        展示 {{ groups.length }} 组，涉及 {{ filtered.length }} 项商品平台范围 / 全部
        {{ rows.length }} 项
      </span>
      <el-pagination
        v-if="groups.length > pageSize"
        v-model:current-page="page"
        :page-size="pageSize"
        :total="groups.length"
        :pager-count="5"
        layout="prev, pager, next"
        size="small"
      />
    </footer>
  </section>
</template>

<style scoped>
.coverage-heading,
.coverage-products-heading {
  display: flex;
  align-items: center;
  gap: 12px;
}
.coverage-heading {
  justify-content: space-between;
  flex-wrap: wrap;
  margin-bottom: 16px;
}
.coverage-heading h3 {
  margin: 0;
}
.coverage-time,
.coverage-query-time,
.coverage-products-heading > span {
  color: var(--policy-muted);
  font-size: 12px;
}
.coverage-time {
  margin: 0;
  line-height: 1.8;
}
.coverage-time time {
  font-variant-numeric: tabular-nums;
}
.coverage-controls {
  border: 1px solid var(--policy-line);
  border-radius: 6px;
  background: #f8fafd;
  padding: 14px 16px;
  margin-bottom: 16px;
}
.coverage-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding-bottom: 14px;
  margin-bottom: 12px;
  border-bottom: 1px solid var(--policy-line);
}
.coverage-summary .el-button {
  margin: 0;
  height: auto;
  min-height: 30px;
  font-size: 12px;
  padding: 7px 12px;
  white-space: normal;
}
.coverage-filters {
  display: flex;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: 12px;
}
.coverage-filters label {
  display: block;
  font-size: 12px;
  margin-bottom: 6px;
  color: var(--policy-muted);
}
.coverage-filter-platform {
  width: 160px;
}
.coverage-filter-keyword {
  width: 320px;
  max-width: 100%;
}
.coverage-query-time {
  margin-left: auto;
  line-height: 32px;
}
.coverage-model-name {
  font-weight: 600;
  line-height: 1.7;
}
.coverage-selection {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 3px;
  color: var(--policy-muted);
  font-size: 12px;
}
.coverage-product-details {
  padding: 16px 20px;
  min-width: 0;
  background: #f8fafd;
  border-left: 3px solid #c7dcfc;
}
.coverage-products-heading {
  justify-content: space-between;
  margin-bottom: 12px;
}
.coverage-products-heading h4 {
  margin: 0;
  font-size: 13px;
}
.coverage-result-counts {
  margin: 6px 0 0;
  color: var(--policy-muted);
  font-size: 12px;
  white-space: normal;
}
.coverage-main-table :deep(.el-table__cell) {
  padding-top: 12px;
  padding-bottom: 12px;
}
.coverage-main-table :deep(.el-table__expanded-cell) {
  padding: 0;
}
.coverage-footer {
  align-items: flex-start;
}
@media (max-width: 660px) {
  .coverage-heading {
    gap: 8px;
  }
  .coverage-controls {
    padding: 12px;
  }
  .coverage-filter-platform {
    width: 100%;
  }
  .coverage-filter-keyword {
    flex: 1 1 180px;
  }
  .coverage-query-time {
    flex-basis: 100%;
    margin: 0;
    line-height: 1.6;
  }
}
</style>
