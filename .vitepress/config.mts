import { defineConfig } from 'vitepress'

// HikariCanvas 用户文档（Wiki）站点配置。
// 内容以插件实际行为（代码）为准。
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
      { text: '常见问题', link: '/faq' },
    ],

    sidebar: {
      '/guide/': [
        {
          text: '入门',
          items: [
            { text: '认识 HikariCanvas', link: '/guide/' },
            { text: '做你的第一面招牌', link: '/guide/making-a-sign' },
            { text: '编辑器导览', link: '/guide/editor' },
          ],
        },
        {
          text: '设计招牌',
          items: [
            { text: '元素与工具', link: '/guide/elements' },
            { text: '文字', link: '/guide/text' },
            { text: '填充、颜色与透明', link: '/guide/fill' },
            { text: '图片与图标', link: '/guide/images-icons' },
            { text: '图层', link: '/guide/layers' },
            { text: '模板', link: '/guide/templates' },
          ],
        },
        {
          text: '让招牌动起来',
          items: [
            { text: '动态数据：变量', link: '/guide/variables' },
            { text: '到站屏：时刻表与铁路', link: '/guide/transit' },
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
            { text: '安全与公网部署', link: '/admin/security' },
            { text: '性能与压测', link: '/admin/performance' },
            { text: '数据与备份', link: '/admin/backup' },
          ],
        },
      ],
      '/api/': [
        {
          text: '开发者手册',
          items: [
            { text: 'Plugin Push API', link: '/api/' },
            { text: '示例插件', link: '/api/examples' },
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

    lastUpdated: {
      text: '最后更新于',
      formatOptions: { dateStyle: 'medium' },
    },

    darkModeSwitchLabel: '外观',
    sidebarMenuLabel: '菜单',
    returnToTopLabel: '回到顶部',
    langMenuLabel: '语言',

    footer: {
      message: '基于 MIT 协议发布',
      copyright: 'HikariCanvas',
    },
  },
})
