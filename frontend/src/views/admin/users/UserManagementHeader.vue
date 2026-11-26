<script setup>
import { computed } from 'vue'
import WorkspacePageHeading from '../../../components/WorkspacePageHeading.vue'
import { authSession } from '../../../composables/useAuthSession'
import { canOpen, USER_MANAGEMENT_SECTIONS } from '../../../constants/navigation'

const sections = computed(() =>
  USER_MANAGEMENT_SECTIONS.filter((section) => canOpen(authSession.user.value, section.name)),
)
</script>

<template>
  <header class="user-management-banner workspace-page-banner">
    <WorkspacePageHeading title="用户管理" icon="users" heading-id="admin-users-title" />
    <slot />
  </header>
  <nav class="user-management-sections" aria-label="用户管理栏目">
    <router-link v-for="section in sections" :key="section.name" :to="{ name: section.name }">
      {{ section.label }}
    </router-link>
  </nav>
</template>

<style scoped>
.user-management-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}
.user-management-sections {
  display: flex;
  gap: 22px;
  padding: 0 18px;
  background: #fff;
  border: 1px solid #e3ebf7;
  border-radius: 9px;
}
.user-management-sections a {
  padding: 12px 2px 10px;
  border-bottom: 2px solid transparent;
  color: #475569;
  font-weight: 500;
  text-decoration: none;
}
.user-management-sections a:hover,
.user-management-sections a[aria-current='page'] {
  color: var(--el-color-primary);
}
.user-management-sections a[aria-current='page'] {
  border-bottom-color: currentColor;
}
</style>
