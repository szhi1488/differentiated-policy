<script setup>
import { computed, onMounted, ref } from 'vue'
import { getFeishuConfig } from './api/feishu'
import { initializeFeishuSdk, isFeishuClient } from './utils/feishu-sdk'

const status = ref('loading')
const message = ref('正在读取应用配置…')
const appId = ref('')
const configured = ref(false)
const inFeishu = isFeishuClient()
const statusLabel = computed(() => ({
  loading: '正在连接', ready: '已就绪', browser: '等待飞书客户端', error: '连接未完成',
})[status.value])

async function connect() {
  status.value = 'loading'
  message.value = '正在读取应用配置…'
  try {
    const config = await getFeishuConfig()
    appId.value = config.appId
    configured.value = config.sdkEnabled
    if (!config.sdkEnabled) throw new Error('后端飞书应用凭证尚未配置，请完成本地配置后重启服务。')
    if (!inFeishu) {
      status.value = 'browser'
      message.value = '本地应用服务已连接。请在飞书客户端中打开网页，继续验证 JS-SDK。'
      return
    }
    message.value = '正在初始化飞书 JS-SDK…'
    await initializeFeishuSdk()
    status.value = 'ready'
    message.value = '飞书 JS-SDK 已就绪，可以继续接入网页应用能力。'
  } catch (error) {
    status.value = 'error'
    message.value = error.message || '初始化失败，请重试。'
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
      <p class="eyebrow">飞书网页应用 · 接入准备</p>
      <h1>应用连接</h1>
      <p class="intro">先完成应用与飞书的连接，再逐步接入政策申请与审核。</p>
      <section class="connection-card" aria-labelledby="connection-title">
        <div class="card-heading">
          <h2 id="connection-title">初始化状态</h2>
          <span class="status-badge" :class="status"><i aria-hidden="true"></i>{{ statusLabel }}</span>
        </div>
        <p class="status-message" role="status" aria-live="polite">{{ message }}</p>
        <dl class="details">
          <div><dt>应用标识</dt><dd class="app-id">{{ appId || '读取中' }}</dd></div>
          <div><dt>运行环境</dt><dd>{{ inFeishu ? '飞书客户端' : '普通浏览器' }}</dd></div>
          <div><dt>后端凭证配置</dt><dd>{{ configured ? '已加载' : '未确认' }}</dd></div>
          <div><dt>用户登录</dt><dd>待接入</dd></div>
        </dl>
        <button type="button" :disabled="status === 'loading'" @click="connect">
          {{ status === 'loading' ? '连接中…' : '重新检查连接' }}
        </button>
      </section>
      <aside class="next-step">
        <span class="step-number" aria-hidden="true">下一步</span>
        <div><h2>在飞书中完成验证</h2><p>在飞书开发者后台配置本机主页与 H5 可信域名，再从当前电脑的飞书打开应用。当前页面仅用于验证接入状态。</p></div>
      </aside>
    </main>
    <footer>差异化政策管理 · 零售 / 百亿</footer>
  </div>
</template>
