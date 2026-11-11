import { createApp } from 'vue'
import App from './App.vue'
import './assets/styles/brand.css'
import './assets/styles/base.css'
import { createWorkspaceRouter } from './router'

createApp(App).use(createWorkspaceRouter()).mount('#app')
