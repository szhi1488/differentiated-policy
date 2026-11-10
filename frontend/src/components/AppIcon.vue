<script setup>
defineProps({ name: String, size: { type: [Number, String], default: 20 } })
const paths = {
  document: 'M14 3H5v18h14V8z M14 3v6h5 M8 13h8 M8 17h6',
  chevron: 'm8 10 4 4 4-4',
  plus: 'M12 5v14 M5 12h14',
  upload: 'M8 16H6a4 4 0 0 1-.5-8A6.5 6.5 0 0 1 18 7a4.5 4.5 0 0 1 0 9h-2 M12 12v9 m-3-6 3-3 3 3',
  list: 'M8 6h13 M8 12h13 M8 18h13 M3 6h.01 M3 12h.01 M3 18h.01',
  arrow: 'M5 12h14 m-6-6 6 6-6 6',
  check: 'm5 12 4 4L19 6',
}
</script>
<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.65"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path :d="paths[name] || paths.document" />
  </svg>
</template>
