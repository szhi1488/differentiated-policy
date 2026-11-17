<script setup>
defineProps({ name: String, size: { type: [Number, String], default: 20 } })
const paths = {
  menu: 'M4 6h16 M4 12h16 M4 18h16',
  users:
    'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0 M17 3a4 4 0 0 1 0 8 M22 21v-2a4 4 0 0 0-3-3.87',
  help: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0 M9.5 9a2.5 2.5 0 1 1 4.4 1.6c-.9.6-1.9 1.1-1.9 2.4 M12 17h.01',
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
  copy: 'M9 9h12v12H9z M15 6V3H3v12h3',
  filter: 'M4 5h16l-6 7v5l-4 2v-7z',
  stop: 'M20 12a8 8 0 1 1-16 0 8 8 0 0 1 16 0z M8 12h8',
  timeline: 'M4 4v16h16 M8 8h9 M8 12h5 M13 16h7',
}
// 平台简称使用本地矢量标识，不依赖图片、字体图标或远程资源。
const platformBadges = {
  'platform-JD': { label: '京', color: '#e84444' },
  'platform-TMALL': { label: '天', color: '#293344' },
  'platform-PDD': { label: '拼', color: '#ee4455' },
  'platform-DOUYIN': { label: '抖', color: '#293344' },
  'platform-KUAISHOU': { label: '快', color: '#f38a32' },
  'platform-TAOBAO': { label: '淘', color: '#ef8932' },
  'platform-UNICOM': { label: '联', color: '#d94c4c' },
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
    <g v-if="name === 'workspace-brand'" stroke="none">
      <rect x="1" y="1" width="9" height="9" rx="3" fill="#347cff" />
      <rect
        x="14"
        y="1"
        width="9"
        height="9"
        rx="3.5"
        fill="#72a7ff"
        transform="rotate(18 18.5 5.5)"
      />
      <rect x="1" y="14" width="9" height="9" rx="3" fill="#5e97ff" />
      <rect x="14" y="14" width="9" height="9" rx="3" fill="#347cff" />
    </g>
    <g v-else-if="platformBadges[name]" stroke="none">
      <rect x="1" y="1" width="22" height="22" rx="6" :fill="platformBadges[name].color" />
      <text
        x="12"
        y="17"
        text-anchor="middle"
        fill="#fff"
        font-size="14"
        font-family="Microsoft YaHei, sans-serif"
        font-weight="600"
      >
        {{ platformBadges[name].label }}
      </text>
    </g>
    <rect
      v-else-if="name === 'legend'"
      x="2"
      y="6"
      width="20"
      height="12"
      rx="3"
      fill="currentColor"
      fill-opacity="0.16"
    />
    <path v-else :d="paths[name] || paths.document" />
  </svg>
</template>
