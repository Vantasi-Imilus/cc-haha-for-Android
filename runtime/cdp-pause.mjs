import WebSocket from 'ws'
const list = await (await fetch('http://127.0.0.1:9229/json')).json()
console.log('targets:', list.length)
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
    const frames = (msg.params.callFrames ?? []).slice(0, 12)
    console.log('=== PAUSED, 栈: ===')
    for (const f of frames) {
      const loc = f.location
      console.log(`  ${f.functionName || '(anon)'} @ ${f.url.split('/').pop()}:${loc.lineNumber}`)
    }
    console.log('reason:', msg.params.reason)
    ws.close()
    process.exit(0)
  }
})
await new Promise(r => ws.on('open', r))
await call('Debugger.enable')
await call('Runtime.runIfWaitingForDebugger')
await new Promise(r => setTimeout(r, 3000))
console.log('pause...')
await call('Debugger.pause')
await new Promise(r => setTimeout(r, 5000))
console.log('no pause event')
process.exit(1)
