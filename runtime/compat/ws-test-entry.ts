import { installWebSocket } from './web-socket.js'
installWebSocket()
const url = process.argv[2] ?? 'ws://127.0.0.1:3456/sdk/fake-token-test'
console.log('[ws-test] 连接', url)
const ws = new WebSocket(url, { headers: {}, proxy: undefined, tls: undefined } as never)
;(ws as any).onopen = () => { console.log('[ws-test] OPEN OK'); process.exit(0) }
;(ws as any).onerror = (e: any) => { console.log('[ws-test] ERROR:', e?.message ?? e); }
;(ws as any).onclose = (e: any) => { console.log('[ws-test] CLOSE code=', e?.code); process.exit(1) }
setTimeout(() => { console.log('[ws-test] TIMEOUT readyState=', (ws as any).readyState); process.exit(2) }, 8000)
