<script setup>
import { computed, provide, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import WorkspaceTopbar from './components/WorkspaceTopbar.vue'
import { visibleGroups, homePage, canOpen } from '../constants/navigation'
import { useWorkspaceSidebar } from './use-workspace-sidebar'
import './workspace-navigation.css'
import PolicyBanner from './components/PolicyBanner.vue'
import './applicant-layout.css'
import './admin-layout.css'

import { useWorkspacePolicyForm } from '../composables/useWorkspacePolicyForm'
import { roleLabels } from '../constants/users'
const props = defineProps({ user: { type: Object, required: true } })
const applicationRoute = canOpen(props.user, 'apply') ? useWorkspacePolicyForm() : null
const navGroups = computed(() => visibleGroups(props.user))
function location(name) {
  return name === 'apply' && applicationRoute ? applicationRoute.value : { name }
}
const route = useRoute()
const router = useRouter()
const { collapsed, menuOpen, expanded, toggle } = useWorkspaceSidebar(600)
const openGroups = reactive({})
provide('modelSubsidyLastQuery', ref(null))
watch(
  () => route.fullPath,
  () => (menuOpen.value = false),
)

watch(
  () => route.meta.menu || route.name,
  (name) => {
    const group = navGroups.value.find(({ links }) => links.some((link) => link.name === name))
    if (group) {
      openGroups[group.id] = true
    }
  },
  { immediate: true },
)
</script>

<template>
  <div
    class="admin-workspace"
    :class="{ 'is-sidebar-collapsed': collapsed }"
    @keydown.esc="menuOpen = false"
  >
    <aside
      id="admin-sidebar"
      class="admin-sidebar workspace-sidebar"
      :class="{ 'is-open': menuOpen }"
    >
      <router-link :to="location(homePage(user))" class="workspace-brand" @click="menuOpen = false">
        <AppIcon name="workspace-brand" class="admin-brand-icon" :size="23" />
        <strong>电商运营平台</strong>
      </router-link>

      <nav class="admin-nav workspace-sidebar-nav" aria-label="工作台导航">
        <div v-for="group in navGroups" :key="group.id" class="admin-nav-section">
          <el-button
            class="admin-nav-group workspace-nav-group"
            text
            :aria-expanded="Boolean(openGroups[group.id])"
            :aria-controls="group.id"
            @click="openGroups[group.id] = !openGroups[group.id]"
          >
            <AppIcon :name="group.icon" :size="19" />
            <span>{{ group.label }}</span>
            <AppIcon class="workspace-nav-chevron" name="chevron" :size="14" />
          </el-button>
          <div v-show="openGroups[group.id]" :id="group.id">
            <router-link
              v-for="link in group.links"
              :key="link.name"
              :to="location(link.name)"
              class="workspace-nav-item"
              :class="{ 'is-active': (route.meta.menu || route.name) === link.name }"
              @click="menuOpen = false"
            >
              {{ link.label }}
            </router-link>
          </div>
        </div>
      </nav>
    </aside>

    <button
      v-if="menuOpen"
      class="admin-menu-mask"
      aria-label="关闭导航"
      @click="menuOpen = false"
    ></button>

    <div class="admin-body">
      <WorkspaceTopbar
        :user="user"
        :groups="navGroups"
        :role-label="roleLabels(user.roleCodes)"
        sidebar-id="admin-sidebar"
        :menu-expanded="expanded"
        @toggle-menu="toggle"
        @navigate="(name) => router.push(location(name))"
      />
      <div class="workspace-content-background">
        <main class="admin-content">
          <PolicyBanner v-if="route.name === 'apply' || route.name === 'submissions'" />
          <router-view />
        </main>
      </div>
    </div>
  </div>
</template>
