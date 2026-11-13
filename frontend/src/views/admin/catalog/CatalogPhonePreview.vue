<script setup>
defineProps({ tint: { type: String, required: true } })
</script>

<template>
  <svg
    class="catalog-phone-preview"
    viewBox="0 0 32 40"
    aria-hidden="true"
    :style="{ color: tint }"
  >
    <rect x="3" y="4" width="16" height="32" rx="3.3" fill="currentColor" />
    <path d="M5 31 17 9v25H6Z" fill="#fff" opacity=".24" />
    <rect x="5" y="6" width="6.4" height="9" rx="2" fill="#18263b" opacity=".8" />
    <circle cx="8.2" cy="8.8" r="1.35" fill="#a5b8d6" />
    <circle cx="8.2" cy="12.1" r="1.3" fill="#a5b8d6" />
    <rect x="14" y="6" width="15" height="31" rx="3" fill="#18263b" />
    <rect x="15" y="7" width="13" height="29" rx="2.3" fill="currentColor" />
    <path d="M15 26Q20 13 28 11v13Q22 23 15 34Z" fill="#fff" opacity=".42" />
    <path d="M15 33q7-10 13-8v9q0 2-2 2h-9q-2 0-2-3Z" fill="#132749" opacity=".22" />
    <circle cx="21.5" cy="8.6" r=".8" fill="#18263b" />
  </svg>
</template>

<style scoped>
.catalog-phone-preview {
  display: block;
  flex: none;
  width: 29px;
  height: 36px;
  border-radius: 5px;
  background: #eff4fb;
}
</style>
