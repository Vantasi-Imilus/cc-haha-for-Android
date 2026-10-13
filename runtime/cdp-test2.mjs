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
  } else if (msg.method === 'Runtime.exceptionThrown') {
    console.log('[CLI-exception]', JSON.stringify(msg.params.exceptionDetails).slice(0, 500))
  }
})
await new Promise(r => ws.on('open', r))
await call('Runtime.enable')
const r = await call('Runtime.evaluate', {
  expression: `
    (function(){
      const orig = process.exit.bind(process);
      process.exit = function(code){
        console.error('=== EXIT CALLED code=' + code + ' ===');
        console.error(new Error('exit-stack').stack.split('\n').slice(0,15).join('\n'));
        return orig(code);
      };
      return 'hooked';
    })()
  `,
})
console.log('eval result:', JSON.stringify(r.result).slice(0, 300))
console.log('exception:', JSON.stringify(r.result?.exceptionDetails).slice(0, 300))
await call('Debugger.resume')
await new Promise(r2 => setTimeout(r2, 25000))
ws.close()
process.exit(0)
