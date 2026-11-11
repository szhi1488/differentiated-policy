import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [vue(), Components({ dts: false, resolvers: [ElementPlusResolver()] })],
  test: {
    server: { deps: { inline: [/element-plus/] } },
    setupFiles: ['./src/test-setup.js'],
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: { '/api': 'http://127.0.0.1:8080' },
  },
})
