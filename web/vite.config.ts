import { defineConfig } from 'vite';

// 详细配置见 docs/architecture.md。M1 阶段只需要本地 dev server + 基础构建。
export default defineConfig({
    server: {
        host: '127.0.0.1',
        port: 5173,
    },
    build: {
        outDir: 'dist',
        emptyOutDir: true,
        target: 'es2022',
    },
});
