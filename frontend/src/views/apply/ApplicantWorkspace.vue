<script setup>
import { ref } from 'vue'
import AppIcon from '../../components/AppIcon.vue'
import PolicyApplicationForm from './components/PolicyApplicationForm.vue'
import PolicyBanner from './components/PolicyBanner.vue'

const section = ref('apply')
</script>
<template>
  <div class="workspace">
    <aside class="sidebar">
      <a class="workspace-brand" href="/" aria-label="电商运营平台首页">
        <span class="logo-grid">
          <i></i>
          <i></i>
          <i></i>
          <i></i>
        </span>
        <strong>电商运营平台</strong>
      </a>
      <nav aria-label="申请人导航">
        <div class="nav-group">
          <AppIcon name="document" :size="19" />
          <span>政策提交</span>
          <AppIcon class="nav-chevron" name="chevron" :size="14" />
        </div>
        <button
          class="nav-item"
          :class="{ active: section === 'apply' }"
          :aria-current="section === 'apply' ? 'page' : undefined"
          @click="section = 'apply'"
        >
          <span></span>
          提交政策
        </button>
        <button
          class="nav-item"
          :class="{ active: section === 'submissions' }"
          :aria-current="section === 'submissions' ? 'page' : undefined"
          @click="section = 'submissions'"
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
      <PolicyApplicationForm v-show="section === 'apply'" />
      <section v-if="section === 'submissions'" class="submissions-panel">
        <h2>
          <span></span>
          我的提交
        </h2>
        <div class="submissions-empty">
          <span><AppIcon name="document" :size="36" /></span>
          <h3>我的提交即将开放</h3>
          <p>政策提交与记录查询功能尚未接入。</p>
          <button class="primary" @click="section = 'apply'">
            前往提交政策
            <AppIcon name="arrow" :size="16" />
          </button>
        </div>
      </section>
      <p class="workspace-caption">差异化政策管理 · 申请人工作台</p>
    </main>
  </div>
</template>
