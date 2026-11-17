<script setup>
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import AppIcon from '../../../components/AppIcon.vue'
import { USER_ROLE_LABELS, USER_STATUS_LABELS } from '../../../constants/users'

defineProps({
  open: Boolean,
  user: { type: Object, default: null },
  restriction: { type: String, default: '' },
  currentId: { type: String, default: '' },
  loading: Boolean,
  error: { type: Object, default: null },
})
defineEmits(['update:open', 'role', 'status', 'retry'])

async function copyId(value) {
  try {
    await navigator.clipboard.writeText(value)
    ElMessage.success('飞书用户标识已复制')
  } catch {
    ElMessage.warning('复制失败，请选中标识后手动复制。')
  }
}
</script>

<template>
  <el-drawer
    :model-value="open"
    title="用户详情"
    class="admin-user-drawer"
    size="min(480px, 100%)"
    destroy-on-close
    @update:model-value="$emit('update:open', $event)"
  >
    <p v-if="loading" role="status">正在读取用户详情…</p>
    <el-alert v-else-if="error" :title="error.message" type="error" :closable="false" show-icon>
      <el-button link @click="$emit('retry')">重新读取详情</el-button>
    </el-alert>
    <template v-else-if="user">
      <div class="admin-user-profile">
        <el-avatar :size="58" :src="user.avatarUrl || undefined">
          {{ user.name.slice(0, 1) }}
        </el-avatar>
        <div>
          <h2>{{ user.name }}</h2>
          <span>{{ user.department || '部门未填写' }}</span>
        </div>
        <el-tag v-if="user.id === currentId" size="small" effect="plain">当前账号</el-tag>
      </div>
      <section class="admin-user-detail-section" aria-label="基本信息">
        <h3>基本信息</h3>
        <dl class="admin-user-detail-list">
          <div>
            <dt>用户姓名</dt>
            <dd>{{ user.name }}</dd>
          </div>
          <div>
            <dt>所属部门</dt>
            <dd>{{ user.department || '—' }}</dd>
          </div>
          <div>
            <dt>加入时间</dt>
            <dd>{{ user.createdAt }}</dd>
          </div>
          <div>
            <dt>飞书用户标识</dt>
            <dd class="admin-user-identity">
              <code>{{ user.openId }}</code>
              <el-button
                link
                type="primary"
                aria-label="复制飞书用户标识"
                @click="copyId(user.openId)"
              >
                <AppIcon name="copy" :size="15" />
              </el-button>
            </dd>
          </div>
        </dl>
      </section>
      <section class="admin-user-detail-section" aria-label="角色与状态">
        <h3>角色与状态</h3>
        <dl class="admin-user-detail-list">
          <div>
            <dt>系统角色</dt>
            <dd>
              <el-tag :type="user.roleType === 'ADMIN' ? 'primary' : 'info'" size="small">
                {{ USER_ROLE_LABELS[user.roleType] }}
              </el-tag>
            </dd>
          </div>
          <div>
            <dt>账号状态</dt>
            <dd>
              <el-tag :type="user.status === 'ENABLED' ? 'success' : 'info'" size="small">
                {{ USER_STATUS_LABELS[user.status] }}
              </el-tag>
            </dd>
          </div>
        </dl>
        <p class="admin-user-permissions">
          {{
            user.roleType === 'ADMIN'
              ? '管理商品、审核政策，并管理系统用户。'
              : '提交政策，查看和管理本人的申请。'
          }}
        </p>
      </section>
    </template>
    <template #footer>
      <div class="admin-user-drawer-actions">
        <el-button @click="$emit('update:open', false)">关闭</el-button>
        <el-button
          v-if="user"
          :disabled="Boolean(restriction)"
          :title="restriction"
          @click="$emit('status', user)"
        >
          {{ user?.status === 'ENABLED' ? '停用账号' : '启用账号' }}
        </el-button>
        <el-button
          v-if="user"
          type="primary"
          :disabled="Boolean(restriction)"
          :title="restriction"
          @click="$emit('role', user)"
        >
          调整角色
        </el-button>
      </div>
    </template>
  </el-drawer>
</template>
