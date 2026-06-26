<img width="1983" height="793" alt="image" src="https://github.com/user-attachments/assets/8721c5d5-ffe6-4664-806a-31d57b5e09b6" />

# HikariCanvas

[![CI](https://github.com/HyacinthHaru/HikariCanvas/actions/workflows/ci.yml/badge.svg)](https://github.com/HyacinthHaru/HikariCanvas/actions/workflows/ci.yml)

**Minecraft 里的「所见即所得」招牌 / 信息屏编辑器** —— 在浏览器里画，实时渲染到游戏内的地图墙上展示。

把 Canva / Figma 的编辑体验，和 Scratch 的可视化脚本，搬进 Minecraft。你在网页里画一张图、排个版、做个动画，游戏内的地图展示框立刻同步显示同样的画面。可以拿来做店铺招牌、服务器公告屏、地铁 / 车站 PIDS（到站信息屏），甚至右键地图就触发一连串动作的可编程画布。

> ⚠️ **预发布版本**：当前最新为 **`v0.9.4-rc.1`**（release candidate，1.0 正式版之前）。功能已基本完整，正在做实机测试与打磨，可能还有未发现的问题。欢迎试用并反馈。

---

## 环境要求

| 项 | 要求 |
|---|---|
| 服务端 | **Paper 1.21.11 或更高** |
| Java | **21** |

## 下载

从 GitHub Releases 下载最新的 `HikariCanvas-<版本>.jar`：

**👉 https://github.com/HyacinthHaru/HikariCanvas/releases**

当前最新版本是预发布 **`v0.9.4-rc.1`**。

## 60 秒上手

1. 下载 jar，丢进服务器的 `plugins/` 目录
2. 重启服务器（首次启动会自动生成 `plugins/HikariCanvas/config.yml`）
3. 进游戏，用 `/canvas` 系列命令创建并编辑你的招牌
4. 编辑链接默认绑定本机 `127.0.0.1`，**同机开服 + 同机编辑开箱即用**，点开聊天里弹出的链接即可

详细步骤见 [部署指南 §1](docs/deployment.md)。

> 💡 GitHub Releases 上的发布 jar 约 **90 MB**，体积偏大是因为**内置了 20+ 套 TTF 字体**（由后端统一供给给编辑器，保证浏览器所见 = 游戏所得，无需服主另外配字体）。这是正常现象，首次启动稍慢也正常。

## 功能一览

**编辑器**
- 浏览器内「所见即所得」编辑器，画面实时渲染到游戏内地图墙
- 文字排版：内置 20+ 套字体（中文黑体 / 宋体 / 像素体 / 多款艺术字体，西文衬线 / 等宽 / 装饰体），支持加粗、斜体
- 绘图工具：形状、路径、画笔、渐变填充、油漆桶
- 图片上传 + 蒙版、内置图标库、**SVG 矢量图导入**（自动转成可继续编辑的元素）
- 模板系统（创意工坊，一键套用 / 分享版式）
- 透明背景、双语界面（中英）

**动态数据**
- 变量系统 + 四层数据源，画面里的数字 / 文字可以随数据自动更新
- 接入 **PlaceholderAPI**（直接用服内已有的占位符）
- **Push API**：让其它插件把自己的数据推送到招牌上显示（开发者接入见 [插件接入文档](docs/api.md)）
- **列车时刻表 / 完整铁路网络**：线路 + 站点 + 车次 + 服务类型，可做地铁 / 车站到站信息屏（PIDS）

**动画与脚本**
- **时间轴动画**：关键帧 + 缓动曲线
- **补间动画**：「在 X 秒内」平滑过渡到目标状态
- **Scratch 式可视化积木脚本**：拖积木就能编程
  - 触发器：右键墙 / 玩家靠近 / 玩家进服 等
  - 动作：改变量 / 播放动画 / 执行服主白名单内的命令模板 等

**工程管理**
- 工程导入导出（`.canvas` 文件），方便备份与分享作品
- 双端渲染严格一致：浏览器里看到什么，游戏内就是什么

## 公网部署

编辑器默认只绑定本机 `127.0.0.1`，本地 / 局域网即开即用。

**如果要让公网玩家访问编辑器，必须在前面套一层 nginx / Caddy 反向代理并启用 TLS**，绝不能把端口直接暴露到公网明文访问。详见：

- [部署指南 §3 · 公网部署推荐路径](docs/deployment.md)
- [安全说明 SECURITY.md](SECURITY.md)

## 文档

| 文档 | 内容 |
|---|---|
| [部署指南](docs/deployment.md) | 单机 / 局域网 / 公网部署，config.yml 速查 |
| [变量教程](docs/variables.md) | 变量系统、数据源、PlaceholderAPI 接入 |
| [时间轴指南](docs/timeline-guide.md) | 关键帧动画怎么做 |
| [脚本指南](docs/scripting-guide.md) | 可视化积木脚本玩法 |
| [插件接入](docs/api.md) | 给开发者：把数据推到招牌的 API |
| [安全说明](SECURITY.md) | 公网部署边界、漏洞上报 |
| [排错](docs/troubleshooting.md) | 常见问题排查 |

## 更新日志

- 每个版本的更新内容见 [GitHub Releases](https://github.com/HyacinthHaru/HikariCanvas/releases) 的发布说明
- 详细开发日志见 [`docs/journal.md`](docs/journal.md)

## License

[MIT](https://github.com/HyacinthHaru/HikariCanvas) —— 自由使用、修改、分发。

---

这个项目的部分代码使用了 AI 辅助编写，但全程遵循作者本人的技术准绳，定期回顾并集中 Debug，上线前会做实机测试与审查。

感谢你的关注！

<img width="3104" height="1806" alt="image" src="https://github.com/user-attachments/assets/1e0d9c9a-da5f-4bf9-a9ca-1484323219f9" />
