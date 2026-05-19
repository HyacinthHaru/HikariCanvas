# HikariCanvas Push API 示例插件

两个独立的 Paper 插件 subproject，演示 `moe.hikari.canvas.api.HikariCanvasAPI` 的两种典型触发方式。

## DemoTrainPlugin — 定时器 push 范型

模拟列车时刻表：每 5 秒推送两条线路的 `next_departure` / `next_destination` / `eta_minutes`。
范型适用于：周期性数据源（DB 轮询 / HTTP 拉取 / 自家计算）。

可用变量：

- `${var:demo_train/line1.next_departure}` — 1 号线下一班车出发时间 `HH:mm`
- `${var:demo_train/line1.next_destination}` — 1 号线下一班车终点
- `${var:demo_train/line1.eta_minutes}` — 1 号线距下一班车分钟
- `${var:demo_train/line2.next_departure}` / `line2.next_destination` / `line2.eta_minutes` — 2 号线同上

## DemoScorePlugin — 事件 + 命令 push 范型

模拟 PvP 比分：`PlayerJoinEvent` 把进服玩家设为 MVP；`/demoscore add red 1` 等命令累加比分。
范型适用于：事件驱动数据源（Bukkit / 第三方插件事件）+ 玩家 / 管理员主动触发。

可用变量：

- `${var:demo_score/red}` — 红队当前比分
- `${var:demo_score/blue}` — 蓝队当前比分
- `${var:demo_score/mvp}` — MVP 玩家名

命令：

```
/demoscore add <red|blue> <n>     # 累加比分
/demoscore set <red|blue> <n>     # 直接设置比分
/demoscore reset                  # 红蓝同时清零
```

## 编译

```bash
./gradlew :examples:demo-train-plugin:jar :examples:demo-score-plugin:jar
```

输出在 `examples/demo-{train,score}-plugin/build/libs/`。

## 安装

把 `HikariCanvas-0.3.0-SNAPSHOT.jar` + `DemoTrainPlugin.jar` + `DemoScorePlugin.jar` 一起放入
Paper 服务器的 `plugins/` 目录即可。两个 demo 都在 `paper-plugin.yml` 声明 HikariCanvas 为
`required: true + load: BEFORE`，启动顺序由 Paper plugin loader 保证。

## 在 wall 上引用

编辑器 TextElement 的文本框内输入 `${` 自动弹出 Variable Picker；选择
`demo_train` / `demo_score` namespace 下任意 key 即可插入占位符。

详细 API 用法见 `docs/api.md`（P4-S 产物）。
