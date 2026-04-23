// vue-konva 3.x 全局注册 <v-stage> / <v-layer> / <v-rect> / <v-transformer>
// 为 Volar / vue-tsc 补 GlobalComponents 声明（否则会报 "is not a known element"）。
import type { DefineComponent } from 'vue';

declare module 'vue' {
    export interface GlobalComponents {
        'v-stage': DefineComponent<Record<string, unknown>>;
        'v-layer': DefineComponent<Record<string, unknown>>;
        'v-rect': DefineComponent<Record<string, unknown>>;
        'v-transformer': DefineComponent<Record<string, unknown>>;
        'v-text': DefineComponent<Record<string, unknown>>;
        'v-group': DefineComponent<Record<string, unknown>>;
        'v-image': DefineComponent<Record<string, unknown>>;
        'v-line': DefineComponent<Record<string, unknown>>;
    }
}

export {};
