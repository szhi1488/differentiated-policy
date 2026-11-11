<script setup>
import { computed, ref, watch } from 'vue'
import { POLICY_STATUS } from '../../../constants/admin'
const props = defineProps({ policy: { type: Object, required: true }, review: Boolean })
defineEmits(['close'])
const tab = ref('details')
const platform = ref('全部')
watch(
  () => props.policy.id,
  () => {
    tab.value = 'details'
    platform.value = '全部'
  },
)
const details = computed(() =>
  props.policy.details.filter(
    (row) => platform.value === '全部' || row.platform === platform.value,
  ),
)
const history = computed(
  () =>
    props.policy.history || [
      ...(props.policy.status === 'APPROVED' || props.policy.status === 'REJECTED'
        ? [
            {
              time: '2026-11-11 10:00:00',
              action: `第 ${props.policy.round} 轮 · ${POLICY_STATUS[props.policy.status].label}`,
              actor: '审核员（演示）',
              remark: props.policy.reviewRemark || '演示审核意见：已核对本轮申请。',
            },
          ]
        : []),
      {
        time: props.policy.submittedAt,
        action: `第 ${props.policy.round} 轮 · 提交`,
        actor: props.policy.applicant,
        remark: '演示提交记录，真实不可变快照尚未接入。',
      },
    ],
)
</script>
<template>
  <section class="admin-card admin-policy-detail" aria-label="政策详情">
    <div class="admin-section-head">
      <div>
        <h2>
          政策详情
          <el-tag size="small" :type="POLICY_STATUS[policy.status].type">
            {{ POLICY_STATUS[policy.status].label }}
          </el-tag>
        </h2>
        <p>{{ policy.code }} · 第 {{ policy.round }} 轮提交 · 演示样例</p>
      </div>
      <el-button text aria-label="关闭政策详情" @click="$emit('close')">关闭</el-button>
    </div>
    <el-tabs v-model="tab">
      <el-tab-pane label="申请内容" name="details">
        <div class="admin-detail-grid">
          <div>
            <h3 class="admin-subheading">基本信息</h3>
            <dl class="admin-facts">
              <dt>申请人</dt>
              <dd>{{ policy.applicant }}</dd>
              <dt>渠道</dt>
              <dd>{{ policy.channel }}</dd>
              <dt>平台</dt>
              <dd>{{ policy.platforms.join('、') }}</dd>
              <dt>核销类型</dt>
              <dd>{{ policy.verifyType || '不适用' }}</dd>
              <dt>涉及场景</dt>
              <dd>{{ policy.scene || '不适用' }}</dd>
              <dt>预估政策</dt>
              <dd>{{ policy.estimated || '不适用' }}</dd>
              <dt>政策开始</dt>
              <dd>{{ policy.start }}</dd>
              <dt>政策结束</dt>
              <dd>{{ policy.end }}</dd>
            </dl>
          </div>
          <div class="admin-detail-main">
            <h3 class="admin-subheading">逐平台机型及补贴信息</h3>
            <el-radio-group
              v-model="platform"
              size="small"
              class="admin-platform-tabs"
              aria-label="明细平台"
            >
              <el-radio-button label="全部" value="全部" />
              <el-radio-button
                v-for="item in policy.platforms"
                :key="item"
                :label="item"
                :value="item"
              />
            </el-radio-group>
            <el-table :data="details" row-key="id" border>
              <el-table-column prop="platform" label="平台" width="85" />
              <el-table-column prop="model" label="标准型号" min-width="135" />
              <el-table-column prop="spec" label="配置" min-width="140" />
              <el-table-column prop="color" label="颜色范围 / 颜色" min-width="135" />
              <el-table-column prop="quantity" label="数量" width="90" />
              <el-table-column prop="subsidy" label="补贴参数" min-width="300" />
            </el-table>
            <p class="admin-help">
              不限颜色仅适用于当前型号配置；各平台的数量与补贴独立。数量未填写时不补为 0。
            </p>
          </div>
        </div>
        <div class="admin-detail-notes">
          <div>
            <h3 class="admin-subheading">特别备注</h3>
            <p>{{ policy.remark || '无' }}</p>
          </div>
          <div>
            <h3 class="admin-subheading">附件</h3>
            <p class="admin-muted">暂无演示附件，文件下载未接入</p>
          </div>
        </div>
      </el-tab-pane>
      <el-tab-pane label="提交与审核历史" name="history">
        <el-timeline class="admin-timeline">
          <el-timeline-item
            v-for="(item, index) in history"
            :key="`${item.time}-${index}`"
            :timestamp="item.time"
            placement="top"
            :type="index === 0 ? 'primary' : 'info'"
          >
            <h3>{{ item.action }}</h3>
            <p>{{ item.actor }} · {{ item.remark }}</p>
          </el-timeline-item>
        </el-timeline>
        <el-alert
          title="此处展示历史结构样例，历轮完整提交快照查询尚未接入。"
          type="info"
          :closable="false"
        />
      </el-tab-pane>
      <el-tab-pane label="候选影响" name="impact">
        <el-empty description="覆盖口径待确认，当前无法判定实际影响" :image-size="80" />
        <p class="admin-help">
          平台、规格、颜色范围和时间可能形成交集。此展示不计算替换关系，也不代表没有覆盖。
        </p>
      </el-tab-pane>
    </el-tabs>
    <div v-if="review && policy.status === 'PENDING'" class="admin-review-footer">
      <div>
        <strong>本轮审核</strong>
        <p>操作区仅展示；审核意见及决定未提交。</p>
      </div>
      <el-input
        type="textarea"
        :rows="2"
        disabled
        placeholder="审核意见（展示版暂不可填写）"
        aria-label="审核意见"
      />
      <div class="admin-actions">
        <el-button disabled>驳回申请</el-button>
        <el-button type="primary" disabled>通过审核</el-button>
      </div>
    </div>
  </section>
</template>
