<script setup>
import { onMounted, ref } from 'vue'
import { getFeishuConfig } from './api/feishu'
import { isFeishuClient } from './utils/feishu-sdk'
import { loginWithFeishu } from './utils/feishu-auth'
import ApplicantWorkspace from './views/apply/ApplicantWorkspace.vue'
const status = ref('loading')
const message = ref('正在通过飞书验证身份…')
const login = ref(null)
const inFeishu = isFeishuClient()
async function connect() {
  status.value = 'loading'
  try {
    const config = await getFeishuConfig()
    if (!config.sdkEnabled) {
      throw new Error('后端飞书应用凭证尚未配置，请完成本地配置后重启服务。')
    }
    if (!inFeishu) {
      status.value = 'browser'
      message.value = '请在飞书客户端中打开应用，使用当前飞书账号登录。'
      return
    }
    login.value = await loginWithFeishu(config.appId)
    status.value = 'ready'
  } catch (error) {
    status.value = 'error'
    login.value = null
    message.value = error.message || '登录失败，请重试。'
  }
}
onMounted(connect)
</script>
<template>
  <ApplicantWorkspace v-if="status === 'ready' && login" />
  <main v-else class="login-shell">
    <section class="login-card">
      <div class="workspace-brand">
        <span class="logo-grid">
          <i></i>
          <i></i>
          <i></i>
          <i></i>
        </span>
        <strong>电商运营平台</strong>
      </div>
      <h1>差异化政策管理</h1>
      <p role="status" aria-live="polite">{{ message }}</p>
      <button type="button" class="primary" :disabled="status === 'loading'" @click="connect">
        {{ status === 'loading' ? '正在登录…' : '通过飞书登录' }}
      </button>
    </section>
  </main>
</template>
