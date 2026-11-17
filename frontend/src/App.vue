<script setup>
import { computed, defineAsyncComponent, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { authSession } from './composables/useAuthSession'
import { isFeishuClient } from './utils/feishu-sdk'
import { workspaceDestination } from './utils/workspace-route'
import FeishuLoginPage from './views/auth/FeishuLoginPage.vue'
const ApplicantLayout = defineAsyncComponent(() => import('./layouts/ApplicantLayout.vue'))
const AdminLayout = defineAsyncComponent(() => import('./layouts/AdminLayout.vue'))
const router = useRouter()
const { login, status, message, connect, revision } = authSession
const client = isFeishuClient()
const loginPageKey = ref(0)
const routeMatchesUser = computed(
  () => !workspaceDestination(login.value?.user, router.currentRoute.value),
)
watch(login, async (value) => {
  if (value) {
    await router.isReady()
    await router.syncSessionRoute()
  }
})
function reconnectOnReturn(event) {
  if (event.persisted) {
    if (!login.value) {
      loginPageKey.value++
    }
    void connect()
  }
}
function reconnectWhenVisible() {
  if (document.visibilityState === 'visible' && login.value) {
    void connect()
  }
}
onMounted(() => {
  window.addEventListener('pageshow', reconnectOnReturn)
  document.addEventListener('visibilitychange', reconnectWhenVisible)
  void connect()
})
onUnmounted(() => {
  window.removeEventListener('pageshow', reconnectOnReturn)
  document.removeEventListener('visibilitychange', reconnectWhenVisible)
})
</script>
<template>
  <el-config-provider :locale="zhCn">
    <template v-if="status === 'ready' && login">
      <template v-if="routeMatchesUser">
        <AdminLayout :key="revision" v-if="login.user?.roleType === 'ADMIN'" :user="login.user" />
        <ApplicantLayout v-else :key="revision" />
      </template>
    </template>
    <FeishuLoginPage
      v-else
      :key="loginPageKey"
      :client="client"
      :status="status"
      :message="message"
      @authorize="connect(true)"
    />
  </el-config-provider>
</template>
