# HikariCanvas

Minecraft Paper 1.21+ 插件 + 内嵌 Web 编辑器。通过 TTF 字体渲染 + 模板系统 + 实时投影，在游戏内生成文字招牌。

## 标识

| 项 | 值 |
|---|---|
| 包名 | `moe.hikari.canvas` |
| 命令前缀 | `/hc` |
| 权限前缀 | `hc.` |
| PDC namespace | `hikari_canvas` |
| 工程文件扩展名 | `.canvas` |
| 仓库 | https://github.com/HyacinthHaru/HikariCanvas（MIT） |

## 技术栈

- Java 21 / Paper API 1.21+ / Gradle Kotlin DSL 多模块（`plugin/` + `web/`）
- 插件描述：**`paper-plugin.yml`**（不用 `plugin.yml` 旧格式）
- 本地测试服：**`paperweight-userdev`** 的 `./gradlew runServer`
- 后端依赖：PacketEvents / Javalin 6 / HikariCP + JDBI + SQLite / SnakeYAML
- 前端：Vite + TypeScript；Vue 3 + Konva + Pinia 于 **M5 引入**（M1~M4 前端仅原生 DOM）

## 文档先行

**契约类（已定稿，代码必须与之一致）：**

- `PROPOSAL.md` — 立项总纲
- `docs/architecture.md` — 架构与核心机制
- `docs/protocol.md` — WebSocket v1 协议
- `docs/rendering.md` — 渲染管线与双端一致性
- `docs/template-spec.md` — 模板 YAML v1
- `docs/data-model.md` — SQLite / PDC / `.canvas` 格式
- `docs/security.md` — 威胁模型与安全规范

**操作类（M1 之后按需写，先写易过时）：** `deployment.md` / `development.md` / `api.md` / `troubleshooting.md`

**规则：**
- 写代码前先对照契约文档检查实现意图
- 要改契约 → **先改 `docs/*.md`，再改代码**
- 文档里的「未决问题」清单，实现时回填答案并从列表移除

## Git 提交约定

1. 身份固定：`HaruHyacinth <122684177+HyacinthHaru@users.noreply.github.com>`（本地 `.git/config` 已配，**不动全局**）
2. 所有 commit 必须 SSH 签名：`~/.ssh/id_ed25519.pub`，本地已开 `gpg.format=ssh` + `commit.gpgsign=true` + `tag.gpgsign=true`
3. **禁止** `Co-Authored-By: Claude`（以及任何形式的 Claude 署名）
4. **每次 commit 后立刻 `git push origin main`**——不堆积、不集中推
5. **每次修改必须在 `docs/journal.md` 顶部追加一条**（日期 · 范围 · 改动 · 关联文件）
6. 签名失败**不要用 `--no-gpg-sign` 绕过**，先查原因
7. 签名验证：`gh api /repos/HyacinthHaru/HikariCanvas/commits/<sha> --jq '.commit.verification.verified'` 应返回 `true`

## 不可越界的技术决策

- **预览地图池**是技术核心：编辑期间**只刷像素、不新建 MapView**，避免 `idcounts.dat` 膨胀——这一项做不好整个项目报废
- **双端渲染一致性**：浏览器 Canvas 与 Java Graphics2D 用同一 TTF 文件、禁抗锯齿
- **帧率策略**：静止 0fps · 输入防抖 100ms + 5fps 上限 · 提交全量
- **网络默认绑 `127.0.0.1`**；公网部署必须 nginx/Caddy 反代 + TLS
- **字体**：仅打包 SIL OFL 协议字体（思源黑体），商业字体让用户自备

## 里程碑

M0 立项 ✅ → M1 端到端验证（1w）→ M2 会话与地图池（2w）→ M3 实时投影（2w）→ M4 渲染引擎（2w）→ M5 编辑器 UI（5w）→ M6 模板系统（1w）→ M7 打磨发布（2w）。总工期约 3.5 个月。
