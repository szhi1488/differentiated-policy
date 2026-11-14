<script setup>
import { computed, onScopeDispose, provide, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import PolicyBanner from './components/PolicyBanner.vue'
import { usePolicyForm } from '../composables/usePolicyForm'
import { authSession } from '../composables/useAuthSession'
import { confirmPolicyAction } from './confirm-policy-action'
import './applicant-layout.css'

const route = useRoute()
const state = usePolicyForm(confirmPolicyAction)
const applicationRoute = computed(() => ({
  name: 'apply',
  query: state.record.value ? { policyId: state.record.value.id } : {},
}))
provide('policyForm', state)
watch(authSession.revision, state.clear, { flush: 'sync' })
onScopeDispose(state.clear)
</script>
<template>
  <div class="workspace">
    <aside class="sidebar">
      <router-link class="workspace-brand" :to="applicationRoute" aria-label="电商运营平台首页">
        <span class="logo-grid">
          <i></i>
          <i></i>
          <i></i>
          <i></i>
        </span>
        <strong>电商运营平台</strong>
      </router-link>
      <nav aria-label="申请人导航">
        <div class="nav-group">
          <AppIcon name="document" :size="19" />
          <span>政策提交</span>
          <AppIcon class="nav-chevron" name="chevron" :size="14" />
        </div>
        <button
          class="nav-item"
          :class="{ active: route.name === 'apply' }"
          :aria-current="route.name === 'apply' ? 'page' : undefined"
          @click="$router.push(applicationRoute)"
        >
          <span></span>
          提交政策
        </button>
        <button
          class="nav-item"
          :class="{ active: route.name === 'submissions' }"
          :aria-current="route.name === 'submissions' ? 'page' : undefined"
          @click="$router.push({ name: 'submissions' })"
        >
          <span></span>
          我的提交
        </button>
      </nav>
      <div class="sidebar-foot">
        <span class="role-dot"></span>
        申请人工作台
        <span class="role-code">APPLICANT</span>
      </div>
    </aside>
    <main class="workspace-main">
      <PolicyBanner />
      <router-view />
      <p class="workspace-caption">差异化政策管理 · 申请人工作台</p>
    </main>
  </div>
</template>
