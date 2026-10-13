import WebSocket from 'ws'
const list = await (await fetch('http://127.0.0.1:9229/json')).json()
const ws = new WebSocket(list[0].webSocketDebuggerUrl)
let id = 0
const pending = new Map()
function call(method, params = {}) {
  return new Promise((resolve) => {
    const mid = ++id
    pending.set(mid, resolve)
    ws.send(JSON.stringify({ id: mid, method, params }))
  })
}
ws.on('message', (data) => {
  const msg = JSON.parse(data.toString())
  if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id) }
  else if (msg.method === 'Debugger.paused') {
    const frames = (msg.params.callFrames ?? []).slice(0, 14)
    console.log('=== PAUSED ===')
    for (const f of frames) {
      const loc = f.location
      const fn = f.functionName || '(anon)'
      console.log(`  ${fn}\n    url=${loc.url}\n    line=${loc.lineNumber}`)
    }
    ws.close()
    process.exit(0)
  }
})
await new Promise(r => ws.on('open', r))
await call('Debugger.enable')
await call('Debugger.pause')
await new Promise(r => setTimeout(r, 5000))
process.exit(1)
