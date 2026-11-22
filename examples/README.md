# HikariCanvas Push API 示例插件

本目录包含两个独立的 Paper 插件子项目，演示如何通过 `ac.haru.hikaricanvas.api.HikariCanvasAPI` 从外部插件向招牌推送实时数据。

## 1. `DemoTrainPlugin` —— 定时批量推送示例

模拟列车信息系统：每 5 秒自动推送两条线路的下一班发车时间、终点站与剩余分钟数，适合轮询数据库、拉取外部接口或周期性计算的场景。

提供的变量：
- `${var:demo_train/line1.next_departure}` —— 1 号线下一班出发时间（`HH:mm`）
- `${var:demo_train/line1.next_destination}` —— 1 号线下一班终点站
- `${var:demo_train/line1.eta_minutes}` —— 1 号线距离发车剩余分钟数
- `${var:demo_train/line2.next_departure}` / `line2.next_destination` / `line2.eta_minutes` —— 2 号线对应变量

## 2. `DemoScorePlugin` —— 事件与命令驱动示例

模拟小游戏计分板：玩家进服时自动更新全场最佳（MVP）名字，并可通过命令实时加减红蓝两队比分，适合监听游戏事件实时刷新的场景。

提供的变量：
- `${var:demo_score/red}` —— 红队当前比分
- `${var:demo_score/blue}` —— 蓝队当前比分
- `${var:demo_score/mvp}` —— 当前 MVP 玩家名

测试命令：

```text
/demoscore add <red|blue> <分数>    # 累加指定队伍比分
/demoscore set <red|blue> <分数>    # 直接设置指定队伍比分
/demoscore reset                    # 红蓝两队比分同时归零
```

## 编译与安装

在仓库根目录执行：

```bash
./gradlew :examples:demo-train-plugin:jar :examples:demo-score-plugin:jar
```

生成的插件包位于 `examples/demo-train-plugin/build/libs/` 与 `examples/demo-score-plugin/build/libs/`。

测试时将 **PacketEvents**、`HikariCanvas-<版本>.jar` 以及上述两个示例 jar 一并放入 Paper 服务器的 `plugins/` 目录启动即可（两个示例均已在 `paper-plugin.yml` 中声明在 HikariCanvas 之后加载）。进服建好画板后，在网页编辑器的文本输入框里输入 `${`，就能在变量选择器里直接看到 `demo_train` 和 `demo_score` 分组。

完整的接口说明与接入规范见 [外部插件接入 API](../docs/api.md)。
