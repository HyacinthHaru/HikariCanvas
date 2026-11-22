import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';
import tailwind from '@tailwindcss/vite';
import path from 'node:path';

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
        rolldownOptions: {
            treeshake: {
                // 生产构建摇掉纯开发探针，保留 console.error / console.warn。
                manualPureFunctions: [
                    'console.log',
                    'console.info',
                    'console.debug',
                    'console.trace',
                    'console.dir',
                ],
            },
            output: {
                minify: {
                    compress: {
                        dropDebugger: true,
                    },
                },
                manualChunks(id: string) {
                    if (
                        id.includes('node_modules/lexical/') ||
                        id.includes('node_modules/@lexical/')
                    ) {
                        return 'lexical';
                    }
                    // i18n 表被首屏同步组件与异步组件共享，钉到独立 chunk 防止下沉进异步入口。
                    if (id.includes('src/i18n/')) {
                        return 'i18n';
                    }
                    if (id.includes('src/script/')) {
                        return 'script-engine';
                    }
                    return undefined;
                },
            },
        },
    },
    test: {
        environment: 'node',
        include: ['src/**/*.test.ts', 'test/**/*.test.ts'],
    },
});
