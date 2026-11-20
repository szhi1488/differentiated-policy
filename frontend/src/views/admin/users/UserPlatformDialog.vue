<script setup>
import { computed, ref, watch, onScopeDispose } from 'vue'
import { readPlatformOwners, updateUserPlatforms } from '../../../api/admin-users'
import { useLatestQuery } from '../../../composables/useLatestQuery'
import { RETAIL_PLATFORMS, BILLION_PLATFORMS, CHANNEL_LABELS } from '../../../constants/policy'

const props = defineProps({ user: { type: Object, required: true } })
const emit = defineEmits(['close', 'saved'])
const groups = { RETAIL: RETAIL_PLATFORMS, BILLION: BILLION_PLATFORMS }
const { data, error, loading, run } = useLatestQuery()
const selected = ref([])
const message = ref('')
const writing = ref(false)
const controller = new AbortController()
onScopeDispose(() => controller.abort())
const original = computed(() =>
  (data.value ?? [])
    .filter((row) => row.userId === props.user.id)
    .map(({ perspective, platformCode }) => ({ perspective, platformCode })),
)
function matches(scope, channel, code) {
  return scope.perspective === channel && scope.platformCode === code
}
function checked(channel, code) {
  return selected.value.some((scope) => matches(scope, channel, code))
}
function unavailable(channel, code) {
  const owner = data.value?.find((scope) => matches(scope, channel, code))
  return owner && owner.userId !== props.user.id ? '由' + owner.name + '负责' : ''
}
function toggle(channel, code, value) {
  if (writing.value || unavailable(channel, code)) {
    return
  }
  selected.value = selected.value.filter((scope) => !matches(scope, channel, code))
  if (value) {
    selected.value.push({ perspective: channel, platformCode: code })
  }
}
const changed = computed(
  () =>
    original.value.length !== selected.value.length ||
    original.value.some((scope) => !checked(scope.perspective, scope.platformCode)),
)
async function load() {
  await run(readPlatformOwners)
  selected.value = [...original.value]
}
watch(() => props.user.id, load, { immediate: true })
async function save() {
  if (writing.value || !data.value || !changed.value) {
    return
  }
  writing.value = true
  message.value = ''
  try {
    await updateUserPlatforms(props.user.id, selected.value, original.value, controller.signal)
    if (!controller.signal.aborted) {
      emit('saved')
    }
  } catch (cause) {
    if (controller.signal.aborted) {
      return
    }
    message.value = cause.message
    await load()
  } finally {
    writing.value = false
  }
}
</script>
<template>
  <el-dialog
    :model-value="true"
    :title="user.name + '的负责平台'"
    width="min(550px, calc(100% - 24px))"
    align-center
    :close-on-click-modal="false"
    :close-on-press-escape="!writing"
    :show-close="!writing"
    @close="!writing && emit('close')"
  >
    <el-alert v-if="message" :title="message" type="warning" :closable="false" />
    <p v-if="loading" role="status">正在读取平台分工…</p>
    <el-alert v-else-if="error" :title="error.message" type="error" :closable="false">
      <el-button link @click="load">重新读取</el-button>
    </el-alert>
    <template v-else-if="data">
      <fieldset v-for="(options, channel) in groups" :key="channel" class="user-platform-group">
        <legend>{{ CHANNEL_LABELS[channel] }}</legend>
        <el-checkbox
          v-for="[code, label] in options"
          :key="code"
          :model-value="checked(channel, code)"
          :disabled="writing || Boolean(unavailable(channel, code))"
          @update:model-value="toggle(channel, code, $event)"
        >
          {{ label }}
          <small v-if="unavailable(channel, code)">（{{ unavailable(channel, code) }}）</small>
        </el-checkbox>
      </fieldset>
    </template>
    <template #footer>
      <el-button :disabled="writing" @click="emit('close')">取消</el-button>
      <el-button type="primary" :disabled="!data || !changed" :loading="writing" @click="save">
        保存分工
      </el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.user-platform-group {
  margin: 12px 0;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  padding: 8px 14px;
}
legend {
  font-weight: 600;
  padding: 0 4px;
}
small {
  white-space: normal;
}
</style>
