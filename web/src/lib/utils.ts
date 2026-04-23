import { type ClassValue, clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';

/**
 * Tailwind class 合并工具（shadcn-vue 约定）。
 * 用法：<div :class="cn('base', variant === 'ghost' && 'hover:bg-accent', props.class)">
 */
export function cn(...inputs: ClassValue[]): string {
    return twMerge(clsx(inputs));
}
