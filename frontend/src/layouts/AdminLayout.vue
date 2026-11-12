<script setup>
import { ref } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import './admin-layout.css'

defineProps({ user: { type: Object, required: true } })
const menuOpen = ref(false)
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
        <div class="admin-nav-group">
          <AppIcon name="box" :size="18" />
          商品
        </div>
        <router-link :to="{ name: 'catalog-sources' }" @click="menuOpen = false">
          源商品库
        </router-link>
        <router-link :to="{ name: 'catalog-cleaning' }" @click="menuOpen = false">
          商品清洗与识别
        </router-link>
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
