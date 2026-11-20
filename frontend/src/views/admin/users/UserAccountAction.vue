<script setup>
import { computed, onScopeDispose, ref, watch } from 'vue'
import { readUser, updateUserRole, updateUserStatus } from '../../../api/admin-users'
import { useLatestQuery } from '../../../composables/useLatestQuery'
import { USER_ROLE_LABELS, USER_ROLE_OPTIONS, USER_STATUS_LABELS } from '../../../constants/users'

const props = defineProps({
  id: { type: String, required: true },
  kind: { type: String, required: true },
  targetStatus: { type: String, required: true },
})
const emit = defineEmits(['close', 'saved', 'refresh'])
const { data: user, loading, error, run } = useLatestQuery()
const roleSelection = ref('')
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
      ? roleSelection.value && roleSelection.value !== user.value.roleType
      : props.targetStatus !== user.value.status),
)

function load() {
  return run((signal) => readUser(props.id, signal))
}
// 复核旧值时保留管理员最初选择的目标，避免把停用意图反转为启用。
watch(user, (latest) => {
  if (latest && !roleSelection.value) {
    roleSelection.value = latest.roleType
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
      ? await updateUserRole(user.value, roleSelection.value, controller.signal)
      : await updateUserStatus(user.value, props.targetStatus, controller.signal)
    if (controller.signal.aborted) {
      return
    }
    if (
      updated?.id !== props.id ||
      (roleAction.value
        ? updated.roleType !== roleSelection.value
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
            {{ user.department || '部门未填写' }} · {{ USER_ROLE_LABELS[user.roleType] }} ·
            {{ USER_STATUS_LABELS[user.status] }}
          </span>
        </div>
      </div>
      <template v-if="roleAction">
        <el-radio-group
          v-model="roleSelection"
          :disabled="writing"
          class="admin-user-role-options"
          aria-label="选择系统角色"
        >
          <el-radio
            v-for="option in USER_ROLE_OPTIONS"
            :key="option.value"
            :value="option.value"
            border
          >
            <span>
              <strong>{{ option.label }}</strong>
              <small>{{ option.description }}</small>
            </span>
          </el-radio>
        </el-radio-group>
        <p class="admin-user-action-hint">
          申请人升为管理员时，会释放其负责的平台。已有申请和审核历史继续保留。
        </p>
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
