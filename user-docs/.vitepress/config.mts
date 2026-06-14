import { defineConfig } from 'vitepress'

// HikariCanvas 用户文档（Wiki）站点配置。
// 内容以代码为唯一事实来源；写作前请对照 plugin/ 与 web/ 源码核对。
export default defineConfig({
  lang: 'zh-CN',
  title: 'HikariCanvas',
  description: '游戏内可编程动态招牌系统 — 玩家与服主使用手册',
  lastUpdated: true,
  cleanUrls: true,

  themeConfig: {
    nav: [
      { text: '玩家手册', link: '/guide/' },
      { text: '服主手册', link: '/admin/' },
      { text: '开发者手册', link: '/api/' },
    ],

    sidebar: {
      '/guide/': [
        {
          text: '玩家手册',
          items: [
            { text: '认识 HikariCanvas', link: '/guide/' },
            { text: '做你的第一面招牌', link: '/guide/making-a-sign' },
            { text: '画：元素与工具', link: '/guide/elements' },
            { text: '动态数据：变量', link: '/guide/variables' },
            { text: '时间轴动画', link: '/guide/timeline' },
            { text: '积木脚本', link: '/guide/scripting' },
          ],
        },
      ],
      '/admin/': [
        {
          text: '服主手册',
          items: [
            { text: '安装与部署', link: '/admin/' },
            { text: '命令', link: '/admin/commands' },
            { text: '权限', link: '/admin/permissions' },
            { text: '配置', link: '/admin/config' },
            { text: '安全与配额', link: '/admin/security' },
          ],
        },
      ],
      '/api/': [
        {
          text: '开发者手册',
          items: [
            { text: 'Plugin Push API', link: '/api/' },
          ],
        },
      ],
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/HyacinthHaru/HikariCanvas' },
    ],

    search: { provider: 'local' },

    outline: { level: [2, 3], label: '本页目录' },

    docFooter: { prev: '上一页', next: '下一页' },

    footer: {
      message: '基于 MIT 协议发布',
      copyright: 'HikariCanvas',
    },
  },
})
