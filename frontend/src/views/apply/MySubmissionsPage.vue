<script setup>
import AppIcon from '../../components/AppIcon.vue'
</script>
<template>
  <section class="submissions-panel">
    <h2>
      <span></span>
      我的提交
    </h2>
    <el-empty description="政策提交与记录查询功能尚未接入" :image-size="110">
      <el-button type="primary" @click="$router.push({ name: 'apply' })">
        前往提交政策
        <AppIcon name="arrow" :size="16" />
      </el-button>
    </el-empty>
  </section>
</template>

<style scoped>
.submissions-panel {
  padding: 24px;
  min-height: 480px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  background: #fff;
  overflow: hidden;
}
.submissions-panel h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  margin-bottom: 22px;
}
.submissions-panel h2 > span {
  width: 3px;
  height: 16px;
  border-radius: 2px;
  background: var(--el-color-primary);
}
</style>
