<script setup>
import { computed, provide, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import PolicyBanner from './components/PolicyBanner.vue'
import WorkspaceTopbar from './components/WorkspaceTopbar.vue'
import { APPLICANT_NAV_GROUPS, visibleGroups, homePage } from '../constants/navigation'
import { useWorkspaceSidebar } from './use-workspace-sidebar'
import { authSession } from '../composables/useAuthSession'
import './workspace-navigation.css'
import './applicant-layout.css'

import { useWorkspacePolicyForm } from '../composables/useWorkspacePolicyForm'
import { roleLabels } from '../constants/users'
const route = useRoute()
const router = useRouter()
const policyMenuOpen = ref(true)
const { collapsed, expanded, toggle } = useWorkspaceSidebar(520)
const applicationRoute = useWorkspacePolicyForm()
provide('modelSubsidyLastQuery', ref(null))
const navGroups = computed(() => visibleGroups(authSession.user.value, APPLICANT_NAV_GROUPS))
</script>
<template>
  <div class="workspace" :class="{ 'is-sidebar-collapsed': collapsed }">
    <aside id="applicant-sidebar" class="sidebar workspace-sidebar">
      <router-link
        class="workspace-brand"
        :to="
          homePage(authSession.user.value) === 'apply'
            ? applicationRoute
            : { name: homePage(authSession.user.value) }
        "
        aria-label="电商运营平台首页"
      >
        <span class="logo-grid">
          <i></i>
          <i></i>
          <i></i>
          <i></i>
        </span>
        <strong>电商运营平台</strong>
      </router-link>
      <nav class="workspace-sidebar-nav" aria-label="运营导航">
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
            v-for="link in navGroups[0]?.links"
            :key="link.name"
            class="nav-item workspace-nav-item"
            :class="{ 'is-active': (route.meta.menu || route.name) === link.name }"
            :aria-current="(route.meta.menu || route.name) === link.name ? 'page' : undefined"
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
        :groups="navGroups"
        :role-label="roleLabels(authSession.user.value?.roleCodes)"
        sidebar-id="applicant-sidebar"
        :menu-expanded="expanded"
        @toggle-menu="toggle"
        @navigate="(name) => router.push(name === 'apply' ? applicationRoute : { name })"
      />
      <div class="workspace-content-background">
        <main class="workspace-main">
          <PolicyBanner v-if="route.name === 'apply' || route.name === 'submissions'" />
          <router-view />
        </main>
      </div>
    </div>
  </div>
</template>
