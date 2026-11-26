<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import AppIcon from '../../../components/AppIcon.vue'
import UserManagementHeader from './UserManagementHeader.vue'
import { authSession } from '../../../composables/useAuthSession'
import { useUserManagement } from '../../../composables/useUserManagement'
import { roleLabels, USER_STATUS_LABELS } from '../../../constants/users'
import UserAccountAction from './UserAccountAction.vue'
import { readRoles } from '../../../api/admin-users'
import { useLatestQuery } from '../../../composables/useLatestQuery'
import UserDetailDrawer from './UserDetailDrawer.vue'
import './admin-users.css'

const rolesQuery = useLatestQuery()
void rolesQuery.run(readRoles)
const roles = computed(() => rolesQuery.data.value?.roles ?? [])

const {
  filters,
  page,
  pageSize,
  usersPage,
  listBusy,
  listError,
  counts,
  statsBusy,
  statsError,
  detailId,
  detailOpen,
  selectedUser,
  detailBusy,
  detailError,
  loadList,
  loadStats,
  loadDetail,
  search,
  reset,
  refresh,
} = useUserManagement()
const metrics = [
  { key: 'total', label: '用户总数', icon: 'users', tone: 'blue' },
  { key: 'enabledUsers', label: '启用用户', icon: 'document', tone: 'cyan' },
  { key: 'enabledAdmins', label: '启用管理员', icon: 'shield', tone: 'violet' },
  { key: 'disabledUsers', label: '已停用用户', icon: 'stop', tone: 'gray' },
]
const currentId = computed(() => authSession.user.value?.id)
const action = ref(null)

function restriction(user) {
  return user?.id === currentId.value ? '不能调整本人角色或停用本人账号' : ''
}
function showAction(user, kind) {
  if (!user || restriction(user) || action.value) {
    return
  }
  action.value = {
    id: user.id,
    kind,
    targetStatus: user.status === 'ENABLED' ? 'DISABLED' : 'ENABLED',
  }
}

function saved(message) {
  action.value = null
  ElMessage.success(message)
  void refresh()
}
</script>

<template>
  <section class="admin-users" aria-labelledby="admin-users-title">
    <UserManagementHeader>
      <div class="admin-users-heading-actions">
        <el-button :loading="listBusy || statsBusy || detailBusy" @click="refresh">
          <AppIcon name="sync" :size="15" />
          刷新
        </el-button>
      </div>
    </UserManagementHeader>

    <el-alert
      v-if="rolesQuery.error.value"
      :title="rolesQuery.error.value.message"
      type="error"
      :closable="false"
    >
      <el-button link @click="rolesQuery.run(readRoles)">重新读取岗位</el-button>
    </el-alert>
    <div class="admin-user-metrics" aria-label="用户统计" :aria-busy="statsBusy">
      <article
        v-for="metric in metrics"
        :key="metric.label"
        class="admin-user-metric"
        :data-tone="metric.tone"
      >
        <div>
          <span>{{ metric.label }}</span>
          <strong>
            {{ counts?.[metric.key] ?? '—' }}
            <small>人</small>
          </strong>
        </div>
        <span class="admin-user-metric-icon"><AppIcon :name="metric.icon" :size="23" /></span>
      </article>
    </div>

    <el-alert
      v-if="statsError"
      :title="statsError.message"
      type="error"
      :closable="false"
      show-icon
    >
      <el-button link @click="loadStats">重新读取统计</el-button>
    </el-alert>

    <section class="admin-user-surface" aria-label="用户列表">
      <div class="admin-user-list-heading">
        <h2>用户列表</h2>
      </div>
      <div class="admin-user-list-content">
        <form class="admin-user-filters" aria-label="筛选用户" @submit.prevent="search">
          <div class="admin-user-filter admin-user-keyword">
            <label for="admin-user-keyword">关键词</label>
            <el-input
              id="admin-user-keyword"
              v-model="filters.keyword"
              placeholder="搜索姓名或部门"
              clearable
              maxlength="128"
            >
              <template #prefix><AppIcon name="search" :size="15" /></template>
            </el-input>
          </div>
          <div class="admin-user-filter">
            <span id="admin-user-role-label">系统角色</span>
            <el-select
              v-model="filters.roleCode"
              aria-label="系统角色"
              aria-labelledby="admin-user-role-label"
              placeholder="全部角色"
              clearable
            >
              <el-option
                v-for="role in roles"
                :key="role.roleCode"
                :label="role.roleName"
                :value="role.roleCode"
              />
            </el-select>
          </div>
          <div class="admin-user-filter">
            <span id="admin-user-status-label">账号状态</span>
            <el-select
              v-model="filters.status"
              aria-label="账号状态"
              aria-labelledby="admin-user-status-label"
              placeholder="全部状态"
              clearable
            >
              <el-option
                v-for="(label, value) in USER_STATUS_LABELS"
                :key="value"
                :label="label"
                :value="value"
              />
            </el-select>
          </div>
          <div class="admin-user-filter-actions">
            <el-button type="primary" native-type="submit">
              <AppIcon name="search" :size="15" />
              查询
            </el-button>
            <el-button @click="reset">重置</el-button>
          </div>
        </form>

        <el-table
          :data="usersPage?.items ?? []"
          row-key="id"
          class="admin-user-table"
          aria-label="用户列表"
          :aria-busy="listBusy"
        >
          <el-table-column label="用户" min-width="190">
            <template #default="{ row }">
              <div class="admin-user-cell">
                <el-avatar :size="36" :src="row.avatarUrl || undefined">
                  {{ row.name.slice(0, 1) }}
                </el-avatar>
                <div>
                  <strong>{{ row.name }}</strong>
                  <span v-if="row.id === currentId">当前账号</span>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="所属部门" min-width="124">
            <template #default="{ row }">{{ row.department || '—' }}</template>
          </el-table-column>
          <el-table-column label="系统角色" min-width="98">
            <template #default="{ row }">
              {{ roleLabels(row.roleCodes, roles) }}
            </template>
          </el-table-column>
          <el-table-column label="账号状态" min-width="98">
            <template #default="{ row }">
              <el-tag
                :type="row.status === 'ENABLED' ? 'success' : 'info'"
                size="small"
                effect="light"
              >
                {{ USER_STATUS_LABELS[row.status] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="加入时间" min-width="170" />
          <el-table-column label="操作" width="238" fixed="right" :resizable="false">
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                :aria-label="`查看用户 ${row.name}`"
                @click="detailId = row.id"
              >
                查看
              </el-button>
              <el-button
                link
                type="primary"
                :disabled="Boolean(restriction(row))"
                :title="restriction(row)"
                :aria-label="`调整角色 ${row.name}`"
                @click="showAction(row, 'role')"
              >
                调整角色
              </el-button>
              <el-button
                link
                :type="row.status === 'ENABLED' ? 'danger' : 'primary'"
                :disabled="Boolean(restriction(row))"
                :title="restriction(row)"
                :aria-label="`${row.status === 'ENABLED' ? '停用' : '启用'}用户 ${row.name}`"
                @click="showAction(row, 'status')"
              >
                {{ row.status === 'ENABLED' ? '停用' : '启用' }}
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <p v-if="listBusy" role="status">正在读取用户列表…</p>
            <el-alert
              v-else-if="listError"
              :title="listError.message"
              type="error"
              :closable="false"
              show-icon
            >
              <el-button link @click="loadList">重新读取列表</el-button>
            </el-alert>
            <el-empty v-else :image-size="66" description="没有符合条件的用户">
              <el-button @click="reset">重置筛选</el-button>
            </el-empty>
          </template>
        </el-table>
        <footer v-if="usersPage" class="admin-user-footer">
          <span aria-live="polite">共 {{ usersPage.total }} 条结果</span>
          <el-pagination
            v-if="usersPage.total"
            v-model:current-page="page"
            v-model:page-size="pageSize"
            :total="usersPage.total"
            :page-sizes="[10, 20, 50]"
            :pager-count="5"
            layout="prev, pager, next, sizes"
            size="small"
            background
          />
        </footer>
      </div>
    </section>

    <UserDetailDrawer
      v-model:open="detailOpen"
      :user="selectedUser"
      :roles="roles"
      :current-id="currentId"
      :restriction="restriction(selectedUser)"
      :loading="detailBusy"
      :error="detailError"
      @retry="loadDetail"
      @role="showAction($event, 'role')"
      @status="showAction($event, 'status')"
    />
    <UserAccountAction
      v-if="action"
      v-bind="action"
      :roles="roles"
      @close="action = null"
      @saved="saved"
      @refresh="refresh"
    />
  </section>
</template>
