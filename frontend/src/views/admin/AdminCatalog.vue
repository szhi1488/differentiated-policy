<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { CLEAN_STATUS } from '../../constants/admin'
import { demoSources, demoVariants, demoJobs, demoLogs } from './data/demo'
const props = defineProps({ section: { type: String, required: true } })
const route = useRoute()
const keyword = ref('')
const brand = ref('')
const status = ref(typeof route.query.status === 'string' ? route.query.status : '')
const actorType = ref('')
const selected = ref(null)
const page = ref(1)
const pageSize = ref(5)
const rawFields = [
  ['goodsName', '商品名称'],
  ['goodsNo', '商品货号'],
  ['skuName', '规格名称'],
  ['cateName', '分类名称'],
  ['goodsField4', '自定义字段 4'],
  ['goodsField1', '自定义字段 1'],
  ['goodsField5', '自定义字段 5'],
]
const isSource = computed(() => ['sources', 'exceptions'].includes(props.section))
const dataset = computed(() =>
  props.section === 'variants' ? demoVariants : props.section === 'logs' ? demoLogs : demoSources,
)
const filtered = computed(() =>
  dataset.value.filter((item) => {
    if (props.section === 'exceptions' && item.status !== 'REVIEW_REQUIRED') {
      return false
    }
    return (
      (!brand.value || item.brand === brand.value) &&
      (!status.value || item.status === status.value) &&
      (!actorType.value || item.actorType === actorType.value) &&
      (!keyword.value.trim() ||
        Object.values(item).join(' ').toLowerCase().includes(keyword.value.trim().toLowerCase()))
    )
  }),
)
const rows = computed(() =>
  filtered.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value),
)
watch([keyword, brand, status, actorType], () => {
  page.value = 1
  selected.value = null
})
function reset() {
  keyword.value = ''
  brand.value = ''
  status.value = ''
  actorType.value = ''
  page.value = 1
  selected.value = null
}
const variantStatus = { ACTIVE: '可用', DRAFT: '草稿', DISABLED: '已停用' }
</script>
<template>
  <template v-if="section === 'sync'">
    <section class="admin-card">
      <div class="admin-section-head">
        <div>
          <h2>商品同步与自动清洗</h2>
          <p>只读导入一加手机、OPPO手机，原始入库完成后自动衔接清洗。</p>
        </div>
        <el-button type="primary" disabled>新建导入任务</el-button>
      </div>
      <div class="admin-sync-stages">
        <div>
          <span>01 · 原始导入</span>
          <h3>七字段先入库</h3>
          <p>两品牌分别续拉，保留原文</p>
          <el-tag type="success" size="small">演示：导入成功</el-tag>
        </div>
        <div>
          <span>02 · 系统清洗</span>
          <h3>唯一明确结果自动通过</h3>
          <p>规格、颜色与映射一并校验</p>
          <el-tag size="small">演示：已完成分流</el-tag>
        </div>
        <div>
          <span>03 · 异常处理</span>
          <h3>只处理需要核对的商品</h3>
          <p>缺失、歧义、冲突进入队列</p>
          <router-link class="admin-text-link" to="/admin/catalog/exceptions">
            查看 2 条演示异常 →
          </router-link>
        </div>
      </div>
      <el-alert
        title="清洗完成表示本轮商品已分流，仍可能存在待审核或技术失败；不代表全部已进入可用标准库。"
        type="info"
        :closable="false"
      />
    </section>
    <div class="admin-mini-metrics">
      <div
        v-for="item in [
          { label: '已导入', value: 6 },
          { label: '自动通过', value: 2 },
          { label: '异常待审核', value: 2 },
          { label: '人工通过', value: 1 },
          { label: '技术失败', value: 1 },
        ]"
        :key="item.label"
        class="admin-card"
      >
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
        <small>演示来源记录</small>
      </div>
    </div>
    <section class="admin-card">
      <div class="admin-section-head">
        <div>
          <h2>导入任务记录</h2>
          <p>导入与清洗状态独立；此处为当前任务结果展示样例。</p>
        </div>
      </div>
      <el-table :data="demoJobs" row-key="id">
        <el-table-column prop="id" label="任务编号" min-width="180" />
        <el-table-column prop="time" label="发起时间" min-width="185" />
        <el-table-column prop="actor" label="发起人" min-width="145" />
        <el-table-column label="品牌" width="160">
          <template #default>一加手机 / OPPO手机</template>
        </el-table-column>
        <el-table-column label="导入状态" width="115">
          <template #default="{ row }">
            <el-tag :type="row.importStatus === '导入成功' ? 'success' : 'danger'" size="small">
              {{ row.importStatus }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="cleanStatus" label="清洗阶段" width="125" />
        <el-table-column prop="imported" label="导入条数" width="95" />
        <el-table-column label="操作" width="95" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="selected = row">任务详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
    <section v-if="selected" class="admin-card">
      <div class="admin-section-head">
        <div>
          <h2>任务详情 · {{ selected.id }}</h2>
          <p>演示规则包 demo-rules-v1 · 不保留完整历史批次成员或游标轨迹</p>
        </div>
        <el-button text @click="selected = null">关闭</el-button>
      </div>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="导入阶段">{{ selected.importStatus }}</el-descriptions-item>
        <el-descriptions-item label="清洗阶段">{{ selected.cleanStatus }}</el-descriptions-item>
        <el-descriptions-item label="自动 / 人工通过">
          {{ selected.automatic }} / {{ selected.manual }}
        </el-descriptions-item>
        <el-descriptions-item label="待审核 / 技术失败">
          {{ selected.review }} / {{ selected.error }}
        </el-descriptions-item>
        <el-descriptions-item label="数据范围">
          仅两品牌七业务字段，示例不代表真实同步结果
        </el-descriptions-item>
        <el-descriptions-item label="停用商品策略">
          真实联调时明确，当前展示未配置
        </el-descriptions-item>
      </el-descriptions>
      <div class="admin-actions admin-card-actions">
        <el-button disabled>暂停导入</el-button>
        <el-button disabled>恢复导入</el-button>
        <el-button disabled>重试技术失败</el-button>
      </div>
    </section>
  </template>
  <template v-else>
    <section class="admin-card">
      <div class="admin-section-head">
        <div>
          <h2>
            {{
              section === 'exceptions'
                ? '商品异常队列'
                : section === 'sources'
                  ? '来源与清洗结果'
                  : section === 'variants'
                    ? '本地标准目录'
                    : '商品操作审计'
            }}
          </h2>
          <p>
            {{
              section === 'exceptions'
                ? '首次缺少颜色不能通过，也不能将未知颜色转换为不限颜色。'
                : section === 'variants'
                  ? '仅可用规格与所属可用颜色可供申请人选择；停用不改变政策快照。'
                  : section === 'logs'
                    ? '系统自动处理不冒用管理员身份，人工操作单独记录。'
                    : '原文不可覆盖，本轮清洗状态与当前有效映射分别查看。'
            }}
          </p>
        </div>
        <el-button v-if="section === 'variants'" disabled>补充标准规格</el-button>
        <el-button v-if="section === 'exceptions'" disabled>批量审核暂不开放</el-button>
      </div>
      <el-form class="admin-catalog-filters" inline @submit.prevent>
        <el-form-item v-if="section !== 'logs'" label="品牌">
          <el-select v-model="brand" placeholder="全部品牌" clearable aria-label="筛选品牌">
            <el-option
              v-for="item in ['一加手机', 'OPPO手机']"
              :key="item"
              :label="item"
              :value="item"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="section === 'sources'" label="清洗状态">
          <el-select v-model="status" placeholder="全部状态" clearable aria-label="清洗状态">
            <el-option
              v-for="(item, key) in CLEAN_STATUS"
              :key="key"
              :label="item.label"
              :value="key"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="section === 'variants'" label="目录状态">
          <el-select v-model="status" placeholder="全部状态" clearable>
            <el-option
              v-for="(label, key) in variantStatus"
              :key="key"
              :label="label"
              :value="key"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="section === 'logs'" label="操作者类型">
          <el-select v-model="actorType" placeholder="全部类型" clearable>
            <el-option label="SYSTEM · 系统" value="SYSTEM" />
            <el-option label="USER · 管理员" value="USER" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键词">
          <el-input
            v-model="keyword"
            placeholder="搜索当前演示记录"
            clearable
            aria-label="搜索商品记录"
          />
        </el-form-item>
        <el-form-item><el-button @click="reset">重置</el-button></el-form-item>
      </el-form>
      <el-table :data="rows" row-key="id" stripe @row-click="selected = $event">
        <template v-if="isSource">
          <el-table-column label="来源商品 / 本地编号" min-width="280">
            <template #default="{ row }">
              <strong class="admin-table-title">{{ row.goodsName }}</strong>
              <div class="admin-table-subtitle">{{ row.id }} · {{ row.brand }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="skuName" label="原始规格" min-width="150" />
          <el-table-column label="本轮清洗状态" width="130">
            <template #default="{ row }">
              <el-tag :type="CLEAN_STATUS[row.status].type" size="small">
                {{ CLEAN_STATUS[row.status].label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="reason" label="结果 / 异常原因" min-width="280" />
          <el-table-column label="当前有效映射" min-width="270">
            <template #default="{ row }">{{ row.mapping || '未映射' }}</template>
          </el-table-column>
        </template>
        <template v-else-if="section === 'variants'">
          <el-table-column prop="id" label="标准规格编码" min-width="160" />
          <el-table-column label="品牌 / 系列" min-width="135">
            <template #default="{ row }">
              {{ row.brand }}
              <div class="admin-table-subtitle">{{ row.series }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="model" label="型号" min-width="150" />
          <el-table-column prop="spec" label="配置" min-width="150" />
          <el-table-column label="所属颜色" min-width="260">
            <template #default="{ row }">
              <div class="admin-color-tags">
                <el-tag v-for="color in row.colors" :key="color" type="info" size="small">
                  {{ color }}
                </el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
                {{ variantStatus[row.status] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="origin" label="建立方式" min-width="135" />
        </template>
        <template v-else>
          <el-table-column prop="time" label="操作时间" width="185" />
          <el-table-column label="操作者" min-width="180">
            <template #default="{ row }">
              <el-tag :type="row.actorType === 'SYSTEM' ? 'info' : 'primary'" size="small">
                {{ row.actorType }}
              </el-tag>
              <div class="admin-table-subtitle">{{ row.actor }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="action" label="操作" min-width="190" />
          <el-table-column prop="target" label="本地对象" width="100" />
          <el-table-column prop="result" label="结果" width="110" />
          <el-table-column prop="reason" label="依据 / 原因" min-width="260" />
        </template>
        <el-table-column label="操作" fixed="right" width="110">
          <template #default="{ row }">
            <el-button type="primary" link @click.stop="selected = row">
              {{ section === 'exceptions' ? '核对预览' : '查看详情' }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="没有匹配的演示记录" :image-size="70" /></template>
      </el-table>
      <div class="admin-pagination">
        <span>共 {{ filtered.length }} 条演示记录</span>
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :total="filtered.length"
          :page-sizes="[5, 10, 20]"
          layout="prev, pager, next, sizes"
          background
          size="small"
          @size-change="page = 1"
        />
      </div>
    </section>
    <section v-if="selected" class="admin-card" aria-label="商品详情">
      <div class="admin-section-head">
        <div>
          <h2>
            {{
              isSource
                ? '来源原文与清洗证据'
                : section === 'variants'
                  ? '规格与所属颜色'
                  : '操作记录详情'
            }}
          </h2>
          <p>{{ selected.id }} · 演示样例</p>
        </div>
        <el-button text @click="selected = null">关闭</el-button>
      </div>
      <template v-if="isSource">
        <div class="admin-source-grid">
          <div>
            <h3 class="admin-subheading">七字段原文（只读）</h3>
            <el-descriptions :column="1" border>
              <el-descriptions-item
                v-for="[field, label] in rawFields"
                :key="field"
                :label="`${label} · ${field}`"
              >
                {{ selected[field] === '' ? '（空字符串）' : selected[field] }}
              </el-descriptions-item>
            </el-descriptions>
          </div>
          <div>
            <h3 class="admin-subheading">本轮候选与有效映射</h3>
            <dl class="admin-facts">
              <dt>规则版本</dt>
              <dd>{{ selected.rule }}</dd>
              <dt>候选结果</dt>
              <dd>{{ selected.candidate }}</dd>
              <dt>判断证据</dt>
              <dd>{{ selected.evidence }}</dd>
              <dt>异常 / 结果</dt>
              <dd>{{ selected.reason }}</dd>
              <dt>当前映射</dt>
              <dd>{{ selected.mapping || '未映射，暂不可供政策选择' }}</dd>
              <dt>人工保护</dt>
              <dd>
                {{
                  selected.status === 'MANUAL_PASSED'
                    ? '已保护，自动重跑不能覆盖'
                    : '人工确认与排除结果不得被自动重跑覆盖'
                }}
              </dd>
            </dl>
            <el-alert
              title="此处不修改来源原文；首次通过须有确定的规格及其具体颜色。"
              type="info"
              :closable="false"
            />
            <div class="admin-actions admin-card-actions">
              <template v-if="section === 'exceptions'">
                <el-button disabled>排除此商品</el-button>
                <el-button type="primary" disabled>确认映射</el-button>
              </template>
              <el-button v-else-if="selected.status === 'ERROR'" disabled>重试清洗</el-button>
            </div>
          </div>
        </div>
      </template>
      <template v-else-if="section === 'variants'">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="品牌 / 系列">
            {{ selected.brand }} / {{ selected.series }}
          </el-descriptions-item>
          <el-descriptions-item label="型号 / 配置">
            {{ selected.model }} / {{ selected.spec }}
          </el-descriptions-item>
          <el-descriptions-item label="目录状态">
            {{ variantStatus[selected.status] }}
          </el-descriptions-item>
          <el-descriptions-item label="最后更新">{{ selected.updatedAt }}</el-descriptions-item>
        </el-descriptions>
        <h3 class="admin-subheading admin-spaced">该规格独立颜色集合</h3>
        <el-table
          :data="
            selected.colors.map((name, index) => ({ id: `${selected.id}-C${index + 1}`, name }))
          "
          row-key="id"
        >
          <el-table-column prop="id" label="本地颜色编码（演示）" min-width="220" />
          <el-table-column prop="name" label="颜色名称" min-width="140" />
          <el-table-column label="所属规格" min-width="260">
            <template #default>{{ selected.model }} · {{ selected.spec }}</template>
          </el-table-column>
          <el-table-column label="颜色状态" width="150">
            <template #default><el-tag type="success" size="small">颜色记录可用</el-tag></template>
          </el-table-column>
        </el-table>
        <p class="admin-help">
          目录停用时，即使颜色记录可用也不能新增引用；同名颜色不跨规格共用。标准目录变化不改写已提交政策快照。
        </p>
        <div class="admin-actions admin-card-actions">
          <el-button disabled>补充颜色</el-button>
          <el-button disabled>
            {{ selected.status === 'DISABLED' ? '启用规格' : '停用规格' }}
          </el-button>
        </div>
      </template>
      <template v-else>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="操作人">
            {{ selected.actorType }} · {{ selected.actor }}
          </el-descriptions-item>
          <el-descriptions-item label="操作依据">{{ selected.reason }}</el-descriptions-item>
          <el-descriptions-item label="变更前">{{ selected.before }}</el-descriptions-item>
          <el-descriptions-item label="变更后">{{ selected.after }}</el-descriptions-item>
          <el-descriptions-item label="规则版本">demo-rules-v1</el-descriptions-item>
        </el-descriptions>
      </template>
    </section>
    <div v-else class="admin-detail-hint">
      点击记录查看{{
        isSource
          ? '七字段原文、候选与当前映射'
          : section === 'variants'
            ? '规格信息及其独立颜色集合'
            : '操作前后结果及依据'
      }}。
    </div>
  </template>
</template>
