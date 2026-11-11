<script setup>
import { defineAsyncComponent, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { getFeishuConfig, getLoginSession } from './api/feishu'
import { isFeishuClient } from './utils/feishu-sdk'
import { loginWithFeishu } from './utils/feishu-auth'
import { completeBrowserLogin, startBrowserLogin } from './utils/feishu-browser-auth'
const ApplicantWorkspace = defineAsyncComponent(
  () => import('./views/apply/ApplicantWorkspace.vue'),
)
const AdminWorkspace = defineAsyncComponent(() => import('./views/admin/AdminWorkspace.vue'))
const router = useRouter()
const status = ref('loading')
const message = ref('正在通过飞书验证身份…')
const login = ref(null)
const inFeishu = isFeishuClient()
async function connect(startAuthorization = false) {
  status.value = 'loading'
  message.value = '正在通过飞书验证身份…'
  try {
    login.value = (await completeBrowserLogin()) || (await getLoginSession())
    if (login.value) {
      await router.isReady()
      await router.setSessionUser(login.value.user)
      status.value = 'ready'
      return
    }
    const config = await getFeishuConfig()
    if (!config.sdkEnabled) {
      throw new Error('后端飞书应用凭证尚未配置，请完成本地配置后重启服务。')
    }
    if (!inFeishu) {
      if (startAuthorization) {
        message.value = '正在前往飞书授权页面…'
        await startBrowserLogin()
        return
      }
      status.value = 'browser'
      message.value = '点击下方按钮，通过飞书网页授权登录。'
      return
    }
    login.value = await loginWithFeishu(config.appId)
    await router.isReady()
    await router.setSessionUser(login.value.user)
    status.value = 'ready'
  } catch (error) {
    status.value = 'error'
    login.value = null
    message.value = error.message || '登录失败，请重试。'
  }
}
onMounted(() => connect())
</script>
<template>
  <el-config-provider :locale="zhCn">
    <template v-if="status === 'ready' && login">
      <AdminWorkspace v-if="login.user?.roleType === 'ADMIN'" :user="login.user" />
      <ApplicantWorkspace v-else />
    </template>
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
        <el-button type="primary" :loading="status === 'loading'" @click="connect(true)">
          {{ status === 'loading' ? '正在登录…' : '通过飞书登录' }}
        </el-button>
      </section>
    </main>
  </el-config-provider>
</template>
