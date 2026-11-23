<script setup>
import { computed, onScopeDispose, provide, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import PolicyBanner from './components/PolicyBanner.vue'
import WorkspaceTopbar from './components/WorkspaceTopbar.vue'
import { APPLICANT_NAV_GROUPS } from '../constants/navigation'
import { useWorkspaceSidebar } from './use-workspace-sidebar'
import { useLatestQuery } from '../composables/useLatestQuery'
import { readAssignedPlatforms } from '../api/policies'
import { usePolicyForm } from '../composables/usePolicyForm'
import { authSession } from '../composables/useAuthSession'
import './workspace-navigation.css'
import './applicant-layout.css'

const route = useRoute()
const router = useRouter()
const policyMenuOpen = ref(true)
const { collapsed, expanded, toggle } = useWorkspaceSidebar(520)
const permissions = useLatestQuery()
const refreshPlatforms = () => permissions.run(readAssignedPlatforms)
const state = usePolicyForm(permissions.data, undefined, refreshPlatforms)
provide('platformPermissions', { ...permissions, refreshPlatforms })
const applicationRoute = computed(() => ({
  name: 'apply',
  query: state.record.value ? { policyId: state.record.value.id } : {},
}))
provide('policyForm', state)
provide('modelSubsidyPreferences', { lastQuery: ref(null), showIndependent: ref(false) })
// 在地址变更前确认，取消导航时保留原地址、表单和附件；加载由申请页负责。
const removePolicyGuard = router.beforeEach((to) => {
  const id = to.query.policyId
  if (
    to.name === 'apply' &&
    typeof id === 'string' &&
    (id !== state.record.value?.id || state.loading.value)
  ) {
    return state.confirmPolicyReplacement()
  }
  return true
})
onScopeDispose(removePolicyGuard)
watch(
  authSession.revision,
  () => {
    state.clear()
    void refreshPlatforms()
  },
  { flush: 'sync' },
)
onScopeDispose(state.clear)
</script>
<template>
  <div class="workspace" :class="{ 'is-sidebar-collapsed': collapsed }">
    <aside id="applicant-sidebar" class="sidebar workspace-sidebar">
      <router-link class="workspace-brand" :to="applicationRoute" aria-label="电商运营平台首页">
        <span class="logo-grid">
          <i></i>
          <i></i>
          <i></i>
          <i></i>
        </span>
        <strong>电商运营平台</strong>
      </router-link>
      <nav class="workspace-sidebar-nav" aria-label="申请人导航">
        <button
          class="nav-group workspace-nav-group"
          type="button"
          aria-controls="applicant-policy-links"
          :aria-expanded="policyMenuOpen"
          @click="policyMenuOpen = !policyMenuOpen"
        >
          <AppIcon name="document" :size="19" />
          <span>政策提交</span>
          <AppIcon class="workspace-nav-chevron" name="chevron" :size="14" />
        </button>
        <div
          id="applicant-policy-links"
          class="applicant-policy-links"
          :class="{ 'is-collapsed': !policyMenuOpen }"
        >
          <button
            v-for="link in APPLICANT_NAV_GROUPS[0].links"
            :key="link.name"
            class="nav-item workspace-nav-item"
            :class="{ 'is-active': route.name === link.name }"
            :aria-current="route.name === link.name ? 'page' : undefined"
            @click="router.push(link.name === 'apply' ? applicationRoute : { name: link.name })"
          >
            {{ link.label }}
          </button>
        </div>
      </nav>
    </aside>
    <div class="applicant-body">
      <WorkspaceTopbar
        :user="authSession.user.value"
        :groups="APPLICANT_NAV_GROUPS"
        role-label="申请人"
        sidebar-id="applicant-sidebar"
        :menu-expanded="expanded"
        @toggle-menu="toggle"
        @navigate="(name) => router.push(name === 'apply' ? applicationRoute : { name })"
      />
      <div class="workspace-content-background">
        <main class="workspace-main">
          <PolicyBanner v-if="route.name !== 'applicant-model-subsidies'" />
          <router-view />
        </main>
      </div>
    </div>
  </div>
</template>
