import { WebSocket as WsWebSocket } from 'ws'
// 模拟 CLI（Bun 分支）：headers + proxy(undefined) + tls(undefined)
const opts = { headers: {} }
const url = 'ws://127.0.0.1:3456/sdk/test-token-xyz'
console.log('连接', url)
const ws = new WsWebSocket(url, opts)
ws.on('open', () => { console.log('OPEN ✓'); process.exit(0) })
ws.on('unexpected-response', (req, res) => { console.log('UNEXPECTED:', res.statusCode); process.exit(0) })
ws.on('error', e => { console.log('ERROR:', e.message); process.exit(0) })
setTimeout(() => { console.log('TIMEOUT readyState=', ws.readyState); process.exit(0) }, 6000)
