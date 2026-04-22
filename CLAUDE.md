# HikariCanvas

Minecraft Paper 1.21+ 插件 + 内嵌 Web 编辑器。通过 TTF 字体渲染 + 模板系统 + 实时投影，在游戏内生成文字招牌。

## 标识

| 项 | 值 |
|---|---|
| 包名 | `moe.hikari.canvas` |
| 命令前缀 | `/canvas` |
| 权限前缀 | `canvas.` |
| PDC namespace | `hikari_canvas` |
| 工程文件扩展名 | `.canvas` |
| 仓库 | https://github.com/HyacinthHaru/HikariCanvas（MIT） |

## 技术栈（锁定版本）

| 项 | 版本 |
|---|---|
| Java | **21**（不升 25，守住 1.21 LTS） |
| Paper API | **1.21.11**（`1.21.11-R0.1-SNAPSHOT`） |
| Gradle | **9.4.1** |
| `paperweight-userdev` | **2.0.0-beta.21**（官方唯一支持最新版） |
| PacketEvents | **2.11.2**（1.21.x 最终稳定版） |
| Javalin | **7.1.0**（6 已过时，不用） |
| 插件描述文件 | **`paper-plugin.yml`**（不用 `plugin.yml` 旧格式） |
| 本地测试服 | `./gradlew runServer`（paperweight-userdev 提供） |

其余：HikariCP + JDBI + SQLite、SnakeYAML、JUnit 5 + MockBukkit、AWT/Graphics2D。

**前端**：Vite + TypeScript；Vue 3 + Konva + Pinia 于 **M5 引入**（M1~M4 前端仅原生 DOM）。

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

## 架构纪律（26.x 升级保障，不可越界）

Paper 26.1 起移除插件的 Spigot 重映射，任何碰 NMS 的插件 26.x 必崩。为让未来升级只改版本号、不动代码：

1. **禁用 NMS。** 任何 `net.minecraft.*` / 服务端内部类一律禁止；只用公开 Bukkit API + PacketEvents
2. **PacketEvents 调用集中。** 所有 `sendPacket` 走 `plugin/deploy/MapPacketSender.java` 一个类，别的模块不直接碰 PacketEvents
3. **Mojang mappings 输出。** `paperweight-userdev` 默认行为，不开 reobf

见 PROPOSAL.md §5.2.6 完整说明。

## 其他不可越界的技术决策

- **预览地图池**是技术核心：编辑期间**只刷像素、不新建 MapView**，避免 `idcounts.dat` 膨胀——这一项做不好整个项目报废
- **双端渲染一致性**：浏览器 Canvas 与 Java Graphics2D 用同一 TTF 文件、禁抗锯齿
- **帧率策略**：静止 0fps · 输入防抖 100ms + 5fps 上限 · 提交全量
- **网络默认绑 `127.0.0.1`**；公网部署必须 nginx/Caddy 反代 + TLS
- **字体**：只打包 SIL OFL 协议字体；M4 内置 **Ark Pixel 12px**（仓库内 `resources/fonts/`，~200KB）+ **思源黑体 SC Regular**（Gradle `downloadFonts` 构建期抓到 `build/downloaded-fonts/` 再合并入 jar，`.gitignore` 排除，SHA-256 校验）。其他字体让用户自己放到 `plugins/HikariCanvas/fonts/`

## 里程碑

M0 立项 ✅ → M1 端到端验证 ✅（2026-04-20） → M2 会话与地图池 ✅（2026-04-21） → M3 实时投影 ✅（2026-04-21，方案 α：op 骨架 + hello_world 硬编码模板） → M4 渲染引擎 ✅（2026-04-22；竖排 + 像素字体最近邻缩放 + 前端 Playwright 推迟 M4.5/M7） → **M5 编辑器 UI（5w）** → M6 模板系统（1w）→ M7 打磨发布（2w）。总工期约 3.5 个月。
