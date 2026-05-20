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
        // 0.4.1-P3.7：把 Lexical 拆独立 chunk。Lexical 仅在 TextElement 编辑场景需要，
        // 占 ~165 kB（gzip ~50 kB），不拆会让 main bundle 从 643 kB 涨到 808 kB（> 800 kB
        // 阈值）。拆 chunk 后 main 回到 ~643 kB，lexical chunk 与主 bundle 并发下载。
        //
        // Vite 8 / rolldown 只接受 function 形态的 manualChunks（不再支持 object map）。
        rollupOptions: {
            output: {
                manualChunks(id: string) {
                    if (
                        id.includes('node_modules/lexical/') ||
                        id.includes('node_modules/@lexical/')
                    ) {
                        return 'lexical';
                    }
                    return undefined;
                },
            },
        },
    },
    test: {
        // M18-P5 / M28-P2-G：node 环境跑纯算法 / composable / 校验逻辑测试。
        // 组件渲染测试暂不引入（需 @vue/test-utils + jsdom），改写纯逻辑测试。
        environment: 'node',
        include: ['src/**/*.test.ts', 'test/**/*.test.ts'],
    },
});
