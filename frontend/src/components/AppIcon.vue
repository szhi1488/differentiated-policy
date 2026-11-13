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
  home: 'm3 10 9-7 9 7 M5 9v12h14V9 M9 21v-8h6v8',
  shield: 'M12 3 3 7v6c0 5 9 9 9 9s9-4 9-9V7z m-5 9 3 3 5-6',
  sync: 'M20 8a8 8 0 0 0-14-3L3 8 m0-5v5h5 M4 16a8 8 0 0 0 14 3l3-3 m0 5v-5h-5',
  box: 'm3 7 9-4 9 4v10l-9 4-9-4z m0-10 9 4 9-4 M12 11v10 M7 5l10 4',
  alert: 'm12 3 10 18H2z M12 9v5 M12 17h.01',
  layers: 'm12 3 10 5-10 5L2 8z M2 12l10 5 10-5 M2 16l10 5 10-5',
  history: 'M3 11a9 9 0 1 1 2 7 M3 4v7h7 M12 7v5l3 2',
  settings: 'M9 3h6l1 4 4 1v8l-4 1-1 4H9l-1-4-4-1V8l4-1z M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0',
  search: 'M16 10a6 6 0 1 1-12 0 6 6 0 0 1 12 0z M14.5 14.5 20 20',
  download: 'M12 3v12 m-4-4 4 4 4-4 M4 16v4h16v-4',
  filter: 'M4 5h16l-6 7v5l-4 2v-7z',
  stop: 'M20 12a8 8 0 1 1-16 0 8 8 0 0 1 16 0z M8 12h8',
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
