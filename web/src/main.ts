// HikariCanvas M1-T6 前端探针：一个按钮，连 ws://127.0.0.1:8877/ws，发 ping，
// 把收到的响应打到页面 log 区域。消息信封遵循 docs/protocol.md §2。

const WS_URL = 'ws://127.0.0.1:8877/ws';

interface Envelope<P = unknown> {
    v: number;
    op: string;
    id?: string;
    ts?: number;
    payload?: P;
}

const logEl = document.getElementById('log');
const btn = document.getElementById('ping-btn');
if (!(logEl instanceof HTMLElement) || !(btn instanceof HTMLButtonElement)) {
    throw new Error('missing #log or #ping-btn in DOM');
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

btn.addEventListener('click', async () => {
    btn.disabled = true;
    try {
        const socket = await connect();
        const env: Envelope = {
            v: 1,
            op: 'ping',
            id: `c-${seq++}`,
            ts: Date.now(),
        };
        const txt = JSON.stringify(env);
        print('sent', `→ ${txt}`);
        socket.send(txt);
    } catch (e) {
        print('err', e instanceof Error ? e.message : String(e));
    } finally {
        btn.disabled = false;
    }
});

print('meta', 'ready — click the button to probe the plugin');
