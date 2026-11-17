<script setup>
defineProps({
  row: { type: Object, required: true },
  policyId: { type: String, required: true },
})
defineEmits(['show-reference'])
</script>

<template>
  <span v-if="row.comparisonUniform === false">展开逐项查看</span>
  <div v-else-if="row.winner && row.winner.id !== policyId" class="coverage-reference-actions">
    <el-button
      link
      type="primary"
      :aria-label="`查看对照政策 ${row.winner.policyCode}`"
      @click="$emit('show-reference', { ...row, showWhole: true })"
    >
      {{ row.winner.policyCode }}
    </el-button>
    <el-button
      link
      type="primary"
      :aria-label="`对比 ${row.name} ${row.goodsId} ${row.platformCode} 补贴`"
      @click="$emit('show-reference', { ...row, showWhole: false })"
    >
      对比补贴
    </el-button>
  </div>
  <span v-else>{{ row.winner ? '本申请生效' : '无对照政策' }}</span>
</template>

<style scoped>
.coverage-reference-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.coverage-reference-actions .el-button {
  margin: 0;
}
</style>
