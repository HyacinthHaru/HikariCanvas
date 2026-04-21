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
    projectState: {
        version: number;
        canvas: { widthMaps: number; heightMaps: number; background: string };
        elements: unknown[];
        history: { undoDepth: number; redoDepth: number };
    };
}

const logRaw = document.getElementById('log');
const statusRaw = document.getElementById('status');
const pingRaw = document.getElementById('ping-btn');
const paintRaw = document.getElementById('paint-btn');
if (
    !(logRaw instanceof HTMLElement) ||
    !(statusRaw instanceof HTMLElement) ||
    !(pingRaw instanceof HTMLButtonElement) ||
    !(paintRaw instanceof HTMLButtonElement)
) {
    throw new Error('missing required DOM nodes');
}
const logEl: HTMLElement = logRaw;
const statusEl: HTMLElement = statusRaw;
const pingBtn: HTMLButtonElement = pingRaw;
const paintBtn: HTMLButtonElement = paintRaw;

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

function send(op: string): void {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
        print('err', 'socket not open');
        return;
    }
    const env: Envelope = { v: 1, op, id: `c-${seq++}`, ts: Date.now() };
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
    setStatus('ready',
        `Ready · session ${payload.sessionId.slice(0, 8)}… · wall ${w}×${h} · ` +
        `server ${payload.serverVersion} · protocol v${payload.protocolVersion}`);
    pingBtn!.disabled = false;
    paintBtn!.disabled = false;
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
        pingBtn!.disabled = true;
        paintBtn!.disabled = true;
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

// 页面加载入口
const token = new URLSearchParams(location.search).get('token');
print('meta', `ws target = ${resolveWsUrl()}${token ? ' (with token)' : ' (no token)'}`);
connect(token);
