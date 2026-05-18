// HikariCanvas 前端入口（M5-A1 起）
// Vue 3 + Pinia + shadcn-vue（Tailwind 4）+ Konva overlay。
// 历史：M1~M4 是纯原生 DOM probe；从 M5-A 切到 Vue 壳。
// 协议契约：docs/protocol.md §2；架构：docs/architecture.md §2.2

import { createApp } from 'vue';
import { createPinia } from 'pinia';
import VueKonva from 'vue-konva';
import App from './App.vue';
import './style.css';
import { useThemeStore } from '@/stores/theme';

const app = createApp(App);
const pinia = createPinia();
app.use(pinia);
app.use(VueKonva);

// M24-B：theme store 必须在 mount 前实例化一次，让 applyFlavor / applyAccent / applyRadius
// 在首次渲染前就把 .theme-* class + data-* attr 写进 <html>。否则 HomePage（不经过 ui store
// 入口）首屏会看到 shadcn 默认深灰主题，再被 watch 替换，出现一次闪烁。
useThemeStore(pinia);

app.mount('#app');
