// HikariCanvas 前端入口（M5-A1 起）
// Vue 3 + Pinia + shadcn-vue（Tailwind 4）+ Konva overlay。
// 历史：M1~M4 是纯原生 DOM probe；从 M5-A 切到 Vue 壳。
// 协议契约：docs/protocol.md §2；架构：docs/architecture.md §2.2

import { createApp } from 'vue';
import { createPinia } from 'pinia';
import VueKonva from 'vue-konva';
import App from './App.vue';
import './style.css';

const app = createApp(App);
app.use(createPinia());
app.use(VueKonva);
app.mount('#app');
