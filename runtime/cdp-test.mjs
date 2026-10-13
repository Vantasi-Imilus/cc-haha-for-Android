import WebSocket from 'ws'
const list = await (await fetch('http://127.0.0.1:9229/json')).json()
const wsUrl = list[0].webSocketDebuggerUrl
console.log('target:', wsUrl)
const ws = new WebSocket(wsUrl)
let id = 0
const pending = new Map()
function call(method, params = {}) {
  return new Promise((resolve, reject) => {
    const mid = ++id
    pending.set(mid, { resolve, reject })
    ws.send(JSON.stringify({ id: mid, method, params }))
  })
}
ws.on('message', (data) => {
  const msg = JSON.parse(data.toString())
  if (msg.id && pending.has(msg.id)) {
    pending.get(msg.id).resolve(msg.result)
    pending.delete(msg.id)
  } else if (msg.method === 'Runtime.consoleAPICalled') {
    const args = (msg.params.args ?? []).map(a => a.value ?? a.description ?? '').join(' ')
    console.log('[CLI-console]', args.slice(0, 2000))
  }
})
await new Promise(r => ws.on('open', r))
await call('Runtime.enable')
await call('Debugger.enable')
// 注入 exit 拦截
const r = await call('Runtime.evaluate', {
  expression: `
    (function(){
      if (globalThis.__exitHooked) return 'already';
      globalThis.__exitHooked = true;
      const orig = process.exit;
      process.exit = function(code){
        console.error('=== EXIT CALLED code=' + code + ' ===');
        console.error(new Error('exit-stack').stack.split('\n').slice(0,12).join('\n'));
        return orig.apply(this, arguments);
      };
      const origExitCode = process.exitCode;
      return 'hooked';
    })()
  `,
  awaitPromise: true,
})
console.log('hook:', r.result?.value)
await call('Debugger.resume')
// 等 20 秒收集输出
await new Promise(r => setTimeout(r, 20000))
ws.close()
process.exit(0)
