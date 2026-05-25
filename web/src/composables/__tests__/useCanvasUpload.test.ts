/**
 * 2026-05-25 bugfix：URL 粘贴上传无响应。
 *
 * 根因：旧 IMAGE_URL_RE 强制要求 URL 以 .png/.jpg/.jpeg/.gif/.webp 扩展名结尾，但绝大多数
 * 现代图片 URL（CDN / 签名 URL / Discord/Imgur token URL）不带扩展，paste 静默 no-op。
 *
 * 修法：放宽为 looksLikeHttpUrl 任意 http(s) URL。本测试锁定新行为，防回归。
 */

import { describe, it, expect } from 'vitest';
import { looksLikeHttpUrl } from '../useCanvasUpload';

describe('looksLikeHttpUrl', () => {
    describe('应该匹配（合法 URL）', () => {
        it.each([
            // 传统带扩展名
            'https://example.com/image.png',
            'https://example.com/image.jpg',
            'https://example.com/image.jpeg',
            'https://example.com/image.gif',
            'https://example.com/image.webp',
            // 大写 scheme（regex /i flag）
            'HTTPS://example.com/foo',
            'HTTP://example.com/foo',
            // 旧 regex 漏抓的现代 URL（bug 根因 case）
            'https://i.imgur.com/abc123',                                          // 无扩展
            'https://cdn.discordapp.com/attachments/123/456/img.png?ex=abc&hm=def', // 多 query
            'https://example.com/api/avatar/userid',                                // service URL
            'https://example.com/image.png#fragment',                               // fragment
            'https://example.com/api/img?format=png&id=123&token=xyz',              // 多 query 无扩展
            'http://example.com',                                                   // 仅 host
            'https://example.com:8080/path/to/resource',                            // 端口
            'https://example.com/p%20path/img%20file',                              // 含 %20
        ])('匹配: %s', (url) => {
            expect(looksLikeHttpUrl(url)).toBe(true);
        });
    });

    describe('不应该匹配（非 URL / 不合法）', () => {
        it.each([
            ['空字符串', ''],
            ['纯文本', 'hello world'],
            ['无 scheme', 'example.com/image.png'],
            ['file scheme', 'file:///etc/passwd'],
            ['javascript scheme', 'javascript:alert(1)'],
            ['data scheme', 'data:image/png;base64,iVBOR...'],
            ['ftp scheme', 'ftp://example.com/file'],
            ['仅 scheme', 'https://'],
            ['含空白（多 token）', 'https://example.com/foo bar.png'],
            ['多行', 'https://example.com\nhttps://other.com'],
            ['含尖括号 HTML', '<https://example.com/img.png>'],
            ['含双引号', '"https://example.com/img.png"'],
            ['含单引号', "'https://example.com/img.png'"],
        ])('不匹配 (%s): %s', (_label, url) => {
            expect(looksLikeHttpUrl(url)).toBe(false);
        });
    });

    describe('长度上限', () => {
        it('正常长度 URL 通过', () => {
            const url = 'https://example.com/' + 'a'.repeat(100);
            expect(looksLikeHttpUrl(url)).toBe(true);
        });

        it('超过 2048 字符的 URL 拒绝（防 spam paste）', () => {
            const url = 'https://example.com/' + 'a'.repeat(2050);
            expect(looksLikeHttpUrl(url)).toBe(false);
        });

        it('恰好 2048 字符通过（边界）', () => {
            const prefix = 'https://example.com/';
            const url = prefix + 'a'.repeat(2048 - prefix.length);
            expect(url.length).toBe(2048);
            expect(looksLikeHttpUrl(url)).toBe(true);
        });
    });
});
