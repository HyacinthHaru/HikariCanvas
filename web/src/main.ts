// HikariCanvas M1-T7a 前端探针：两个按钮（ping / paint），
// 都走同一条 WebSocket 连接，消息信封遵循 docs/protocol.md §2。
// 被插件 serve 时为同源（ws:// 同 host/port 切路径），
// 被 Vite dev server serve 时跨源连 127.0.0.1:8877（开发调试用）。

interface Envelope<P = unknown> {
    v: number;
    op: string;
    id?: string;
    ts?: number;
    payload?: P;
}

function resolveWsUrl(): string {
    // 同源条件：被 WebServer 自己 serve（M7 单 jar 模式），页面的 origin 就是插件端口
    // 其余情况（Vite dev 任意端口、file:// 直接打开 index.html）→ 跨源连插件
    const loc = window.location;
    const pluginHost = '127.0.0.1';
    const pluginPort = '8877';
    if (loc.hostname === pluginHost && loc.port === pluginPort) {
        const scheme = loc.protocol === 'https:' ? 'wss:' : 'ws:';
        return `${scheme}//${loc.host}/ws`;
    }
    return `ws://${pluginHost}:${pluginPort}/ws`;
}

const WS_URL = resolveWsUrl();

const logEl = document.getElementById('log');
const pingBtn = document.getElementById('ping-btn');
const paintBtn = document.getElementById('paint-btn');
if (
    !(logEl instanceof HTMLElement) ||
    !(pingBtn instanceof HTMLButtonElement) ||
    !(paintBtn instanceof HTMLButtonElement)
) {
    throw new Error('missing #log / #ping-btn / #paint-btn in DOM');
}

let ws: WebSocket | null = null;
let seq = 0;

function print(cls: 'sent' | 'recv' | 'err' | 'meta', msg: string): void {
    const span = document.createElement('span');
    span.className = cls;
    const t = new Date().toISOString().slice(11, 23);
    span.textContent = `[${t}] ${msg}\n`;
    logEl!.appendChild(span);
    logEl!.scrollTop = logEl!.scrollHeight;
}

function connect(): Promise<WebSocket> {
    if (ws && ws.readyState === WebSocket.OPEN) {
        return Promise.resolve(ws);
    }
    print('meta', `connecting to ${WS_URL} ...`);
    return new Promise((resolve, reject) => {
        const socket = new WebSocket(WS_URL);
        socket.addEventListener('open', () => {
            print('meta', 'open');
            ws = socket;
            resolve(socket);
        });
        socket.addEventListener('message', (ev: MessageEvent<string>) => {
            print('recv', `← ${ev.data}`);
        });
        socket.addEventListener('close', (ev: CloseEvent) => {
            print('meta', `close code=${ev.code}${ev.reason ? ` reason="${ev.reason}"` : ''}`);
            if (ws === socket) ws = null;
        });
        socket.addEventListener('error', () => {
            print('err', 'socket error');
            reject(new Error('WebSocket error (is the plugin running on port 8877?)'));
        });
    });
}

async function send(op: string): Promise<void> {
    const socket = await connect();
    const env: Envelope = {
        v: 1,
        op,
        id: `c-${seq++}`,
        ts: Date.now(),
    };
    const txt = JSON.stringify(env);
    print('sent', `→ ${txt}`);
    socket.send(txt);
}

pingBtn.addEventListener('click', async () => {
    pingBtn.disabled = true;
    try { await send('ping'); }
    catch (e) { print('err', e instanceof Error ? e.message : String(e)); }
    finally { pingBtn.disabled = false; }
});

paintBtn.addEventListener('click', async () => {
    paintBtn.disabled = true;
    try { await send('paint'); }
    catch (e) { print('err', e instanceof Error ? e.message : String(e)); }
    finally { paintBtn.disabled = false; }
});

print('meta', `ready — ws target = ${WS_URL}`);
