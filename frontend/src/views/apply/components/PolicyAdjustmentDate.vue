<script setup>
import { computed, watch } from 'vue'
import { policyTime } from '../../../utils/policy-adapter'

const props = defineProps({
  modelValue: String,
  previousEnd: { type: String, required: true },
  earliestEnd: String,
  canShorten: Boolean,
  canExtend: Boolean,
})
const emit = defineEmits(['update:modelValue', 'validity'])
const before = computed(() => policyTime(props.previousEnd))
const direction = computed(() => (props.modelValue > before.value ? '延长' : '提前结束'))
const error = computed(() => {
  const end = props.modelValue
  if (!end) {
    return ''
  }
  if (end === before.value) {
    return '请选择与原截止不同的时间'
  }
  if (end < before.value) {
    if (!props.canShorten) {
      return '当前不能提前结束这张政策'
    }
    if (props.earliestEnd && end < policyTime(props.earliestEnd)) {
      return '最早可选 ' + policyTime(props.earliestEnd).replace('T', ' ')
    }
  } else {
    if (!props.canExtend) {
      return '当前不能延长这张政策'
    }
    if (Date.parse(end + 'Z') - Date.parse(before.value + 'Z') <= 1000) {
      return '新截止须晚于延长段开始时刻'
    }
    if (Date.parse(end + '+08:00') < Math.floor(Date.now() / 1000) * 1000) {
      return '延期截止不能早于当前北京时间'
    }
  }
  return ''
})
watch(
  () => Boolean(props.modelValue && !error.value),
  (valid) => emit('validity', valid),
  { immediate: true },
)
</script>

<template>
  <el-form-item label="调整后截止时间（北京时间）" required :error="error">
    <el-date-picker
      :model-value="modelValue"
      type="datetime"
      value-format="YYYY-MM-DDTHH:mm:ss"
      format="YYYY-MM-DD HH:mm:ss"
      :default-value="new Date(before)"
      aria-label="调整后截止时间"
      placeholder="选择日期和时间"
      @update:model-value="emit('update:modelValue', $event || '')"
    />
    <span v-if="modelValue && !error" class="adjustment-direction">{{ direction }}</span>
  </el-form-item>
</template>
<style scoped>
.adjustment-direction {
  margin-inline-start: 8px;
  color: var(--el-text-color-secondary);
}
</style>
