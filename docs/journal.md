# 变更日志

本文件记录 Claude 每次对本项目做出的修改。**新条目追加到文件顶部**（倒序）。每条应含：日期、改动范围、简要说明、关联文件。
代码与文档的日常提交信息写 git commit，本文件只留会话级摘要。

---

## 2026-04-19 · 项目改名 HikariBetterText → HikariCanvas

**范围：** 全局重命名 + 新增 journal.md + .gitignore + git 初始化

**改动：**
- 全局替换文档里的项目标识：
  - `HikariBetterText` → `HikariCanvas`
  - `moe.hikari.bettertext` → `moe.hikari.canvas`
  - `hikari_better_text` (PDC 命名空间) → `hikari_canvas`
  - `HbtCommand` → `HcCommand`
  - `/hbt` (游戏内命令) → `/hc`
  - `hbt.` (权限节点前缀) → `hc.`
  - `hbt:` (PDC key 前缀) → `hc:`
  - `.hbt` (工程导出文件扩展名) → `.canvas`
- 新增本文件 `docs/journal.md`
- 新增根目录 `.gitignore`
- 初始化 git 仓库，创建 GitHub `HyacinthHaru/HikariCanvas` 公开仓库并首次推送

**关联文件：** `PROPOSAL.md`、`docs/architecture.md`、`docs/protocol.md`、`docs/rendering.md`、`docs/template-spec.md`、`docs/data-model.md`、`docs/security.md`、`docs/journal.md`（新）、`.gitignore`（新）

---

## 2026-04-19 · 立项期 6 份契约文档完成

**范围：** 所有立项期需要的设计文档一次性写完

**改动：**
- `docs/architecture.md`：系统架构总览、组件分层、编辑会话生命周期状态机、预览地图池机制（核心）、实时投影管线、双端渲染一致性原则、墙面识别、持久化分层、Web 服务层、关键非功能需求、配置骨架
- `docs/protocol.md`：浏览器 ↔ 插件 WebSocket 协议 v1；消息信封格式、连接生命周期、请求/响应模型、所有 op 类型、错误模型（应用层 error + WS close 码）、工程状态模型 TypeScript 定义、完整交互示例、限流、版本化规则
- `docs/rendering.md`：渲染五层管线（Layout / Rasterize / Composite / Quantize / Slice）、字体管理（同 TTF 双端）、排版算法、Graphics2D 与 Canvas 必设项、效果（描边/阴影/发光）实现、调色板 LUT 预生成、CIE76 距离、透明处理、双端 snapshot 测试规范与 CI 要求
- `docs/template-spec.md`：模板 YAML 格式 spec v1；顶层字段、canvas 定义、layout 三种类型、text/rect/line/icon 元素、参数系统类型表、表达式子集、实例化语义、版本兼容规则、完整示例
- `docs/data-model.md`：SQLite schema（schema_version / pool_maps / sign_records / audit_log / template_usage 五表）、PDC key 约定、`.canvas` 工程文件 zip 格式、配置字段约束、迁移策略、一致性不变式与修复
- `docs/security.md`：威胁模型 T1~T13、Token 机制与防暴力、WebSocket 安全、输入校验白名单、权限节点表、部署安全建议（nginx 配置）、审计事件清单、依赖安全、响应渠道

**关联文件：** `docs/*.md`

---

## 2026-04-19 · 网页优先方向确认 + PROPOSAL 大改

**范围：** 核心交互从「命令 + GUI」转为「网页编辑器 + 实时投影」，立项文件随之重写

**改动：**
- `PROPOSAL.md`：
  - 核心创新 3.3 改为「网页编辑器 + 实时投影」
  - v1 MVP 整体替换为「Canva 式完整编辑器 + 实时投影链路 + 预览地图池 + 命令辅助」
  - 新增 5.2 关键机制：双端渲染一致性、预览地图池、帧率策略（静止 0fps / 输入防抖 100ms + 5fps 上限 / 提交全量）、脏矩形差分、WebSocket permessage-deflate
  - 网络绑定默认 `127.0.0.1`，公网部署必须反代 + TLS
  - 技术栈加前端（Vue 3 + Vite + Konva + TypeScript）和 Javalin / PacketEvents / SQLite
  - 项目结构改多模块（`plugin/` + `web/`）
  - 里程碑重排为 M1~M7，总工期 3.5 个月
  - 风险表新增：池机制缺陷、双端一致性、公网暴露、服主端口不足等

**关联文件：** `PROPOSAL.md`

---

## 2026-04-18 · 立项初稿

**范围：** 项目从 0 到 1 的立项讨论与初稿

**改动：**
- 新建 `PROPOSAL.md` 作为立项文件，定义背景、定位、创新点、功能范围、技术栈、里程碑、风险、成功标准
- 讨论并确认定位：避开 `text_display` 的普通文字场景，主打像素风 / 艺术字 / 大招牌
- 确认三大创新点：内置渲染、模板化、分阶段交互
- 初版里程碑：M1~M6，工期约 2 个月（后续因网页优先方向调整）

**关联文件：** `PROPOSAL.md`（新）
