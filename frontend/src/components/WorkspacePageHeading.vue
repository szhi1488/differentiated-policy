<script setup>
import AppIcon from './AppIcon.vue'

defineProps({
  title: { type: String, required: true },
  icon: { type: String, default: 'document' },
  headingId: { type: String, default: undefined },
})
</script>

<template>
  <div class="workspace-page-heading">
    <span class="workspace-page-heading-icon"><AppIcon :name="icon" :size="26" /></span>
    <h1 :id="headingId">{{ title }}</h1>
  </div>
</template>

<style scoped>
.workspace-page-heading {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
}
.workspace-page-heading-icon {
  display: grid;
  place-items: center;
  flex: none;
  width: 48px;
  height: 48px;
  border-radius: 12px;
  color: #fff;
  background: linear-gradient(150deg, #5ca5ff, #267bf2);
  box-shadow: 0 4px 10px #3079dc24;
}
.workspace-page-heading h1 {
  min-width: 0;
  overflow-wrap: anywhere;
}
</style>
