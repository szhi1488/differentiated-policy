<script setup>
import { demoPolicies, demoSources, demoVariants, demoLogs } from './data/demo'
import { POLICY_STATUS } from '../../constants/admin'
import AppIcon from '../../components/AppIcon.vue'
const pending = demoPolicies.filter((item) => item.status === 'PENDING')
const cards = [
  {
    label: '待审核政策',
    value: pending.length,
    unit: '条',
    icon: 'shield',
    tone: 'blue',
    note: '查看本轮提交与逐平台明细',
    path: '/admin/reviews',
  },
  {
    label: '商品异常待审核',
    value: demoSources.filter((item) => item.status === 'REVIEW_REQUIRED').length,
    unit: '条',
    icon: 'alert',
    tone: 'amber',
    note: '缺失或冲突需要人工核对',
    path: '/admin/catalog/exceptions',
  },
  {
    label: '可用标准规格',
    value: demoVariants.filter((item) => item.status === 'ACTIVE').length,
    unit: '项',
    icon: 'layers',
    tone: 'green',
    note: '颜色按型号配置独立维护',
    path: '/admin/catalog/variants',
  },
  {
    label: '清洗技术失败',
    value: demoSources.filter((item) => item.status === 'ERROR').length,
    unit: '条',
    icon: 'sync',
    tone: 'purple',
    note: '重试入口仅展示，暂未接入',
    path: '/admin/catalog/sources?status=ERROR',
  },
]
</script>
<template>
  <div class="admin-metrics">
    <router-link
      v-for="card in cards"
      :key="card.label"
      :to="card.path"
      class="admin-metric admin-card"
    >
      <div>
        <span>{{ card.label }}</span>
        <p>
          <strong>{{ card.value }}</strong>
          {{ card.unit }}
        </p>
        <small>{{ card.note }}</small>
      </div>
      <span class="admin-metric-icon" :class="card.tone">
        <AppIcon :name="card.icon" :size="25" />
      </span>
    </router-link>
  </div>
  <div class="admin-overview-grid">
    <section class="admin-card">
      <div class="admin-section-head">
        <div>
          <h2>待处理政策</h2>
          <p>演示申请队列 · 按提交时间排列</p>
        </div>
        <router-link class="admin-text-link" to="/admin/reviews">查看全部 →</router-link>
      </div>
      <el-table :data="pending" row-key="id">
        <el-table-column label="申请主题" min-width="235">
          <template #default="{ row }">
            <strong class="admin-table-title">{{ row.title }}</strong>
            <div class="admin-table-subtitle">{{ row.code }} · {{ row.applicant }}</div>
          </template>
        </el-table-column>
        <el-table-column label="审核状态" width="100">
          <template #default="{ row }">
            <el-tag :type="POLICY_STATUS[row.status].type" size="small">待审核</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="90">
          <template #default="{ row }">
            <router-link
              class="admin-text-link"
              :to="{ path: '/admin/reviews', query: { policy: row.id } }"
            >
              查看详情
            </router-link>
          </template>
        </el-table-column>
      </el-table>
    </section>
    <section class="admin-card admin-flow-card">
      <div class="admin-section-head">
        <div>
          <h2>商品处理流程</h2>
          <p>明确结果自动入库，异常才需人工审核</p>
        </div>
      </div>
      <ol class="admin-flow">
        <li>
          <span>01</span>
          <div>
            <strong>保存七字段原文</strong>
            <p>仅一加手机、OPPO手机，只读导入</p>
          </div>
        </li>
        <li>
          <span>02</span>
          <div>
            <strong>自动清洗与校验</strong>
            <p>识别系列、型号、配置与具体颜色</p>
          </div>
        </li>
        <li>
          <span>03</span>
          <div>
            <strong>唯一结果进入标准库</strong>
            <p>异常单独分流，不阻塞明确商品</p>
          </div>
        </li>
        <li>
          <span>04</span>
          <div>
            <strong>政策选择可用规格</strong>
            <p>明确不限颜色或指定所属颜色</p>
          </div>
        </li>
      </ol>
      <router-link class="admin-text-link" to="/admin/catalog/sync">查看导入与清洗 →</router-link>
    </section>
    <section class="admin-card">
      <div class="admin-section-head">
        <div>
          <h2>最近商品操作</h2>
          <p>SYSTEM 与 USER 操作分别留痕</p>
        </div>
        <router-link class="admin-text-link" to="/admin/catalog/logs">全部历史 →</router-link>
      </div>
      <div class="admin-activity-list">
        <div v-for="item in demoLogs.slice(0, 3)" :key="item.id">
          <span class="admin-activity-dot"></span>
          <div>
            <strong>{{ item.action }}</strong>
            <p>{{ item.actor }} · {{ item.target }} · {{ item.result }}</p>
          </div>
          <time>{{ item.time.slice(11) }}</time>
        </div>
      </div>
    </section>
    <section class="admin-card admin-boundary-card">
      <span class="admin-eyebrow">WORKSPACE NOTE</span>
      <h2>让每次审核都有据可查</h2>
      <p>从提交快照到规格颜色，再到系统清洗证据，保留每一次判断的上下文。</p>
      <div>
        <el-tag effect="plain">本轮内容</el-tag>
        <el-tag effect="plain">逐平台明细</el-tag>
        <el-tag effect="plain">操作追溯</el-tag>
      </div>
      <small>审核通过与当前生效分别判断；覆盖口径仍待确认。</small>
    </section>
  </div>
</template>
