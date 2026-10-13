// 本地调试脚本：以 stdio stream-json 模式拉起 CLI 并喂一条消息。
// 用法（在 runtime/ 目录下）：node cli-stdin-local.mjs
// 测试用 HOME 可经 CC_HAHA_TEST_HOME 覆盖（默认系统临时目录）。
import { spawn } from 'node:child_process'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const testHome = process.env.CC_HAHA_TEST_HOME ?? join(tmpdir(), 'cc-haha-cli')
const env = {
  ...process.env,
  HOME: testHome,
  CLAUDE_CONFIG_DIR: join(testHome, '.claude'),
}
const runtimeDir = dirname(fileURLToPath(import.meta.url))
const args = [process.execPath, 'dist/cli-split/cli.js', '--print', '--verbose', '--input-format', 'stream-json', '--output-format', 'stream-json', '--include-partial-messages']
const c = spawn(args[0], args.slice(1), { env, cwd: runtimeDir, stdio: ['pipe', 'pipe', 'pipe'] })
console.log('spawned', c.pid)
let got = ''
c.stdout.on('data', d => { got += d.toString(); process.stdout.write(d.toString().slice(0, 120)) })
c.stderr.on('data', d => console.log('ERR:', d.toString().slice(0, 200)))
c.on('exit', code => console.log('\nEXIT:', code, '总输出', got.length, '字节'))
setTimeout(() => { console.log('--- 喂输入 ---'); c.stdin.ref(); c.stdin.write(JSON.stringify({ type: 'user', message: { role: 'user', content: [{ type: 'text', text: '回复两个字：成功' }] } }) + '\n') }, 20000)
setTimeout(() => { console.log('\n=== 90 秒结束 ==='); c.kill('SIGKILL'); process.exit(0) }, 90000)
