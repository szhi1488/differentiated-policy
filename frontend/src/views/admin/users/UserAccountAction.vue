<script setup>
import { computed, onScopeDispose, ref, watch } from 'vue'
import { readUser, updateUserRoles, updateUserStatus } from '../../../api/admin-users'
import { useLatestQuery } from '../../../composables/useLatestQuery'
import { roleLabels, USER_STATUS_LABELS } from '../../../constants/users'

const props = defineProps({
  roles: { type: Array, required: true },
  id: { type: String, required: true },
  kind: { type: String, required: true },
  targetStatus: { type: String, required: true },
})
const emit = defineEmits(['close', 'saved', 'refresh'])
const { data: user, loading, error, run } = useLatestQuery()
const roleSelection = ref(null)
const writing = ref(false)
const writeError = ref('')
const controller = new AbortController()
onScopeDispose(() => controller.abort())
const roleAction = computed(() => props.kind === 'role')
const title = computed(() =>
  roleAction.value ? '调整用户角色' : props.targetStatus === 'DISABLED' ? '停用账号' : '启用账号',
)
const canConfirm = computed(
  () =>
    user.value &&
    !writing.value &&
    (roleAction.value
      ? roleSelection.value && !sameRoles(roleSelection.value, user.value.roleCodes)
      : props.targetStatus !== user.value.status),
)

function sameRoles(a, b) {
  return JSON.stringify([...(a ?? [])].sort()) === JSON.stringify([...(b ?? [])].sort())
}
function load() {
  return run((signal) => readUser(props.id, signal))
}
// 复核旧值时保留管理员最初选择的目标，避免把停用意图反转为启用。
watch(user, (latest) => {
  if (latest && !roleSelection.value) {
    roleSelection.value = [...latest.roleCodes]
  }
})
void load()

async function confirm() {
  if (!canConfirm.value) {
    return
  }
  writing.value = true
  writeError.value = ''
  try {
    const updated = roleAction.value
      ? await updateUserRoles(user.value, roleSelection.value, controller.signal)
      : await updateUserStatus(user.value, props.targetStatus, controller.signal)
    if (controller.signal.aborted) {
      return
    }
    if (
      updated?.id !== props.id ||
      (roleAction.value
        ? !sameRoles(updated.roleCodes, roleSelection.value)
        : updated.status !== props.targetStatus)
    ) {
      throw new Error('服务端未返回预期的账号状态。')
    }
    emit(
      'saved',
      roleAction.value
        ? '用户角色已更新'
        : props.targetStatus === 'DISABLED'
          ? '账号已停用'
          : '账号已启用',
    )
  } catch (cause) {
    if (controller.signal.aborted) {
      return
    }
    const uncertain =
      !cause.status ||
      cause.status >= 500 ||
      cause.kind === 'parse' ||
      cause.kind === 'timeout' ||
      cause.kind === 'network'
    writeError.value = uncertain ? `写入结果待核对。${cause.message}` : cause.message
    if (uncertain || cause.status === 409 || cause.status === 403 || cause.status === 404) {
      emit('refresh')
      await load()
    }
  } finally {
    writing.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="true"
    :title="title"
    class="admin-user-action-dialog"
    width="min(460px, calc(100% - 24px))"
    align-center
    :close-on-click-modal="false"
    :close-on-press-escape="!writing"
    :show-close="!writing"
    @update:model-value="!writing && $emit('close')"
  >
    <el-alert v-if="writeError" :title="writeError" type="warning" :closable="false" show-icon />
    <p v-if="loading" role="status">正在读取账号详情…</p>
    <el-alert v-else-if="error" :title="error.message" type="error" :closable="false" show-icon>
      <el-button link @click="load">重新读取详情</el-button>
    </el-alert>
    <template v-else-if="user">
      <div class="admin-user-action-account">
        <el-avatar :size="40" :src="user.avatarUrl || undefined">
          {{ user.name.slice(0, 1) }}
        </el-avatar>
        <div>
          <strong>{{ user.name }}</strong>
          <span>
            {{ user.department || '部门未填写' }} · {{ roleLabels(user.roleCodes, roles) }} ·
            {{ USER_STATUS_LABELS[user.status] }}
          </span>
        </div>
      </div>
      <template v-if="roleAction">
        <el-select
          v-model="roleSelection"
          multiple
          aria-label="选择系统角色"
          :disabled="writing"
          placeholder="选择该账号担任的岗位"
        >
          <el-option
            v-for="role in roles"
            :key="role.id"
            :value="role.roleCode"
            :label="role.roleName"
            :disabled="!role.enabled"
          />
        </el-select>
      </template>
      <el-alert
        v-else
        :type="
          targetStatus === user.status ? 'info' : targetStatus === 'DISABLED' ? 'warning' : 'info'
        "
        :closable="false"
        show-icon
        :title="
          targetStatus === user.status
            ? '账号已处于目标状态。'
            : targetStatus === 'DISABLED'
              ? '停用后会释放该用户负责的平台，并停止其系统访问权限。'
              : '启用后，该用户将恢复原角色的使用资格。'
        "
        description="已有申请、审核及商品操作记录继续保留。"
      />
    </template>
    <template #footer>
      <el-button :disabled="writing" @click="$emit('close')">取消</el-button>
      <el-button
        :type="!roleAction && targetStatus === 'DISABLED' ? 'danger' : 'primary'"
        :disabled="!canConfirm"
        :loading="writing"
        @click="confirm"
      >
        {{ roleAction ? '确认调整' : targetStatus === 'DISABLED' ? '确认停用' : '确认启用' }}
      </el-button>
    </template>
  </el-dialog>
</template>
