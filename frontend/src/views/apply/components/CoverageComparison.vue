<script setup>
import { subsidyChanges } from '../../../utils/policy-coverage'

defineProps({
  previous: { type: Object, required: true },
  proposed: { type: Object, required: true },
})
</script>

<template>
  <details>
    <summary>与本次补贴对比</summary>
    <p v-for="change in subsidyChanges(previous, proposed)" :key="change">{{ change }}</p>
  </details>
</template>

<style scoped>
summary {
  color: var(--el-color-primary);
  cursor: pointer;
}
p {
  margin: 3px 0 0;
}
</style>
