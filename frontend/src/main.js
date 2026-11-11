import { createApp } from 'vue'
import App from './App.vue'
import './assets/main.css'
import { createWorkspaceRouter } from './router'

createApp(App).use(createWorkspaceRouter()).mount('#app')
