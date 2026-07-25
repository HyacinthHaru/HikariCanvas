<img width="750" height="501" alt="pids" src="https://github.com/user-attachments/assets/d9c362bb-7181-406d-87b0-f1c26490de32" /><div align="center">
<img width="1000" height="400" alt="banner" src="https://github.com/user-attachments/assets/8721c5d5-ffe6-4664-806a-31d57b5e09b6" />
</div>

## 欢迎使用 HikariCanvas

<div align="center">
  
[![CI](https://github.com/HyacinthHaru/HikariCanvas/actions/workflows/ci.yml/badge.svg)](https://github.com/HyacinthHaru/HikariCanvas/actions/workflows/ci.yml)
![Stars](https://img.shields.io/github/stars/HyacinthHaru/HikariCanvas)
![Last Commit](https://img.shields.io/github/last-commit/HyacinthHaru/HikariCanvas)
![Made with ❤️ | By Haru](https://img.shields.io/badge/Made_with_%E2%9D%A4%EF%B8%8F-By_Haru-D8BFD8?labelColor=FFB6C1)
</div>

**这是一个 Minecraft 里的「所见即所得」招牌与信息屏编辑器** —— 在浏览器里像用 Photoshop 那样去画，实时渲染到游戏内的地图墙上展示。还有 Scratch-like 的类通用脚本编辑器、非线性动画编辑与展示、PAPI 接入等更高级功能等你探索。

你的服务器以后能有动态展示框了，还是你自己做的那种。动态到什么程度？你右键一下展示框，能直接触发你自己用积木块搭的脚本。当然，你也可以在这里做简单的非线性动画和触发器。

<p align="center">
  <img width="700" alt="image" src="https://github.com/user-attachments/assets/1e0d9c9a-da5f-4bf9-a9ca-1484323219f9" />
</p>

<p align="center">
  <img height="250" alt="pids" src="https://github.com/user-attachments/assets/3d46ba11-1e60-41ec-8ed4-0efd087de234" />
  <img height="250" alt="HikariCanvas_Motion" src="https://github.com/user-attachments/assets/4de45b7c-32f6-4c2c-a4e3-c8d9fd924b6d" />
</p>

把 Canva / Figma 的编辑体验，和 Scratch 的可视化脚本、After Effects 的非线性动画、地铁站的倒计时大屏幕，统统搬进 Minecraft。在网页里画一张图、排个版、做个动画，游戏内的地图展示框就能立刻同步显示同样的画面。拿来做店铺招牌、服务器公告屏、地铁车站机场信息屏，甚至右键地图就触发一连串动作的可编程画布。

>  **预发布版本**：这是预发布版本，目前已支持 Paper 1.21.11 与 26.1 / 26.2。功能已基本完整，但仍然可能还有未发现的问题。欢迎试用并反馈。

---
## 下载

从 GitHub Releases 下载最新的发布版本，也可以在 Modrinth 里面下载。

当前最新版本是 ![Release](https://img.shields.io/github/v/release/HyacinthHaru/HikariCanvas)

## 快速上手

1. 下载 jar，丢进服务器的 `plugins/` 目录，重启服务器
2. 进游戏，用 `/canvas` 系列命令创建并编辑你的招牌
3. 点开聊天里弹出的链接即可。目前我们默认设置本地监听，如果你是服主，可以自己设置反向代理。

详细步骤见 [部署指南 §1](docs/deployment.md)。

> 你下载到的 jar 约 90 MB，体积偏大是因为内置了 20+ 套开源 TTF 字体，保证浏览器所见 = 游戏所得，无需服主另外配字体。这是正常现象，首次启动稍慢也正常。

## 功能一览

**编辑器**

- 浏览器内「所见即所得」编辑器，画面实时渲染到游戏内地图墙
- 文字排版：内置 20+ 套字体（中文黑体 / 宋体 / 像素体 / 多款艺术字体，西文衬线 / 等宽 / 装饰体），支持加粗、斜体
- 绘图工具：形状、路径、画笔、渐变填充、油漆桶；Font Awesome 内置矢量图标
- 图片粘贴上传 + 蒙版、SVG 矢量图导入
- 模板系统、「创意工坊」、透明背景、i18n

**动态数据**

- 我们有变量系统，画面里的数字 / 文字可以随数据自动更新。你也可以接入 PlaceholderAPI，直接用服内已有的占位符
- 也可以使用 Push API，让你自己的插件把数据推送到招牌上显示（开发者接入见 [插件接入文档](docs/api.md)）
- 列车时刻表 / 完整铁路网络：线路 + 站点 + 车次 + 各种杂七杂八的类型（直快、区间、编组情况等），铁道服务器狂喜
- 
**动画与脚本**

- 基于关键帧 + 缓动曲线的时间轴动画
- 平滑过渡到目标状态的补间动画
- Scratch 式可视化积木脚本，拖积木就能编程。有各种触发器和动作，欢迎体验

**工程管理与分享**

- 工程导入导出 `.canvas` 文件，方便备份与分享作品。双端渲染严格一致：浏览器里看到什么，游戏内就是什么。

## 公网部署

编辑器默认只绑定本机 `127.0.0.1`，本地 / 局域网即开即用。

如果要让公网玩家访问编辑器，必须在前面套一层 nginx 类反向代理并启用 TLS，本插件设计之初即确定绝不能把端口直接暴露到公网明文访问。详见：[部署指南 §3 · 公网部署推荐路径](docs/deployment.md)

## 文档

| 文档                                 | 内容                                      |
| ------------------------------------ | ----------------------------------------- |
| [部署指南](docs/deployment.md)       | 单机 / 局域网 / 公网部署，config.yml 速查 |
| [变量教程](docs/variables.md)        | 变量系统、数据源、PAPI 接入               |
| [时间轴指南](docs/timeline-guide.md) | 关键帧动画玩法                            |
| [脚本指南](docs/scripting-guide.md)  | 可视化积木脚本玩法                        |
| [插件接入](docs/api.md)              | 开发者教程：插件数据推到招牌的 API        |
| [安全说明](SECURITY.md)              | 公网部署边界与安全说明                    |
| [排错](docs/troubleshooting.md)      | 常见问题排查                              |



## License

[MIT](https://github.com/HyacinthHaru/HikariCanvas) 

---

这个项目的部分代码使用了 AI 辅助编写，但全程遵循作者本人的技术准绳，定期回顾并集中 Debug，上线前会做扎实的实机测试与审查。

感谢你的关注！
