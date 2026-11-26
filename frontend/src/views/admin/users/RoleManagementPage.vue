<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import UserManagementHeader from './UserManagementHeader.vue'
import { useLatestQuery } from '../../../composables/useLatestQuery'
import { useReviewerSelectionWrite } from '../../../composables/useReviewerSelectionWrite'
import { readRoles, saveRole } from '../../../api/admin-users'
const { data, error, loading, run } = useLatestQuery()
const { saving, needsReload, error: writeError, run: write, reset } = useReviewerSelectionWrite()
const draft = ref(null)
const load = () => run(readRoles)
void load()
function edit(row) {
  reset()
  draft.value = row
    ? { ...row, permissions: [...row.permissions] }
    : { roleCode: '', roleName: '', enabled: true, permissions: [] }
}
function save() {
  const { id, roleCode, roleName, enabled, permissions, version } = draft.value
  return write(
    (signal) =>
      saveRole(
        id,
        {
          roleCode: roleCode.trim(),
          roleName: roleName.trim(),
          enabled,
          permissions,
          ...(id ? { expectedVersion: version } : {}),
        },
        signal,
      ),
    () => {
      draft.value = null
      ElMessage.success('角色已保存')
      void load()
    },
  )
}
function closeEditor() {
  draft.value = null
  void load()
}
</script>
<template>
  <section class="role-settings" aria-labelledby="admin-users-title">
    <UserManagementHeader>
      <el-button type="primary" :disabled="!data" @click="edit()">创建角色</el-button>
    </UserManagementHeader>
    <section class="role-panel" v-loading="loading">
      <el-alert v-if="error" :title="error.message" type="error" :closable="false">
        <el-button link @click="load">重试</el-button>
      </el-alert>
      <el-table v-else :data="data?.roles ?? []" row-key="id" empty-text="暂无角色">
        <el-table-column prop="roleName" label="角色名称" />
        <el-table-column prop="roleCode" label="角色编码" />
        <el-table-column label="状态">
          <template #default="{ row }">{{ row.enabled ? '启用' : '停用' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button link type="primary" @click="edit(row)">
              {{ row.roleCode === 'ADMIN' ? '查看权限' : '编辑' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
    <el-dialog
      :model-value="!!draft"
      :title="draft?.id ? '角色权限' : '创建角色'"
      width="min(640px,95%)"
      :close-on-click-modal="false"
      :show-close="!saving"
      :close-on-press-escape="!saving"
      @update:model-value="!$event && !saving && closeEditor()"
    >
      <el-form
        v-if="draft"
        label-position="top"
        :disabled="saving || (draft.roleCode === 'ADMIN' && !!draft.id)"
      >
        <el-form-item label="角色名称">
          <el-input v-model="draft.roleName" maxlength="64" />
        </el-form-item>
        <el-form-item label="角色编码">
          <el-input v-model="draft.roleCode" :disabled="!!draft.id" maxlength="64" />
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="draft.enabled" /></el-form-item>
        <el-form-item label="可用权限">
          <el-checkbox-group v-model="draft.permissions">
            <el-checkbox v-for="p in data?.permissions" :key="p.code" :value="p.code">
              {{ p.name }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <el-alert v-if="writeError" :title="writeError" type="error" :closable="false" />
      <template #footer>
        <el-button :disabled="saving" @click="closeEditor">关闭</el-button>
        <el-button
          v-if="draft && !(draft.id && draft.roleCode === 'ADMIN')"
          type="primary"
          :loading="saving"
          :disabled="needsReload || !draft.roleCode.trim() || !draft.roleName.trim()"
          @click="save"
        >
          保存
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>
<style scoped>
.role-settings {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 16px;
}
.role-panel {
  min-width: 0;
  background: white;
  border: 1px solid #dfe7f3;
  padding: 18px;
  border-radius: 10px;
}
</style>
