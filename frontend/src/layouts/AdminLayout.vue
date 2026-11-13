<script setup>
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import './admin-layout.css'

defineProps({ user: { type: Object, required: true } })
const catalogLinks = [
  { name: 'catalog-sources', label: '源商品库' },
  { name: 'catalog-cleaning', label: '商品清洗与识别' },
  { name: 'catalog-center', label: '商品中心' },
]
const route = useRoute()
const menuOpen = ref(false)
const catalogMenuOpen = ref(false)

watch(
  () => route.name,
  (name) => {
    if (catalogLinks.some((link) => link.name === name)) {
      catalogMenuOpen.value = true
    }
  },
  { immediate: true },
)
</script>

<template>
  <div class="admin-workspace" @keydown.esc="menuOpen = false">
    <aside id="admin-sidebar" class="admin-sidebar" :class="{ 'is-open': menuOpen }">
      <router-link
        :to="{ name: 'catalog-sources' }"
        class="workspace-brand"
        @click="menuOpen = false"
      >
        <span class="logo-grid" aria-hidden="true">
          <i></i>
          <i></i>
          <i></i>
          <i></i>
        </span>
        <strong>电商运营平台</strong>
      </router-link>

      <nav class="admin-nav" aria-label="管理员导航">
        <el-button
          class="admin-nav-group"
          text
          :aria-expanded="catalogMenuOpen"
          aria-controls="admin-catalog-links"
          @click="catalogMenuOpen = !catalogMenuOpen"
        >
          <AppIcon name="box" :size="18" />
          <span>商品</span>
          <AppIcon class="admin-nav-chevron" name="chevron" :size="16" />
        </el-button>
        <div v-show="catalogMenuOpen" id="admin-catalog-links">
          <router-link
            v-for="link in catalogLinks"
            :key="link.name"
            :to="{ name: link.name }"
            @click="menuOpen = false"
          >
            {{ link.label }}
          </router-link>
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
      <header class="admin-topbar">
        <el-button
          class="admin-menu-toggle"
          text
          :aria-label="menuOpen ? '收起导航' : '展开导航'"
          :aria-expanded="menuOpen"
          aria-controls="admin-sidebar"
          @click="menuOpen = !menuOpen"
        >
          <AppIcon name="list" />
        </el-button>
        <div class="admin-identity">
          <span class="admin-avatar" aria-hidden="true">{{ user.name?.slice(0, 1) || '管' }}</span>
          <strong>{{ user.name || '管理员' }}</strong>
        </div>
      </header>
      <main class="admin-content">
        <router-view />
      </main>
    </div>
  </div>
</template>
