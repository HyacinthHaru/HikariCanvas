// HikariCanvas dev probe：M2 阶段的端到端测试前端
// 流程：页面加载 → 从 URL 取 token → 连 WS → 发 auth → 等 ready → enable UI
// 消息信封契约：docs/protocol.md §2

interface Envelope<P = unknown> {
    v: number;
    op: string;
    id?: string;
    ts?: number;
    payload?: P;
}

interface ReadyPayload {
    sessionId: string;
    serverVersion: string;
    protocolVersion: number;
    // M3-T3：auth 成功后服务端 rotate 出的新 token，供断线重连使用
    reconnectToken: string;
    projectState: {
        version: number;
        canvas: { widthMaps: number; heightMaps: number; background: string };
        elements: unknown[];
        history: { undoDepth: number; redoDepth: number };
    };
}

// 存 reconnect token 用；sessionStorage 作用域 = 当前 tab，刷新保留、关闭即失效
const RECONNECT_TOKEN_KEY = 'hikari-canvas:reconnect-token';

const logRaw = document.getElementById('log');
const statusRaw = document.getElementById('status');
const pingRaw = document.getElementById('ping-btn');
const paintRaw = document.getElementById('paint-btn');
const helloRaw = document.getElementById('hello-btn');
const undoRaw = document.getElementById('undo-btn');
const redoRaw = document.getElementById('redo-btn');
if (
    !(logRaw instanceof HTMLElement) ||
    !(statusRaw instanceof HTMLElement) ||
    !(pingRaw instanceof HTMLButtonElement) ||
    !(paintRaw instanceof HTMLButtonElement) ||
    !(helloRaw instanceof HTMLButtonElement) ||
    !(undoRaw instanceof HTMLButtonElement) ||
    !(redoRaw instanceof HTMLButtonElement)
) {
    throw new Error('missing required DOM nodes');
}
const logEl: HTMLElement = logRaw;
const statusEl: HTMLElement = statusRaw;
const pingBtn: HTMLButtonElement = pingRaw;
const paintBtn: HTMLButtonElement = paintRaw;
const helloBtn: HTMLButtonElement = helloRaw;
const undoBtn: HTMLButtonElement = undoRaw;
const redoBtn: HTMLButtonElement = redoRaw;

function print(cls: 'sent' | 'recv' | 'err' | 'meta', msg: string): void {
    const span = document.createElement('span');
    span.className = cls;
    const t = new Date().toISOString().slice(11, 23);
    span.textContent = `[${t}] ${msg}\n`;
    logEl!.appendChild(span);
    logEl!.scrollTop = logEl!.scrollHeight;
}

function setStatus(cls: 'pending' | 'ready' | 'err', text: string): void {
    statusEl!.className = cls;
    statusEl!.textContent = text;
}

function resolveWsUrl(): string {
    // 同源（被 WebServer 自己 serve，port 8877）→ ws://host/ws
    // 跨源（Vite dev 或其它端口）→ 固定连 127.0.0.1:8877
    const loc = window.location;
    if (loc.hostname === '127.0.0.1' && loc.port === '8877') {
        const scheme = loc.protocol === 'https:' ? 'wss:' : 'ws:';
        return `${scheme}//${loc.host}/ws`;
    }
    return 'ws://127.0.0.1:8877/ws';
}

let ws: WebSocket | null = null;
let seq = 0;
let authenticated = false;
// 20s 应用层心跳：协议 §1 要求 30s；Jetty idleTimeout 60s；20s 间隔留两次容错窗口
const HEARTBEAT_INTERVAL_MS = 20_000;
let heartbeatTimer: number | null = null;

function stopHeartbeat(): void {
    if (heartbeatTimer !== null) {
        window.clearInterval(heartbeatTimer);
        heartbeatTimer = null;
    }
}

function startHeartbeat(): void {
    stopHeartbeat();
    heartbeatTimer = window.setInterval(() => {
        if (!ws || ws.readyState !== WebSocket.OPEN || !authenticated) return;
        // 直接写帧，不走 print/send 避免 log 刷屏
        const env: Envelope = { v: 1, op: 'ping', id: `c-hb-${seq++}`, ts: Date.now() };
        ws.send(JSON.stringify(env));
    }, HEARTBEAT_INTERVAL_MS);
}

function send(op: string, payload?: unknown): void {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
        print('err', 'socket not open');
        return;
    }
    const env: Envelope = { v: 1, op, id: `c-${seq++}`, ts: Date.now(), payload };
    const txt = JSON.stringify(env);
    print('sent', `→ ${txt}`);
    ws.send(txt);
}

function sendAuth(token: string): void {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    const env: Envelope = {
        v: 1,
        op: 'auth',
        id: 'c-auth',
        ts: Date.now(),
        payload: { token },
    };
    print('sent', `→ {"op":"auth","id":"c-auth", ...}`); // 不把 token 原文打进 log
    ws.send(JSON.stringify(env));
}

function handleReady(payload: ReadyPayload): void {
    authenticated = true;
    const w = payload.projectState.canvas.widthMaps;
    const h = payload.projectState.canvas.heightMaps;
    // M3-T3：存 reconnect token 到 sessionStorage；原文不进 console / log
    if (payload.reconnectToken) {
        try {
            sessionStorage.setItem(RECONNECT_TOKEN_KEY, payload.reconnectToken);
            print('meta', `reconnect token stored (len ${payload.reconnectToken.length})`);
        } catch {
            print('err', 'sessionStorage unavailable; reconnect disabled');
        }
    }
    setStatus('ready',
        `Ready · session ${payload.sessionId.slice(0, 8)}… · wall ${w}×${h} · ` +
        `server ${payload.serverVersion} · protocol v${payload.protocolVersion}`);
    pingBtn!.disabled = false;
    paintBtn!.disabled = false;
    helloBtn!.disabled = false;
    undoBtn!.disabled = false;
    redoBtn!.disabled = false;
    startHeartbeat();
}

function handleMessage(raw: string): void {
    let env: Envelope;
    try {
        env = JSON.parse(raw) as Envelope;
    } catch {
        print('err', 'malformed frame: ' + raw);
        return;
    }
    print('recv', `← ${raw}`);
    if (env.op === 'ready') {
        handleReady(env.payload as ReadyPayload);
        return;
    }
    if (env.op === 'error') {
        const code = (env.payload as { code?: string } | undefined)?.code;
        if (code === 'AUTH_FAILED') {
            setStatus('err', 'Authentication failed—token may have been used or expired.');
        }
    }
}

function connect(token: string | null): void {
    const url = resolveWsUrl();
    setStatus('pending', token ? `Connecting to ${url}…` : `No token; connecting unauth for demo…`);
    print('meta', `connecting to ${url}`);
    const socket = new WebSocket(url);

    socket.addEventListener('open', () => {
        print('meta', 'open');
        ws = socket;
        if (token) {
            setStatus('pending', 'Authenticating…');
            sendAuth(token);
        } else {
            // 没 token：M2 的 WS 强制 auth-first，会被服务器 close 4001
            // UI 展示一次，帮助用户理解
            setStatus('err', 'No token in URL. Start the editor via /canvas confirm in Minecraft.');
        }
    });
    socket.addEventListener('message', (ev: MessageEvent<string>) => handleMessage(ev.data));
    socket.addEventListener('close', (ev: CloseEvent) => {
        print('meta', `close code=${ev.code}${ev.reason ? ` reason="${ev.reason}"` : ''}`);
        if (ws === socket) ws = null;
        authenticated = false;
        stopHeartbeat();
        pingBtn!.disabled = true;
        paintBtn!.disabled = true;
        helloBtn!.disabled = true;
        undoBtn!.disabled = true;
        redoBtn!.disabled = true;
        if (ev.code === 4001) {
            setStatus('err', 'Session auth failed (WS close 4001).');
        } else if (statusEl!.className !== 'err') {
            setStatus('err', `Disconnected (code ${ev.code}).`);
        }
    });
    socket.addEventListener('error', () => {
        print('err', 'socket error');
    });
}

pingBtn.addEventListener('click', () => {
    if (!authenticated) return;
    send('ping');
});
paintBtn.addEventListener('click', () => {
    if (!authenticated) return;
    send('paint');
});
helloBtn.addEventListener('click', () => {
    if (!authenticated) return;
    send('template.apply', { templateId: 'hello_world' });
});
undoBtn.addEventListener('click', () => {
    if (!authenticated) return;
    send('undo', {});
});
redoBtn.addEventListener('click', () => {
    if (!authenticated) return;
    send('redo', {});
});

// 页面加载入口
// M3-T3：优先用 URL token（新会话 / 用户刚点聊天栏链接），回退到 sessionStorage
// （同 tab 刷新或重连时走这条）
const urlToken = new URLSearchParams(location.search).get('token');
const storedToken = (() => {
    try { return sessionStorage.getItem(RECONNECT_TOKEN_KEY); } catch { return null; }
})();
const token = urlToken ?? storedToken;
const tokenSource = urlToken ? 'url' : storedToken ? 'session-storage' : 'none';
print('meta', `ws target = ${resolveWsUrl()} (token source: ${tokenSource})`);
connect(token);

// 调试入口：浏览器 console 里用 `__hk.send("op", payload)` 发任意 WS 消息
// 方便手动触发 element.* / canvas.* / template.apply / undo / redo 等 op
// ws 是模块内 let 变量，这里暴露 getter 让 console 能看到最新连接状态
(window as unknown as Record<string, unknown>).__hk = {
    send,
    get ws(): WebSocket | null { return ws; },
    get authenticated(): boolean { return authenticated; },
};
