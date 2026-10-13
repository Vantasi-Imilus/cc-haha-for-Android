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
  else if (msg.method === 'Runtime.consoleAPICalled') {
    const args = (msg.params.args ?? []).map(a => a.value ?? a.description ?? '').join(' ')
    console.log('[CLI-console]', args.slice(0, 1500))
  }
})
await new Promise(r => ws.on('open', r))
await call('Runtime.enable')
const NL = String.fromCharCode(10)
const expr = [
  '(function(){',
  'const orig = process.exit.bind(process);',
  'process.exit = function(code){',
  'console.error("=== EXIT CALLED code=" + code + " ===");',
  'const st = new Error("exit-stack").stack.split(' + JSON.stringify(NL) + ').slice(0,14).join(' + JSON.stringify(NL) + ');',
  'console.error(st);',
  'return orig(code);',
  '};',
  'return "hooked";',
  '})()',
].join('')
const r = await call('Runtime.evaluate', { expression: expr })
console.log('eval:', JSON.stringify(r.result?.result?.value ?? r.result).slice(0, 120))
await call('Debugger.resume')
await new Promise(r2 => setTimeout(r2, 25000))
ws.close()
process.exit(0)
