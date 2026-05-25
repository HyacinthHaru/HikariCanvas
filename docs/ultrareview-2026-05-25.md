# Ultrareview 2026-05-25

> **范围**：HikariCanvas 全栈代码（Java 插件 + Vue 前端 + 契约文档）
> **方法**：只读静态审查；结合主线程人工审查与多个子代理结果；未写入代码、未构建、未运行测试
> **筛选标准**：仅记录可能导致实际错误、安全边界绕过、数据/显示状态不一致、协议契约不一致的硬 Bug 与边界 Bug

## 严重度分布

| 严重度 | 数量 | 主要类型 |
|---|---:|---|
| P0 / P1 | 8 | 权限绕过、动态显示失效、最终帧丢失、多图层/透明渲染错误、locked readonly 失效 |
| P2 | 13 | 协议字段丢失、变量 TTL/namespace/配额边界、前端镜像与本地状态漂移 |
| P3 | 2 | 文档契约与运行标识不一致、低概率输入边界 |

---

## P0 / P1

### 1. 无活跃 editor session 的动态变量 wall 不会重绘

- **位置**：`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java:287`、`plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java:735`、`plugin/src/main/java/moe/hikari/canvas/variable/VariableStore.java:819`
- **问题**：变量 dirty 回调最终只遍历 `SessionManager.byId` 中的活跃 session。wall 引用了变量但编辑器已关闭时，没有活跃 session 可被提交 dirty。
- **可能结果**：外部 API/provider/userglobal 更新变量后，已部署在游戏内的地图仍显示旧值；显示内容只会在重新打开编辑器、重启恢复或其他路径触发全量渲染后才更新。
- **契约依据**：`docs/dynamic-data.md:244-248` 描述 API push 后应查引用 wall 并 mark dirty；`docs/architecture.md:897-903` 描述 lock 后模板冻结但显示内容仍读最新动态值。

### 2. locked wall 可能被 `/canvas edit` + confirm existing wall 路径绕过

- **位置**：`plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java:275`、`plugin/src/main/java/moe/hikari/canvas/command/CanvasCommand.java:547`
- **问题**：`/canvas open` 路径包含 locked wall 的 owner/bypass 校验；`/canvas edit` 后对已有 HikariCanvas wall 的 confirm/bind 路径存在不经过同等 lock 校验的分支。
- **可能结果**：非 owner 在没有 `canvas.admin.bypass-lock` 的情况下，可能通过重新选区确认的方式获得 locked wall 的编辑 token。
- **契约依据**：lock 状态设计要求非 owner 不能解锁或编辑 locked wall；`docs/security.md` 定义了 `canvas.admin.bypass-lock` 的特殊权限边界。

### 3. WS auth 没有重新校验 `canvas.edit`

- **位置**：`plugin/src/main/java/moe/hikari/canvas/web/WebServer.java:812`
- **问题**：WebSocket auth 校验 token/session/IP 后进入 ready 流程，没有在 auth 时重新检查玩家当前是否仍拥有 `canvas.edit`。
- **可能结果**：玩家在 token 签发后被撤销权限，仍可能用已签发 token 完成认证或重连，继续进入编辑器。
- **契约依据**：`docs/security.md` 对 WS auth 成功后的权限重校验有明确要求，用于防止权限中途撤销后继续编辑。

### 4. 已持有 Canvas Wand 的玩家在权限撤销后仍可能交互

- **位置**：`plugin/src/main/java/moe/hikari/canvas/session/WandListener.java:230`
- **问题**：wand 交互入口主要检查物品 PDC/owner 与选择状态，没有在交互时重新检查 `canvas.edit`。
- **可能结果**：玩家曾经合法获得 wand 后，即使后续被撤销 `canvas.edit`，仍可能继续用 wand 选择区域或触发已有 wall 打开流程。
- **契约依据**：`docs/security.md` 将持 wand 交互归入 `canvas.edit` 权限控制范围。

### 5. session 结束时 pending 投影尾帧会被丢弃

- **位置**：`plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java:617`、`plugin/src/main/java/moe/hikari/canvas/render/ProjectionThrottler.java:154`、`plugin/src/main/java/moe/hikari/canvas/render/ProjectionThrottler.java:164`
- **问题**：`cancel` 先把 session 从管理表移除，再执行 forget hook；`ProjectionThrottler.discardSession` 会取消任务并清空 pending region。`flushLocked` 在 session 已移除时也会直接丢弃。
- **可能结果**：最后一次编辑落入 100ms/5fps 节流窗口后，如果用户断线、cancel 或被 reaper 回收，游戏内地图可能停留在倒数第二帧。
- **契约依据**：`docs/architecture.md:513-515` 要求 session 关闭前执行一次完整推送，确保最终帧正确。

### 6. 多图层工程会因 active layer 为空被误判为 pristine

- **位置**：`plugin/src/main/java/moe/hikari/canvas/render/CanvasProjector.java:211`、`plugin/src/main/java/moe/hikari/canvas/render/WallRestorer.java:176`、`plugin/src/main/java/moe/hikari/canvas/state/ProjectState.java:194`
- **问题**：`isPristine` 使用 `state.elements()` 判断是否空；M8 后该方法只是 active layer 的兼容视图，不代表所有 layer。
- **可能结果**：active layer 为空、其他可见 layer 有内容且背景为白色时，投影和启动恢复会把真实内容替换成 placeholder。
- **契约依据**：`docs/architecture.md:740` 定义 v2 ProjectState 为 `layers[]`；`docs/rendering.md:163-167` 定义渲染应遍历所有 visible layer。

### 7. 透明背景在 slow layer path 下会被强制不透明

- **位置**：`plugin/src/main/java/moe/hikari/canvas/render/CanvasCompositor.java:174`、`plugin/src/main/java/moe/hikari/canvas/render/BlendModes.java:29`、`web/src/render/BlendModes.ts:43`
- **问题**：主 buffer 已改为 ARGB 以支持透明背景，但 Java/TS 的 blend mode 合成仍按“不透明 RGB 主 buffer”处理，并把输出 alpha 写成 255。
- **可能结果**：透明或半透明背景遇到 layer opacity、非 normal blendMode、element opacity、dither 等 slow path 时，透明像素会变为不透明像素；前端预览与游戏内地图也可能一起错误。
- **契约依据**：`docs/rendering.md:161` 要求主画布为 ARGB；`docs/rendering.md:341-342` 要求 alpha `< 128` 的像素映射为地图透明色。

### 8. 前端 locked wall 的 readonly 执行不完整

- **位置**：`web/src/components/layout/LeftTools.vue:20`、`web/src/components/template/TemplateGallery.vue:131`、`web/src/components/layout/CanvasView.vue:200`、`web/src/components/canvas/CanvasZoomBar.vue:115`
- **问题**：lock 状态的后端编辑 op 按设计不拦截，readonly 依赖前端执行；部分入口在 readonly overlay/RightPanel 之外，仍可能发送 `element.add`、`template.apply`、`undo`、`redo`、`canvas.grid` 等 ProjectState mutation。
- **可能结果**：用户看到 wall 已锁定，但仍能通过左侧工具、模板应用、撤销重做或网格控件改变工程状态。
- **契约依据**：lock-state 设计要求后端编辑路径不读 lock，前端是 lock 的唯一执行者。

---

## P2

### 9. locked layer 下部分前端编辑会产生本地假状态

- **位置**：`web/src/components/layout/RightPanel.vue:56`、`web/src/App.vue:135`、`web/src/components/layout/CanvasView.vue:400`
- **问题**：locked layer 内 element op 会被服务端拒绝，但右侧属性栏、快捷键、inline text 等路径存在先 optimistic mutate 本地状态再发送请求的行为。
- **可能结果**：服务端保持原状态，前端预览却显示已修改内容；刷新或重新打开后本地显示回退，引发前后端状态漂移。
- **契约依据**：`docs/protocol.md:202` 与 `docs/architecture.md:811-814` 要求 locked layer 内 element add/update/delete/reorder/transform 拒绝。

### 10. Paint Bucket 拓扑只使用 active layer

- **位置**：`web/src/components/layout/CanvasView.vue:181`、`web/src/components/layout/CanvasView.vue:565`、`web/src/stores/project.ts:150`
- **问题**：Live Paint 的 `visibleElements` 来自 `project.state.elements`；该字段在 M8 后只指向 active layer。worker graph 与 `findElementAt` 都忽略其他 visible layer。
- **可能结果**：多图层工程中，油漆桶会把其他层的可见元素当作不存在；可能在被其他层占用的位置创建填充，或无法 recolor 实际命中的顶层元素。
- **契约依据**：`docs/architecture.md:1011` 描述 Live Paint 应基于 visible layers sorted；`docs/rendering.md:163-167` 定义所有 visible layer 参与渲染。

### 11. `element.locked` 在前端执行不一致

- **位置**：`web/src/components/layout/CanvasView.vue:337`、`web/src/composables/useTransformerManager.ts:44`、`web/src/components/layout/RightPanel.vue:56`、`web/src/App.vue:97`
- **问题**：画布拖拽与油漆桶部分路径尊重 `element.locked`，但 Transformer、属性栏、删除/方向键快捷键、多选 follower 等路径存在不一致。
- **可能结果**：用户锁定单个 element 后，该 element 仍可能通过部分交互被缩放、旋转、删除、移动或改属性。
- **契约依据**：协议 BaseElement 定义了 `locked` 字段，前端 UI 也暴露了元素锁定状态。

### 12. `element.add` 会丢弃多数类型的 v2 通用视觉字段

- **位置**：`plugin/src/main/java/moe/hikari/canvas/state/EditSession.java:671`、`plugin/src/main/java/moe/hikari/canvas/state/EditSession.java:691`、`plugin/src/main/java/moe/hikari/canvas/state/EditSession.java:714`、`plugin/src/main/java/moe/hikari/canvas/state/EditSession.java:777`、`plugin/src/main/java/moe/hikari/canvas/state/EditSession.java:844`、`plugin/src/main/java/moe/hikari/canvas/state/EditSession.java:1185`
- **问题**：text/rect/path/circle/shape/image 的构造路径将 `opacity`、`blendMode`、`renderMode` 传为 `null`；icon 构造路径会解析这些字段。
- **可能结果**：客户端创建非 icon 元素时携带的 opacity/blendMode/renderMode 被服务端权威状态丢弃，新增元素视觉与客户端请求不一致。
- **契约依据**：`docs/protocol.md:440-452` 将这些字段定义为 BaseElement 的 v2 字段；`docs/architecture.md:768-772` 同步定义了元素级 opacity/blendMode/renderMode。

### 13. `shape.innerRatio` 非数字会抛运行时异常

- **位置**：`plugin/src/main/java/moe/hikari/canvas/state/EditSession.java:833`
- **问题**：`innerRatio` 读取时直接把 raw value cast 为 `Number`，非数字类型会触发 `ClassCastException`。
- **可能结果**：恶意或错误客户端发送字符串/对象类型的 `innerRatio` 时，该错误不会按普通 payload validation 路径返回协议错误，可能表现为 WS op 异常或日志噪声。
- **契约依据**：`docs/protocol.md` 的 op 错误模型要求非法 payload 返回协议错误，而不是未捕获运行时异常。

### 14. brush point 接受非法 pressure 和极大坐标

- **位置**：`plugin/src/main/java/moe/hikari/canvas/web/WebHelpers.java:55`、`plugin/src/main/java/moe/hikari/canvas/state/BrushSession.java:187`、`plugin/src/main/java/moe/hikari/canvas/state/BrushSession.java:230`
- **问题**：brush point 解析只检查 finite；pressure 范围、画布坐标范围和最终 bbox 约束存在缺口。
- **可能结果**：客户端可提交 pressure `<0` 或 `>1`、极大但 finite 的坐标，导致持久化 stroke 几何异常、dirty region 过大、渲染宽度/透明度异常。
- **契约依据**：`docs/protocol.md:281` 定义 pressure 为 `0..1`；`BrushPoint` 代码注释也声明 pressure 为 `[0,1]`。

### 15. Layer 反序列化可接受非法 opacity

- **位置**：`plugin/src/main/java/moe/hikari/canvas/state/Layer.java:56`、`plugin/src/main/java/moe/hikari/canvas/state/LayerOperations.java:164`
- **问题**：layer update 路径校验 opacity 范围，但 `Layer.fromJson`/Jackson 创建路径只处理默认值，不校验 finite 与 `0..1` 范围。
- **可能结果**：导入或数据库中存在非法 layer opacity 时，渲染可能出现层隐藏、过度混合或与前端预期不一致。
- **契约依据**：`docs/protocol.md:424` 与 `docs/architecture.md:763` 定义 layer opacity 为 `0..1`。

### 16. 前端变量镜像忽略 `/variables/<encoded>` 整节点 replace

- **位置**：`web/src/network/wsClient.ts:750`、`plugin/src/main/java/moe/hikari/canvas/state/EditSession.java:1393`、`plugin/src/main/java/moe/hikari/canvas/session/SessionManager.java:905`
- **问题**：前端处理变量 patch 时覆盖 add/remove/currentValue/subpath replace，但缺少 `replace /variables/<encoded>` 整节点的处理。
- **可能结果**：变量 type、defaultValue、source、owner 等整节点更新到达前端后，VariablePanel 或变量 chip 仍显示旧元数据。
- **契约依据**：后端实际会对变量更新和 Provider UPDATED 事件发送整节点 replace。

### 17. alias-only state.patch 会把前端 ProjectState.version 置为 0

- **位置**：`plugin/src/main/java/moe/hikari/canvas/web/VariableAliasDispatcher.java:167`、`plugin/src/main/java/moe/hikari/canvas/web/VariableAliasDispatcher.java:196`、`web/src/network/wsClient.ts:580`、`web/src/stores/project.ts:85`
- **问题**：alias mirror patch 使用 `StatePatch(0L, ...)`；前端即使 project ops 为空，也会调用 `project.applyPatch(payload.version, projectOps)` 并设置 `state.version = 0`。
- **可能结果**：设置或清除变量别名后，前端 ProjectState version 回退到 0，后续基于 version 的显示、调试或冲突判断可能失真。
- **契约依据**：变量 alias mirror 不属于 ProjectState 像素层；相关代码注释也说明 alias 不应影响 ProjectState。

### 18. 变量 TTL 过期逻辑未在渲染读取时生效

- **位置**：`plugin/src/main/java/moe/hikari/canvas/variable/VariableInterpolator.java:205`、`plugin/src/main/java/moe/hikari/canvas/variable/Variable.java:54`
- **问题**：变量 resolver 只要 `currentValue` 非空就直接返回，没有检查 `isStale(now)`。
- **可能结果**：带 TTL 的 provider/API 变量停止刷新后，过期值仍可长期显示在 wall 上。
- **契约依据**：`docs/api.md` 与 `docs/dynamic-data.md` 描述 TTL 过期后 cached value 应失效并回落到 fallback/default。

### 19. Plugin Push API 首次 `ttl=null` 创建变量时会得到永久 TTL

- **位置**：`plugin/src/main/java/moe/hikari/canvas/variable/plugin/HikariCanvasAPIImpl.java:57`、`plugin/src/main/java/moe/hikari/canvas/variable/plugin/HikariCanvasAPIImpl.java:290`、`plugin/src/main/java/moe/hikari/canvas/variable/VariableStore.java:435`
- **问题**：外部插件首次 set variable 且 ttl 为 null 时，自动创建的变量没有使用默认 TTL，而是进入 `ttl=0` 的永久语义。
- **可能结果**：没有声明 TTL 的插件变量不会按默认 30 秒过期，旧业务值可能长期显示。
- **契约依据**：`docs/dynamic-data.md` 描述 Tier 2 未声明 TTL 默认 30 秒。

### 20. 插件 disable 延迟 purge 可能删除 reload 后的新变量

- **位置**：`plugin/src/main/java/moe/hikari/canvas/variable/plugin/PluginCleanupListener.java:142`、`plugin/src/main/java/moe/hikari/canvas/variable/plugin/PluginNamespaceRegistry.java:126`、`plugin/src/main/java/moe/hikari/canvas/variable/plugin/HikariCanvasAPIImpl.java:247`
- **问题**：plugin disable 后 namespace 被释放，延迟 purge 到点时只按 namespace 删除变量，没有区分该 namespace 是否已被 reload 后的同插件或新插件重新注册并写入新值。
- **可能结果**：插件热重载或 namespace 快速复用时，新写入的变量可能被旧 disable 任务删除。
- **契约依据**：`docs/api.md` 描述 30 秒 grace 用于 reload 场景下保留 cached value。

### 21. `ttl < 100ms` 的处理与文档语义不一致

- **位置**：`plugin/src/main/java/moe/hikari/canvas/variable/VariableStore.java:895`、`plugin/src/main/java/moe/hikari/canvas/variable/plugin/HikariCanvasAPIImpl.java:154`
- **问题**：文档描述过短 TTL 会被 clamp 到 100ms；实际 store validation 会抛 `TTL_INVALID`，API catch 后记录并丢弃更新。
- **可能结果**：外部插件传入 1-99ms TTL 时，调用方可能认为变量已更新，但 HikariCanvas 内部没有应用该值。
- **契约依据**：`docs/api.md` 对 TTL 最小值和 clamp 行为有说明。

### 22. RailScheduleProvider 暴露内部 daemon namespace

- **位置**：`plugin/src/main/java/moe/hikari/canvas/variable/provider/RailScheduleProvider.java:70`、`plugin/src/main/java/moe/hikari/canvas/variable/provider/RailScheduleProvider.java:135`、`plugin/src/main/java/moe/hikari/canvas/web/VariableMetadataHandler.java:166`
- **问题**：`schedule_rail` 注释为 daemon 内部 namespace，但 metadata 下发时可暴露给前端变量选择器。
- **可能结果**：前端可能插入 `schedule_rail/key`，而实际 per-wall schedule 变量解析路径不匹配，最终渲染 miss 或显示 fallback。
- **契约依据**：`docs/variables.md` 描述 schedule per-wall namespace 为 `schedule:<wallId>`，Picker 展示语义为 schedule。

### 23. userglobal 配额检查存在并发越限窗口

- **位置**：`plugin/src/main/java/moe/hikari/canvas/variable/VariableStore.java:300`、`plugin/src/main/java/moe/hikari/canvas/variable/VariableStore.java:314`
- **问题**：`countByOwner/countTotal` 与后续创建/持久化不在同一原子边界内。
- **可能结果**：多个并发请求在接近配额上限时可能同时通过计数检查，导致实际 userglobal 数量超过 per-owner 或 total 上限。
- **契约依据**：`docs/dynamic-data.md` 与 `docs/variables.md` 描述了 userglobal 的数量上限。

---

## P3

### 24. 调试 `paint` op 仍存在且会影响所有活跃 session

- **位置**：`plugin/src/main/java/moe/hikari/canvas/web/WebServer.java:697`、`plugin/src/main/java/moe/hikari/canvas/HikariCanvas.java:647`
- **问题**：协议文档没有列出 `paint` op；代码中仍有处理分支，可遍历 live session 并写入地图像素。
- **可能结果**：任意已认证客户端若发送该 op，可能影响多个 wall/session 的地图显示。
- **契约依据**：`docs/protocol.md` 的正式 op 清单不包含 `paint`。

### 25. PDC namespace 与文档固定值存在不一致风险

- **位置**：`plugin/src/main/java/moe/hikari/canvas/deploy/FrameDeployer.java:50`、`plugin/src/main/java/moe/hikari/canvas/deploy/WallResolver.java:35`、`plugin/src/main/java/moe/hikari/canvas/deploy/CanvasWand.java:44`
- **问题**：文档固定 PDC namespace 为 `hikari_canvas`；代码使用 `new NamespacedKey(plugin, "...")`，namespace 取决于插件名归一化结果。
- **可能结果**：外部工具、迁移脚本或后续代码若按文档固定 namespace 写入/读取 PDC，可能无法被当前运行时代码识别。
- **契约依据**：项目标识中固定 `PDC namespace = hikari_canvas`；`docs/data-model.md` 也按该 namespace 描述 PDC 标记。

