/// <reference types="vitest" />
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import tailwind from '@tailwindcss/vite';
import path from 'node:path';

// M5-A1：Vue 3 + Pinia + Tailwind 4 + shadcn-vue 栈。
// 详细说明见 docs/architecture.md §2.2 + docs/journal.md M5 条目。
// M18-P5：vitest 配置挂在 test 段——livepaint 是纯算法，node 环境最快；不需 jsdom。
export default defineConfig({
    plugins: [vue(), tailwind()],
    resolve: {
        alias: {
            '@': path.resolve(__dirname, 'src'),
        },
    },
    server: {
        host: '127.0.0.1',
        port: 9173,
        strictPort: true,
    },
    build: {
        outDir: 'dist',
        emptyOutDir: true,
        target: 'es2022',
    },
    test: {
        // M18-P5 / M28-P2-G：node 环境跑纯算法 / composable / 校验逻辑测试。
        // 组件渲染测试暂不引入（需 @vue/test-utils + jsdom），改写纯逻辑测试。
        environment: 'node',
        include: ['src/**/*.test.ts', 'test/**/*.test.ts'],
    },
});
