<script setup>
import { authSession } from '../../composables/useAuthSession'
import { reviewReturnLocation } from '../../utils/workspace-route'
import './policy-reviews.css'
</script>

<template>
  <section class="policy-reviews-page">
    <el-result
      class="review-panel"
      icon="warning"
      title="无法打开此政策链接"
      sub-title="请核对单据、轮次及当前登录账号。"
    >
      <template #extra>
        <router-link :to="reviewReturnLocation(authSession.user.value)">返回工作台</router-link>
      </template>
    </el-result>
  </section>
</template>
