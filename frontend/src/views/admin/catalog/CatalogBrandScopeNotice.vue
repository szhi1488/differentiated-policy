<script setup>
defineProps({
  error: Error,
  unassigned: Boolean,
  emptyMessage: {
    type: String,
    default: '尚未分配负责品牌，请联系管理员在政策审核设置中指定品牌采购。',
  },
})
defineEmits(['retry'])
</script>

<template>
  <el-alert
    v-if="error || unassigned"
    :type="error ? 'error' : 'info'"
    :title="error?.message || emptyMessage"
    :closable="false"
    show-icon
  >
    <el-button link type="primary" @click="$emit('retry')">刷新负责品牌</el-button>
  </el-alert>
</template>
