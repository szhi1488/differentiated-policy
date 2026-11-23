<script setup>
import './policy-reviews.css'
</script>

<template>
  <section class="policy-reviews-page">
    <el-result
      class="review-panel"
      icon="warning"
      title="无法打开此办理链接"
      sub-title="请核对单据、轮次、节点及当前登录账号。"
    >
      <template #extra>
        <router-link :to="{ name: 'review-inbox' }">返回待我审核</router-link>
      </template>
    </el-result>
  </section>
</template>
