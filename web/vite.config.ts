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
                    // 0.7.0 P4：i18n 表（messages.ts ~1900 行中英）被首屏 TopBar 等同步组件
                    // 与异步组件（TimelineDock / ScriptEditorOverlay）共享。引入 script-engine
                    // 异步入口后，rolldown 会把共享的 messages 下沉到 script-engine，导致首屏
                    // 反向拉取整个 script chunk。显式钉到独立 i18n chunk（index 同步加载它，
                    // 异步 chunk 仅引用它）破除下沉。
                    if (id.includes('src/i18n/')) {
                        return 'i18n';
                    }
                    // 0.7.0 P4：积木脚本编辑器拆独立 chunk（懒加载，首次开编辑器才下载）。
                    if (id.includes('src/script/')) {
                        return 'script-engine';
                    }
                    return undefined;
                },
            },
        },
    },
    // 生产 build 摇掉纯开发探针（console.log/info/debug/trace/dir），保留 error/warn。
    // 用 pure（只把这几个标记为无副作用、返回值未用时可摇树）而非 drop:['console']——
    // 后者会连 console.error/warn 一起删掉。仅生产 minify 生效，vite dev 不 minify，
    // 开发期 console 照常。防未来漏 gate 的裸 console.log 进生产 bundle。
    esbuild: {
        pure: ['console.log', 'console.info', 'console.debug', 'console.trace', 'console.dir'],
        drop: ['debugger'],
    },
    test: {
        // M18-P5 / M28-P2-G：node 环境跑纯算法 / composable / 校验逻辑测试。
        // 组件渲染测试暂不引入（需 @vue/test-utils + jsdom），改写纯逻辑测试。
        environment: 'node',
        include: ['src/**/*.test.ts', 'test/**/*.test.ts'],
    },
});
