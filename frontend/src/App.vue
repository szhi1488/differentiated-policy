<script setup>
import { computed, onMounted, ref } from 'vue'
import { getFeishuConfig, logout } from './api/feishu'
import { isFeishuClient } from './utils/feishu-sdk'
import { loginWithFeishu } from './utils/feishu-auth'

const status = ref('loading')
const message = ref('正在读取应用配置…')
const appId = ref('')
const configured = ref(false)
const login = ref(null)
const inFeishu = isFeishuClient()
const statusLabel = computed(() => ({
  loading: '正在登录', ready: '已登录', browser: '等待飞书客户端', error: '登录未完成', signedOut: '已退出',
})[status.value])

async function connect() {
  if (status.value === 'loading' && appId.value) return
  status.value = 'loading'
  message.value = '正在读取应用配置…'
  try {
    const config = await getFeishuConfig()
    appId.value = config.appId
    configured.value = config.sdkEnabled
    if (!config.sdkEnabled) throw new Error('后端飞书应用凭证尚未配置，请完成本地配置后重启服务。')
    if (!inFeishu) {
      status.value = 'browser'
      message.value = '请在飞书客户端中打开应用，使用当前飞书账号登录。'
      return
    }
    message.value = '正在通过飞书验证身份，请按客户端提示完成授权…'
    login.value = await loginWithFeishu(config.appId)
    status.value = 'ready'
    message.value = `你好，${login.value.user.name}。已通过飞书身份验证。`
  } catch (error) {
    status.value = 'error'
    login.value = null
    message.value = error.message || '初始化失败，请重试。'
  }
}

async function signOut() {
  status.value = 'loading'
  try {
    await logout()
    login.value = null
    status.value = 'signedOut'
    message.value = '已退出当前应用，点击下方按钮可重新通过飞书登录。'
  } catch (error) {
    status.value = 'error'
    message.value = error.message || '退出失败，请重试。'
  }
}

onMounted(connect)
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <a class="brand" href="/" aria-label="差异化政策管理首页">
        <span class="brand-mark" aria-hidden="true">差</span>
        <span>差异化政策管理</span>
      </a>
      <span class="dev-tag">本地开发</span>
    </header>
    <main>
      <p class="eyebrow">飞书网页应用</p>
      <h1>飞书账号登录</h1>
      <p class="intro">使用当前飞书账号，进入差异化政策管理。</p>
      <section class="connection-card" aria-labelledby="connection-title">
        <div class="card-heading">
          <h2 id="connection-title">登录状态</h2>
          <span class="status-badge" :class="status"><i aria-hidden="true"></i>{{ statusLabel }}</span>
        </div>
        <p class="status-message" role="status" aria-live="polite">{{ message }}</p>
        <dl class="details">
          <div><dt>应用标识</dt><dd class="app-id">{{ appId || '读取中' }}</dd></div>
          <div><dt>运行环境</dt><dd>{{ inFeishu ? '飞书客户端' : '普通浏览器' }}</dd></div>
          <div><dt>后端凭证配置</dt><dd>{{ configured ? '已加载' : '未确认' }}</dd></div>
          <div><dt>当前用户</dt><dd>{{ login?.user.name || '尚未登录' }}</dd></div>
          <div v-if="login"><dt>会话最晚到期</dt><dd>{{ new Date(login.expiresAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) }}</dd></div>
        </dl>
        <button v-if="!login" type="button" :disabled="status === 'loading'" @click="connect">
          {{ status === 'loading' ? '登录中…' : '通过飞书登录' }}
        </button>
        <button v-else type="button" :disabled="status === 'loading'" @click="signOut">退出当前应用</button>
      </section>
      <aside class="next-step">
        <span class="step-number" aria-hidden="true">账号授权</span>
        <div><h2>使用飞书身份登录</h2><p>首次使用请按飞书提示授权。登录仅获取基本身份；政策申请与审核功能正在接入。</p></div>
      </aside>
    </main>
    <footer>差异化政策管理 · 零售 / 百亿</footer>
  </div>
</template>
