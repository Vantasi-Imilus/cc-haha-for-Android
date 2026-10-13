import http from 'node:http'
import { WebSocketServer } from 'ws'
import { Readable } from 'node:stream'

// 最小复现：upgrade 事件里 handleUpgrade
const wss = new WebSocketServer({ noServer: true })
const server = http.createServer((req, res) => { res.end('ok') })
server.on('upgrade', (req, socket, head) => {
  console.log('upgrade event, head:', head?.length, 'bytes')
  try {
    wss.handleUpgrade(req, socket, head, (ws) => {
      console.log('HANDLEUPGRADE OK')
      ws.on('message', d => console.log('msg', d.toString()))
    })
  } catch (e) {
    console.log('handleUpgrade THREW:', e.message)
  }
})
server.listen(3997, '127.0.0.1', async () => {
  const { default: WebSocket } = await import('ws')
  const ws = new WebSocket('ws://127.0.0.1:3997/ws/abc')
  ws.on('open', () => { console.log('CLIENT OPEN ✓'); ws.send('hello'); setTimeout(()=>process.exit(0), 500) })
  ws.on('error', e => console.log('CLIENT ERROR:', e.message))
})
