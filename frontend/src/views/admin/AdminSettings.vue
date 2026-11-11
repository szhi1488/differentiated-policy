<script setup>
import { inject } from 'vue'
const user = inject('adminUser')
</script>
<template>
  <div class="admin-settings-grid">
    <section class="admin-card">
      <div class="admin-section-head">
        <div>
          <h2>当前账号</h2>
          <p>身份信息来自服务端登录会话</p>
        </div>
        <el-tag>ADMIN</el-tag>
      </div>
      <dl class="admin-facts">
        <dt>姓名</dt>
        <dd>{{ user.name || '管理员' }}</dd>
        <dt>本地用户 ID</dt>
        <dd>{{ user.id }}</dd>
        <dt>角色</dt>
        <dd>采购管理员</dd>
        <dt>访问边界</dt>
        <dd>以服务端账号、角色与租户校验为准</dd>
        <dt>时间口径</dt>
        <dd>Asia/Shanghai · 精确到秒</dd>
      </dl>
    </section>
    <section class="admin-card">
      <div class="admin-section-head">
        <div>
          <h2>接入状态</h2>
          <p>区分页面展示与真实业务能力</p>
        </div>
      </div>
      <div class="admin-integration-row">
        <span>飞书登录与会话</span>
        <el-tag type="success" size="small">代码已接入</el-tag>
      </div>
      <div
        v-for="item in [
          '政策查询与审核接口',
          '吉客云导入与自动清洗',
          '标准规格与颜色维护',
          '附件存储与下载',
        ]"
        :key="item"
        class="admin-integration-row"
      >
        <span>{{ item }}</span>
        <el-tag type="info" size="small">尚未接入</el-tag>
      </div>
      <p class="admin-help">当前页面不检测远端授权配置；登录、会话与业务接口应分别验证。</p>
    </section>
    <section class="admin-card admin-settings-wide">
      <div class="admin-section-head">
        <div>
          <h2>后续配置事项</h2>
          <p>这里只陈列接入前需要明确的规则，不提供在线配置或权限修改。</p>
        </div>
      </div>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="审核权限">
          管理员名单、跨人可见范围、自审规则与拒绝原因要求待确认。
        </el-descriptions-item>
        <el-descriptions-item label="政策生效">
          时间端点、覆盖优先级、颜色范围交集与局部生效口径待确认。
        </el-descriptions-item>
        <el-descriptions-item label="外部接入">
          吉客云真实返回、限流与结束条件待联调；只读取两品牌七字段。
        </el-descriptions-item>
        <el-descriptions-item label="部署与附件">
          部署域名、同源代理、会话部署、附件容量与访问权限待确定。
        </el-descriptions-item>
      </el-descriptions>
    </section>
  </div>
</template>
