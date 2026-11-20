<script setup>
import { subsidyText, subsidyChanges } from '../../../utils/policy-coverage'
defineProps({ row: { type: Object, required: true } })
</script>

<template>
  <span v-if="row.comparisonUniform === false">展开逐项查看</span>
  <template v-else-if="row.winner">
    <div>{{ row.winner.policyCode }}</div>
    <div>{{ subsidyText(row.existingSubsidy) }}</div>
    <details v-if="row.existingSubsidy && row.proposedSubsidy">
      <summary>对比补贴</summary>
      <p v-for="change in subsidyChanges(row.existingSubsidy, row.proposedSubsidy)" :key="change">
        {{ change }}
      </p>
    </details>
  </template>
  <span v-else>本人暂无可对照政策</span>
</template>

<style scoped>
summary {
  color: var(--el-color-primary);
  cursor: pointer;
}
</style>
