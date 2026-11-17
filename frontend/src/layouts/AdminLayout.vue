<script setup>
import { reactive, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import WorkspaceTopbar from './components/WorkspaceTopbar.vue'
import { ADMIN_NAV_GROUPS as navGroups } from '../constants/navigation'
import { useWorkspaceSidebar } from './use-workspace-sidebar'
import './workspace-navigation.css'
import './admin-layout.css'

defineProps({ user: { type: Object, required: true } })
const route = useRoute()
const router = useRouter()
const { collapsed, menuOpen, expanded, toggle } = useWorkspaceSidebar(600)
const openGroups = reactive({})
watch(
  () => route.fullPath,
  () => (menuOpen.value = false),
)

watch(
  () => route.name,
  (name) => {
    const group = navGroups.find(({ links }) => links.some((link) => link.name === name))
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
      <router-link
        :to="{ name: 'catalog-sources' }"
        class="workspace-brand"
        @click="menuOpen = false"
      >
        <AppIcon name="workspace-brand" class="admin-brand-icon" :size="23" />
        <strong>电商运营平台</strong>
      </router-link>

      <nav class="admin-nav workspace-sidebar-nav" aria-label="管理员导航">
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
              :to="{ name: link.name }"
              class="workspace-nav-item"
              :class="{ 'is-active': route.name === link.name }"
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
        role-label="管理员"
        sidebar-id="admin-sidebar"
        :menu-expanded="expanded"
        @toggle-menu="toggle"
        @navigate="(name) => router.push({ name })"
      />
      <div class="workspace-content-background">
        <main class="admin-content">
          <router-view />
        </main>
      </div>
    </div>
  </div>
</template>
