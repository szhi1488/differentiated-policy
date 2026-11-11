<script setup>
import { computed, provide, ref } from 'vue'
import { useRoute } from 'vue-router'
import AppIcon from '../../components/AppIcon.vue'
import { ADMIN_NAV, ADMIN_PAGES } from '../../constants/admin'
import './admin.css'
const props = defineProps({ user: { type: Object, required: true } })
provide(
  'adminUser',
  computed(() => props.user),
)
const route = useRoute()
const menuOpen = ref(false)
const page = computed(() => ADMIN_PAGES.find((item) => item.path === route.path) || ADMIN_PAGES[1])
</script>
<template>
  <div class="admin-workspace">
    <aside class="admin-sidebar" :class="{ 'is-open': menuOpen }">
      <router-link to="/admin/overview" class="workspace-brand" @click="menuOpen = false">
        <span class="logo-grid">
          <i></i>
          <i></i>
          <i></i>
          <i></i>
        </span>
        <strong>电商运营平台</strong>
      </router-link>
      <nav aria-label="管理员导航">
        <div v-for="group in ADMIN_NAV" :key="group.title" class="admin-nav-group">
          <p>{{ group.title }}</p>
          <router-link
            v-for="item in group.items"
            :key="item.path"
            :to="item.path"
            :aria-current="route.path === item.path ? 'page' : undefined"
            @click="menuOpen = false"
          >
            <AppIcon :name="item.icon" :size="18" />
            <span>{{ item.label }}</span>
          </router-link>
        </div>
      </nav>
      <div class="admin-sidebar-foot">
        <span class="role-dot"></span>
        管理员工作台
        <small>POLICY MANAGEMENT</small>
      </div>
    </aside>
    <button
      v-if="menuOpen"
      class="admin-menu-mask"
      aria-label="关闭导航"
      @click="menuOpen = false"
    ></button>
    <div class="admin-body">
      <header class="admin-topbar">
        <div class="admin-breadcrumb">
          <el-button
            class="admin-menu-toggle"
            text
            aria-label="展开导航"
            @click="menuOpen = !menuOpen"
          >
            <AppIcon name="list" />
          </el-button>
          <span>管理控制台</span>
          <span class="admin-divider">/</span>
          <strong>{{ page.label }}</strong>
        </div>
        <div class="admin-identity">
          <el-tag type="info" effect="plain" size="small">展示版</el-tag>
          <span class="admin-avatar">{{ user.name?.slice(0, 1) || '管' }}</span>
          <span>
            {{ user.name || '管理员' }}
            <small>采购管理员</small>
          </span>
        </div>
      </header>
      <main class="admin-content">
        <section class="admin-banner">
          <div class="admin-banner-icon"><AppIcon :name="page.icon" :size="31" /></div>
          <div>
            <div class="admin-eyebrow">DIFFERENTIATED POLICY</div>
            <h1>{{ page.label === '全部政策' ? '差异化政策管理' : page.label }}</h1>
            <p>{{ page.description }}</p>
          </div>
          <div class="admin-banner-art" aria-hidden="true">
            <div class="admin-paper">
              <i></i>
              <i></i>
              <i></i>
              <span><AppIcon name="check" :size="23" /></span>
            </div>
            <div class="admin-orbit"></div>
          </div>
        </section>
        <div class="admin-demo-notice" role="note">
          <span class="admin-demo-dot"></span>
          <strong>界面展示模式</strong>
          <span>
            下方业务数据均为演示样例。筛选与详情仅在当前页面展示，审核、同步、维护操作暂未接入。
          </span>
        </div>
        <router-view :key="route.path" />
        <footer class="admin-footer">
          差异化政策管理 · 管理员工作台
          <span>北京时间 GMT+8</span>
        </footer>
      </main>
    </div>
  </div>
</template>
