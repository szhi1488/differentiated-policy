<script setup>
import { computed } from 'vue'
import AppIcon from '../../components/AppIcon.vue'
import { useFeishuQrLogin } from '../../composables/useFeishuQrLogin'
import loginArtwork from './login-artwork.webp'

const props = defineProps({
  client: Boolean,
  status: { type: String, required: true },
  message: { type: String, default: '' },
})
const emit = defineEmits(['authorize'])

const { host, status: qrStatus, message: qrMessage, requesting, show, reset } = useFeishuQrLogin()
const busy = computed(
  () => ['loading', 'redirecting'].includes(props.status) || qrStatus.value === 'redirecting',
)
const qrAction = computed(() => (qrStatus.value === 'idle' ? '显示飞书二维码' : '刷新二维码'))

function authorize() {
  reset()
  emit('authorize')
}
</script>

<template>
  <main class="login-shell">
    <header class="login-header workspace-brand">
      <span class="logo-grid" aria-hidden="true">
        <i></i>
        <i></i>
        <i></i>
        <i></i>
      </span>
      <strong>电商运营平台</strong>
    </header>

    <div class="login-layout">
      <picture class="login-artwork" aria-hidden="true">
        <source media="(min-width: 900px)" :srcset="loginArtwork" />
        <img alt="" width="720" height="540" />
      </picture>

      <section class="login-card" aria-labelledby="login-title">
        <div class="login-icon"><AppIcon name="shield" :size="27" /></div>
        <h1 id="login-title">差异化政策管理</h1>
        <p class="login-description">
          {{ client ? '使用当前飞书账号进入工作台' : '使用企业飞书账号登录工作台' }}
        </p>

        <div class="login-actions">
          <p
            v-if="message && qrStatus === 'idle'"
            class="login-status"
            :class="{ 'login-status--error': status === 'error' }"
            role="status"
          >
            {{ message }}
          </p>

          <template v-if="!client && !['loading', 'redirecting'].includes(status)">
            <div v-if="qrStatus !== 'idle'" class="login-qr">
              <div
                v-show="qrStatus === 'loading' || qrStatus === 'waiting'"
                id="feishu-qr-container"
                ref="host"
                class="login-qr-frame"
              ></div>
              <p class="login-status" role="status">{{ qrMessage }}</p>
            </div>
            <el-button
              type="primary"
              :loading="qrStatus === 'loading'"
              :disabled="busy"
              @click="show"
            >
              {{ qrAction }}
            </el-button>
            <el-button :disabled="busy || requesting" @click="authorize">
              飞书网页授权登录
            </el-button>
          </template>
          <el-button v-else type="primary" :loading="busy" @click="authorize">
            {{ busy ? '请稍候' : '重新验证飞书身份' }}
          </el-button>
        </div>

        <p class="login-help">登录遇到问题？请联系应用管理员</p>
      </section>
    </div>
  </main>
</template>

<style scoped>
.login-shell {
  min-height: 100vh;
  min-height: 100dvh;
  display: grid;
  grid-template-rows: auto 1fr;
  padding: 36px clamp(24px, 6vw, 96px) 56px;
  background:
    radial-gradient(ellipse at 20% 64%, #c6d9ff 0%, transparent 52%),
    radial-gradient(ellipse at 88% 12%, #dce6ff 0%, transparent 44%),
    linear-gradient(150deg, #eaf0ff, #f5f8ff 76%);
}
.login-header {
  padding: 0;
  align-self: start;
  gap: 12px;
}
.login-header strong {
  font-size: 17px;
}
.login-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(330px, 1fr);
  align-items: center;
  gap: clamp(28px, 5vw, 78px);
  width: min(1160px, 100%);
  margin: 28px auto 0;
}
.login-artwork img {
  display: block;
  width: 100%;
  height: auto;
}
.login-card {
  padding: 40px clamp(22px, 3.5vw, 48px) 32px;
  border: 1px solid #fff;
  border-radius: 24px;
  background: #fffffffa;
  box-shadow:
    0 20px 64px #244f9c16,
    0 2px 10px #244f9c0b;
  text-align: center;
}
.login-icon {
  display: grid;
  place-items: center;
  width: 56px;
  height: 56px;
  margin: 0 auto 20px;
  border-radius: 17px;
  background: linear-gradient(145deg, #518fff, #1c61ed);
  box-shadow: 0 8px 16px #2c6ae137;
  color: #fff;
}
.login-card h1 {
  color: #142745;
  font-size: clamp(24px, 2.6vw, 32px);
  line-height: 1.35;
}
.login-description {
  margin-top: 10px;
  color: #52647f;
  font-size: 14px;
}
.login-actions {
  display: grid;
  gap: 12px;
  margin: 28px 0 0;
}
.login-actions .el-button {
  width: 100%;
  min-height: 46px;
  margin: 0;
  border-radius: 9px;
  font-size: 14px;
  font-weight: 600;
}
.login-actions .el-button--primary {
  --el-button-bg-color: #2467ed;
  --el-button-border-color: #2467ed;
  --el-button-hover-bg-color: #1b56cd;
  --el-button-hover-border-color: #1b56cd;
}
.login-status {
  padding: 10px 12px;
  border-radius: 8px;
  background: #f2f6ff;
  color: #425c80;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.login-status--error {
  background: #fff2ee;
  color: #9b3c27;
}
.login-qr-frame {
  display: grid;
  justify-content: center;
}
.login-qr-frame :deep(iframe) {
  display: block;
  border: 0;
}
.login-help {
  margin-top: 24px;
  color: #586c87;
  font-size: 12px;
  line-height: 1.6;
}
@media (max-width: 899px) {
  .login-layout {
    grid-template-columns: minmax(0, 1fr);
    width: min(460px, 100%);
  }
  .login-artwork {
    display: none;
  }
}
@media (max-width: 480px) {
  .login-shell {
    padding: 24px 12px 32px;
  }
  .login-header {
    margin-inline: 8px;
  }
  .login-card {
    padding: 28px 14px 24px;
    border-radius: 20px;
  }
  .login-actions {
    margin-top: 22px;
  }
}
</style>
