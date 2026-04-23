/**
 * 轻量级 i18n 字串表（中 / 英）。
 * 为避免引入 vue-i18n 的体积 + 运行时插件机制，这里只做最简单的嵌套对象查表。
 * Vue 组件里用法：
 *   const { t } = useI18n();
 *   t.topbar.toggleLeft
 *
 * 切换：ui store 的 locale toggle；localStorage 持久化。
 */

export const messages = {
    zh: {
        brand: 'HikariCanvas',
        topbar: {
            toggleLeft: '折叠/展开左侧工具栏',
            toggleRight: '折叠/展开右侧属性栏',
            toggleLog: '打开/关闭日志抽屉',
            toggleTheme: '切换主题',
            switchLocale: '切换到 English',
        },
        tools: {
            applyHello: '应用 hello_world 模板',
            addText: '添加文本',
            addRect: '添加矩形',
            undo: '撤销 (Ctrl+Z)',
            redo: '重做 (Ctrl+Shift+Z)',
            ping: 'Ping',
            paint: 'M1 demo：把所有地图涂红',
        },
        canvas: {
            sizeLabel: (cw: number, ch: number, pw: number, ph: number) =>
                `${cw}×${ch} 张地图 · ${pw}×${ph} 像素`,
            zoomIn: '放大 (Ctrl+=)',
            zoomOut: '缩小 (Ctrl+-)',
            zoomReset: '重置缩放 (Ctrl+0)',
            empty: '—',
        },
        properties: {
            header: '属性',
            empty: '未选中元素；在画布或图层中点击选择。',
            id: 'ID',
            type: '类型',
            transformHeader: '变换',
            rectHeader: '矩形',
            textHeader: '文本',
            effectsHeader: '效果',
            verticalHelp: '竖排',
            visible: '可见',
            locked: '锁定',
            fill: '填充',
            stroke: '描边',
            strokeWidth: '描边宽度',
            strokeColor: '描边颜色',
            fitContent: '自适应',
            fitHeight: '按内容调高',
            fitWidth: '按内容调宽',
        },
        layers: {
            header: '图层',
            count: (n: number) => `${n} 项`,
            empty: '画布为空',
            toggleVisible: (on: boolean) => (on ? '隐藏' : '显示'),
            toggleLock: (on: boolean) => (on ? '解锁' : '锁定'),
        },
        logdrawer: {
            header: 'WS 日志',
            clear: '清空',
            close: '关闭',
        },
        status: {
            ready: '就绪',
            connecting: '连接中',
            authenticating: '鉴权中',
            error: '错误',
            disconnected: '已断开',
            session: (id: string) => `会话 ${id.slice(0, 8)}…`,
            wall: (w: number, h: number) => `墙面 ${w}×${h}`,
            elements: (n: number, v: number) => `v${v} · ${n} 个元素`,
        },
    },
    en: {
        brand: 'HikariCanvas',
        topbar: {
            toggleLeft: 'Toggle left toolbar',
            toggleRight: 'Toggle right panel',
            toggleLog: 'Toggle log drawer',
            toggleTheme: 'Toggle theme',
            switchLocale: 'Switch to 中文',
        },
        tools: {
            applyHello: 'Apply hello_world template',
            addText: 'Add text',
            addRect: 'Add rectangle',
            undo: 'Undo (Ctrl+Z)',
            redo: 'Redo (Ctrl+Shift+Z)',
            ping: 'Ping',
            paint: 'M1 demo: paint all maps red',
        },
        canvas: {
            sizeLabel: (cw: number, ch: number, pw: number, ph: number) =>
                `${cw}×${ch} maps · ${pw}×${ph} px`,
            zoomIn: 'Zoom in (Ctrl+=)',
            zoomOut: 'Zoom out (Ctrl+-)',
            zoomReset: 'Reset zoom (Ctrl+0)',
            empty: '—',
        },
        properties: {
            header: 'Properties',
            empty: 'No element selected. Click an element on canvas or in Layers.',
            id: 'ID',
            type: 'Type',
            transformHeader: 'Transform',
            rectHeader: 'Rect',
            textHeader: 'Text',
            effectsHeader: 'Effects',
            verticalHelp: 'Vertical',
            visible: 'visible',
            locked: 'locked',
            fill: 'fill',
            stroke: 'stroke',
            strokeWidth: 'stroke.width',
            strokeColor: 'stroke.color',
            fitContent: 'Fit content',
            fitHeight: 'Fit height',
            fitWidth: 'Fit width',
        },
        layers: {
            header: 'Layers',
            count: (n: number) => `${n} items`,
            empty: 'Canvas is empty',
            toggleVisible: (on: boolean) => (on ? 'Hide' : 'Show'),
            toggleLock: (on: boolean) => (on ? 'Unlock' : 'Lock'),
        },
        logdrawer: {
            header: 'WS Log',
            clear: 'Clear',
            close: 'Close',
        },
        status: {
            ready: 'ready',
            connecting: 'connecting',
            authenticating: 'authenticating',
            error: 'error',
            disconnected: 'disconnected',
            session: (id: string) => `session ${id.slice(0, 8)}…`,
            wall: (w: number, h: number) => `wall ${w}×${h}`,
            elements: (n: number, v: number) => `v${v} · ${n} elements`,
        },
    },
} as const;

export type Messages = typeof messages['zh'];
