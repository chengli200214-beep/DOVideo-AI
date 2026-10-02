import { createApp } from 'vue'
import './style.css'
import App from './App.vue'

if (import.meta.env.VITE_AIGC_ONLY === 'true') {
  document.title = 'DOVideo · AIGC 视频创作平台'
  document.querySelector('meta[name="description"]')?.setAttribute('content', '从需求和分镜到镜头生成、局部重生成、成片合成及可追溯人工评测。')
}

createApp(App).mount('#app')
