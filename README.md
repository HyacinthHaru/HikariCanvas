<div align="center">
<img width="1000" height="400" alt="banner" src="https://github.com/user-attachments/assets/8721c5d5-ffe6-4664-806a-31d57b5e09b6" />
</div>

## 欢迎使用 HikariCanvas

<div align="center">

[![CI](https://github.com/HyacinthHaru/HikariCanvas/actions/workflows/ci.yml/badge.svg)](https://github.com/HyacinthHaru/HikariCanvas/actions/workflows/ci.yml)
![Stars](https://img.shields.io/github/stars/HyacinthHaru/HikariCanvas)
![Last Commit](https://img.shields.io/github/last-commit/HyacinthHaru/HikariCanvas)
![Made with ❤️ | By Haru](https://img.shields.io/badge/Made_with_%E2%9D%A4%EF%B8%8F-By_Haru-D8BFD8?labelColor=FFB6C1)

</div>

**HikariCanvas 是一个给 Minecraft 服务器做的「所见即所得」招牌与信息屏插件。**

不用再手搓地图画，不用在命令行里死磕坐标和颜色代码，也不用忍受外部图片导进游戏后糊成一团的色差——点开聊天栏里的链接，在浏览器里像用 Canva 或 Figma 那样排文字、画图形、调图层，游戏里的地图展示框就会实时同步出完全一致的画面。

除了静态海报，你还能给画板接上实时数据、排一段关键帧动画，或者用类似 Scratch 的可视化积木搭一套交互逻辑：做商业街的店铺招牌、主城公告栏、地铁站与机场的实时到站大屏，甚至做成玩家走近自动亮起、右键一下就触发机关的互动展示墙。

<p align="center">
  <img width="700" alt="编辑器界面" src="https://github.com/user-attachments/assets/1e0d9c9a-da5f-4bf9-a9ca-1484323219f9" />
</p>

<p align="center">
  <img height="250" alt="站台信息屏示例" src="https://github.com/user-attachments/assets/3d46ba11-1e60-41ec-8ed4-0efd087de234" />
  <img height="250" alt="动画效果示例" src="https://github.com/user-attachments/assets/4de45b7c-32f6-4c2c-a4e3-c8d9fd924b6d" />
</p>

---

## 环境要求与下载

前往 [GitHub Releases](https://github.com/HyacinthHaru/HikariCanvas/releases) 或 Modrinth 下载最新发布的插件包：![Release](https://img.shields.io/github/v/release/HyacinthHaru/HikariCanvas)

| 项目 | 要求 |
| --- | --- |
| 服务端 | **Paper 1.21.11**（运行于 **Java 21**）或 **Paper 26.1 / 26.2**（运行于 **Java 25**），同一份 jar 通用 |
| 必装前置 | [PacketEvents](https://modrinth.com/plugin/packetevents) `2.13.0` 及以上 |
| 可选联动 | PlaceholderAPI（可直接读取服内已有的占位符数据） |

> 插件包体积约 86–90 MB，是因为内置了 22 款开源字体、矢量图标库和完整的网页编辑器。服主不需要另外找字体配环境，装上就能保证浏览器里看到什么样、游戏里就是什么样；首次启动稍慢属于正常现象。

## 三步上手

1. 把前置插件 **PacketEvents** 和下载好的 **HikariCanvas** 一起放进服务器的 `plugins/` 目录，启动服务器。
2. 进游戏输入 `/canvas edit` 拿到选区魔棒，对准墙面**左键**点一个角、**右键**点对角，再输入 `/canvas confirm`。
3. 点击聊天栏弹出的链接打开浏览器，画完的内容会自动保存并实时投到墙上。之后想再修改，随时输入 `/canvas open <编号或别名>`，或者拿着魔棒对准已有招牌再点一次即可。

完整的命令列表、编辑器工具说明与 LuckPerms 权限配法见 [玩家与服主使用手册](docs/user-guide.md)。

### 关于公网服务器

网页编辑器默认只监听本机 `127.0.0.1`，单机或局域网测试即装即用。

如果要让公网玩家打开编辑器，请在服务器前配一层 Caddy 或 nginx 反向代理并开启 HTTPS，**不要把编辑器端口直接裸露到公网明文访问**。现成的反代配置模板见 [部署指南](docs/deployment.md)。

## 功能一览

### 排版与绘图
- **双端一致的画布编辑器**：浏览器预览与游戏内地图共享同一套字距、调色板与抖动算法，网页里排好是什么样，上墙就是什么样。
- **丰富的字体与图形工具**：内置 22 款开源字体（涵盖中文黑体、宋体、12px 像素体、手写与艺术字，以及多款西文衬线、等宽与装饰字体，也支持服主自行放入 `.ttf` / `.otf` 字体）；提供形状、路径、画笔、渐变填充、油漆桶和 Font Awesome 矢量图标。
- **图片、蒙版与矢量导入**：支持直接粘贴或上传图片、添加路径裁剪蒙版、导入外部 SVG 矢量图，以及透明背景与多图层管理。

### 动态数据与铁道屏
- **实时变量接入**：文字内容可随时插入动态变量，既能直接引用 PlaceholderAPI 占位符和原版计分板，也支持第三方插件通过 [Push API](docs/api.md) 主动推送比分、经济或状态数据。
- **列车时刻表与铁路网络**：除了单块屏独立配置的简易时刻表，还内置了完整的铁路网络管理——可按线路、站点、上下行方向编排车次，支持站站停、大站快车、区间车、车厢编组与逐站到发时刻，多块站台屏绑定站点后自动同步倒计时与进站状态。

### 动画与交互脚本
- **非线性时间轴动画**：内置关键帧与缓动曲线编辑器，可让文字和图形按设定节奏平滑移动、缩放、旋转或淡入淡出。
- **积木式交互脚本**：拖放积木就能给招牌写交互逻辑——响应玩家右键点击、靠近离开、进服或定时触发，控制画面过渡、修改变量、播放音效与粒子，或执行服主预先放行的命令模板。

### 备份、迁移与复用
- **`.canvas` 工程文件**：单块招牌连同图层、图片资源、动画和脚本可一键打包导出为 `.canvas` 文件，跨服迁移或分享给朋友备份都很方便。
- **服内模板工坊**：做好一套满意的版式后，可以把文字、颜色、字号标成可调参数，发布到服内模板市场供其他玩家一键套用。

## 文档导航

### 玩家与服主手册

| 文档 | 说明 |
| --- | --- |
| [玩家与服主使用手册](docs/user-guide.md) | 建墙与二次编辑、编辑器工具导览、全命令表、LuckPerms 权限方案与自定义字体/模板 |
| [部署指南](docs/deployment.md) | 单机、局域网与公网反向代理配置，`config.yml` 常用项说明 |
| [变量与铁路教程](docs/variables.md) | 动态变量、PlaceholderAPI 接入、时刻表与铁路网络配置 |
| [时间轴动画指南](docs/timeline-guide.md) | 关键帧、缓动曲线与循环动画制作方法 |
| [积木脚本指南](docs/scripting-guide.md) | 可视化积木触发器、补间过渡与服主命令白名单配置 |
| [性能压测指南](docs/benchmark.md) | 用 `/canvas bench` 实测当前机器能承载多少块动态画布 |
| [常见问题排错](docs/troubleshooting.md) | 打不开网页、画面不刷新、上传被拒等常见情况排查 |
| [安全说明](SECURITY.md) | 公网部署边界、权限控制与安全机制说明 |

### 插件接入与二次开发

| 文档 | 说明 |
| --- | --- |
| [外部插件接入 API](docs/api.md) | 用 Java API 从你自己的插件向招牌推送实时数据 |
| [构建与二次开发指南](docs/development.md) | 本地编译、前后端联调、项目结构与测试说明 |
| [文档总目录](docs/README.md) | 全套使用手册与底层架构、协议、渲染规范的完整索引 |

## 开源协议

本项目基于 [MIT](LICENSE) 协议开源。

---

本项目的部分代码使用了 AI 辅助编写，全程遵循作者本人的架构设计与技术准绳，上线前均经过实机测试与人工审查。

感谢你的关注与使用！
