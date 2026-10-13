/**
 * 全局 Bun 对象的 Node 实现。
 *
 * 提供面依据对上游调用点的逐处审计（compat 调研报告）：
 *  必须提供（无守卫或 pattern-1 守卫）：serve、spawn、sleep、file、
 *    gunzipSync、inflateSync、hash、semver、YAML、gc、generateHeapSnapshot、
 *    version、listen、zstd 系列
 *  刻意不提供（上游有成熟的 Node 回退，且是属性级守卫）：
 *    stringWidth、wrapAnsi、JSONL、which —— typeof Bun.x === 'function' 为
 *    false 时上游自动走 Node 实现
 */
import { spawn as nodeSpawn, spawnSync as nodeSpawnSync } from 'node:child_process'
import { Readable, Writable } from 'node:stream'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import net from 'node:net'
import v8 from 'node:v8'
import {
  gzipSync,
  gunzipSync,
  inflateSync,
  deflateSync,
  zstdCompress,
  zstdDecompress,
  zstdCompressSync,
  zstdDecompressSync,
} from 'node:zlib'
import { promisify } from 'node:util'
import { parse as parseYaml } from 'yaml'
import semver from 'semver'
import { createBunServe } from './bun-serve.js'
import { fnv1a64 } from './wyhash.js'

const zstdCompressAsync = promisify(zstdCompress) as (
  data: Uint8Array,
  opts?: { level?: number },
) => Promise<Uint8Array>
const zstdDecompressAsync = promisify(zstdDecompress) as (
  data: Uint8Array,
) => Promise<Uint8Array>

const MIME: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.map': 'application/json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.avif': 'image/avif',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.otf': 'font/otf',
  '.txt': 'text/plain; charset=utf-8',
  '.md': 'text/plain; charset=utf-8',
  '.webmanifest': 'application/manifest+json',
  '.wasm': 'application/wasm',
  '.mp4': 'video/mp4',
  '.webm': 'video/webm',
  '.mp3': 'audio/mpeg',
  '.wav': 'audio/wav',
  '.pdf': 'application/pdf',
  '.xml': 'application/xml',
}

/**
 * BunFile：构造时读入内存的 Blob 子类。
 * 依据：真机实测 Node 26 的 undici 对 Response(Blob 子类) 会触发方法覆盖
 * 调用，且 Blob 的 slice/text/stream 语义与上游对 Bun.file 的使用完全吻合
 * （staticH5.ts / previewFs.ts 只用到 new Response(file)、file.slice(a,b)、
 * exists()/text()/json()）。
 */
class BunFile extends Blob {
  readonly filePath: string
  constructor(p: string | URL, range?: [number, number]) {
    const filePath = typeof p === 'string' ? p : p instanceof URL ? p.pathname : String(p)
    let buf: Buffer
    try {
      buf = fs.readFileSync(filePath)
      if (range) {
        const start = Math.max(0, range[0])
        const end = range[1] === Infinity ? buf.length : Math.min(buf.length, range[1] + 1)
        buf = buf.subarray(start, end)
      }
    } catch {
      buf = Buffer.alloc(0)
    }
    const type = MIME[path.extname(filePath).toLowerCase()] ?? 'application/octet-stream'
    super([buf], { type })
    this.filePath = filePath
  }
  async exists(): Promise<boolean> {
    try {
      await fs.promises.access(this.filePath)
      return true
    } catch {
      return false
    }
  }
}

/** Bun.spawn 返回的 stdout/stderr 包装：ReadableStream + Blob 双接口 */
function makeSubprocessStream(nodeStream: Readable): unknown {
  let web: ReadableStream | null = null
  const api = {
    getReader() {
      web ??= Readable.toWeb(nodeStream) as unknown as ReadableStream
      return web.getReader()
    },
    stream() {
      web ??= Readable.toWeb(nodeStream) as unknown as ReadableStream
      return web
    },
    async text(): Promise<string> {
      const chunks: Buffer[] = []
      for await (const c of nodeStream) chunks.push(c as Buffer)
      return Buffer.concat(chunks).toString()
    },
    async bytes(): Promise<Buffer> {
      const chunks: Buffer[] = []
      for await (const c of nodeStream) chunks.push(c as Buffer)
      return Buffer.concat(chunks)
    },
    async arrayBuffer(): Promise<ArrayBuffer> {
      const b = await api.bytes()
      return b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength) as ArrayBuffer
    },
    async json(): Promise<unknown> {
      return JSON.parse(await api.text())
    },
    async blob(): Promise<Blob> {
      return new Blob([await api.bytes()])
    },
  }
  return api
}

function spawnShim(argv: string[], opts: Record<string, unknown> = {}): unknown {
  const {
    cwd,
    env,
    stdin = 'pipe',
    stdout = 'pipe',
    stderr = 'pipe',
    argv0,
    windowsHide,
  } = opts as {
    cwd?: string
    env?: Record<string, string>
    stdin?: 'pipe' | 'ignore' | 'inherit'
    stdout?: 'pipe' | 'ignore' | 'inherit'
    stderr?: 'pipe' | 'ignore' | 'inherit'
    argv0?: string
    windowsHide?: boolean
  }
  const stdio = [
    stdin === 'inherit' ? 'inherit' : stdin === 'ignore' ? 'ignore' : 'pipe',
    stdout === 'inherit' ? 'inherit' : stdout === 'ignore' ? 'ignore' : 'pipe',
    stderr === 'inherit' ? 'inherit' : stderr === 'ignore' ? 'ignore' : 'pipe',
  ] as const
  const child = nodeSpawn(argv[0], argv.slice(1), {
    cwd,
    env: env as NodeJS.ProcessEnv,
    stdio: stdio as never,
    argv0,
    windowsHide,
    shell: false,
  })

  // 调试辅助：CC_HAHA_SPAWN_LOG_DIR 设置时把子进程 stdout/stderr tee 到文件
  const debugLogDir = process.env.CC_HAHA_SPAWN_LOG_DIR
  if (debugLogDir) {
    try {
      fs.mkdirSync(debugLogDir, { recursive: true })
      const logPath = path.join(debugLogDir, `spawn-${path.basename(String(argv[0]))}.log`)
      // 打印关键认证 env（脱敏）以诊断 401 / 未授权问题
      const envRec = (env ?? {}) as Record<string, string | undefined>
      const authKeys = [
        'ANTHROPIC_API_KEY',
        'ANTHROPIC_AUTH_TOKEN',
        'ANTHROPIC_BASE_URL',
        'ANTHROPIC_MODEL',
        'CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST',
      ]
      const envDump = authKeys
        .map((k) => {
          const v = envRec[k]
          if (v === undefined) return `${k}=<unset>`
          // 凭据类完全不输出内容（仅长度）——避免日志泄漏密钥片段
          if (/KEY|TOKEN|SECRET|PASSWORD/i.test(k)) {
            return `${k}=<redacted len=${v.length}>`
          }
          return `${k}=${v}`
        })
        .join('\n  ')
      fs.appendFileSync(
        logPath,
        `\n=== spawn ${JSON.stringify(argv.slice(0, 6))} pid=${child.pid} ===\n  ${envDump}\n`,
      )
      child.stderr?.on('data', (d: Buffer) => {
        try {
          fs.appendFileSync(logPath, d)
        } catch {
          /* noop */
        }
      })
      child.stdout?.on('data', (d: Buffer) => {
        try {
          fs.appendFileSync(logPath, d)
        } catch {
          /* noop */
        }
      })
    } catch {
      /* noop */
    }
  }

  const emptyStream = new ReadableStream({ start(c) { c.close() } })
  const stdoutApi = child.stdout ? (makeSubprocessStream(child.stdout) as Record<string, unknown>) : null
  const stderrApi = child.stderr ? (makeSubprocessStream(child.stderr) as Record<string, unknown>) : null
  const stdinApi = child.stdin
    ? (Writable.toWeb(child.stdin) as unknown as WritableStream)
    : null

  const exited = new Promise<number>((resolve) => {
    // 用 'exit'（进程退出即触发）而非 'close'（等待 stdio 全部关闭）：
    // Android 上子进程的孙进程可能继承 stdout 管道写端，导致 close
    // 永不触发、server 卡死在等待 CLI 输出。与 Bun 的 exited 语义一致。
    child.on('exit', (code, signal) => {
      resolve(code ?? (signal ? 137 : 0))
    })
  })
  child.on('exit', () => {
    // 进程退出后强制结束 stdio 流，让 readProcessOutputStream 的
    // getReader() 收到 done（防孙进程持有写端造成流永不 EOF）
    try {
      child.stdout?.destroy()
    } catch {
      /* noop */
    }
    try {
      child.stderr?.destroy()
    } catch {
      /* noop */
    }
  })

  const proc: Record<string, unknown> = {
    get pid() {
      return child.pid
    },
    stdout: stdoutApi ?? emptyStream,
    stderr: stderrApi ?? emptyStream,
    get stdin() {
      return stdinApi
    },
    exited,
    kill(signal?: NodeJS.Signals | number) {
      return child.kill(signal as NodeJS.Signals | undefined)
    },
    ref() {
      child.ref()
      return proc
    },
    unref() {
      child.unref()
      return proc
    },
  }
  return proc
}

function spawnSyncShim(argv: string[], opts: Record<string, unknown> = {}): unknown {
  const { cwd, env, stdin = 'pipe', stdout = 'pipe', stderr = 'pipe' } = opts as {
    cwd?: string
    env?: Record<string, string>
    stdin?: 'pipe' | 'ignore' | 'inherit'
    stdout?: 'pipe' | 'ignore' | 'inherit'
    stderr?: 'pipe' | 'ignore' | 'inherit'
  }
  const r = nodeSpawnSync(argv[0], argv.slice(1), {
    cwd,
    env: env as NodeJS.ProcessEnv,
    stdio: [
      stdin === 'inherit' ? 'inherit' : stdin === 'ignore' ? 'ignore' : 'pipe',
      stdout === 'inherit' ? 'inherit' : stdout === 'ignore' ? 'ignore' : 'pipe',
      stderr === 'inherit' ? 'inherit' : stderr === 'ignore' ? 'ignore' : 'pipe',
    ],
  })
  return {
    exitCode: r.status ?? 0,
    success: r.status === 0,
    stdout: r.stdout ?? Buffer.alloc(0),
    stderr: r.stderr ?? Buffer.alloc(0),
    signal: r.signal ?? null,
  }
}

function whichShim(cmd: string): string | null {
  if (!cmd) return null
  if (cmd.includes('/')) {
    try {
      fs.accessSync(cmd, fs.constants.X_OK)
      return cmd
    } catch {
      return null
    }
  }
  const dirs = (process.env.PATH ?? '').split(path.delimiter).filter(Boolean)
  for (const d of dirs) {
    const p = path.join(d, cmd)
    try {
      fs.accessSync(p, fs.constants.X_OK)
      return p
    } catch {
      /* continue */
    }
  }
  return null
}

function gcShim(): void {
  try {
    ;(globalThis as { gc?: () => void }).gc?.()
  } catch {
    /* 未开 --expose-gc 时忽略 */
  }
}

function generateHeapSnapshotShim(_format?: string, _mode?: string): ArrayBuffer {
  const file = path.join(
    os.tmpdir(),
    `cc-haha-heap-${process.pid}-${Math.floor(Math.random() * 1e9)}.heapsnapshot`,
  )
  try {
    v8.writeHeapSnapshot(file)
    const buf = fs.readFileSync(file)
    return buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength) as ArrayBuffer
  } finally {
    try {
      fs.unlinkSync(file)
    } catch {
      /* noop */
    }
  }
}

function listenShim<D = unknown>(opts: {
  hostname?: string
  port?: number
  socket: {
    open?: (sock: unknown) => void
    data?: (sock: unknown, data: Buffer) => void
    drain?: (sock: unknown) => void
    close?: (sock: unknown) => void
    error?: (sock: unknown, err: Error) => void
  }
}): { port: number; hostname: string; stop(closeActive?: boolean): void } {
  const server = net.createServer((sock) => {
    const api: Record<string, unknown> = { data: undefined }
    api.write = (data: Uint8Array) => {
      sock.write(data)
      return data.byteLength
    }
    api.end = () => sock.end()
    opts.socket.open?.(api)
    sock.on('data', (d) => opts.socket.data?.(api, d))
    sock.on('drain', () => opts.socket.drain?.(api))
    sock.on('close', () => opts.socket.close?.(api))
    sock.on('error', (e) => opts.socket.error?.(api, e))
  })
  server.listen(opts.port ?? 0, opts.hostname ?? '127.0.0.1')
  return {
    get port() {
      const a = server.address()
      return typeof a === 'object' && a ? a.port : 0
    },
    get hostname() {
      const a = server.address()
      return typeof a === 'object' && a ? a.address : ''
    },
    stop(closeActive?: boolean) {
      if (closeActive) {
        try {
          ;(server as net.Server & { closeAllConnections?: () => void }).closeAllConnections?.()
        } catch {
          /* noop */
        }
      }
      server.close()
    },
  }
}

const bunServe = createBunServe()

export function createBun(): Record<string, unknown> {
  const bun: Record<string, unknown> = {
    serve: bunServe,
    spawn: spawnShim,
    spawnSync: spawnSyncShim,
    sleep: (ms: number) => new Promise<void>((r) => setTimeout(r, ms)),
    file: (p: string | URL) => new BunFile(p),
    write: async (p: string | URL, data: string | Uint8Array) => {
      const filePath = typeof p === 'string' ? p : p instanceof URL ? p.pathname : String(p)
      await fs.promises.mkdir(path.dirname(filePath), { recursive: true })
      await fs.promises.writeFile(filePath, data)
    },
    which: whichShim,
    version: '1.3.14',
    main: process.argv[1] ?? '',
    gc: gcShim,
    embeddedFiles: undefined,
    listen: listenShim,
    hash: (input: string | number, seed?: number | bigint) => fnv1a64(input, seed),
    semver: {
      order: (a: string, b: string) => semver.compare(a, b),
      satisfies: (v: string, range: string) => semver.satisfies(v, range),
    },
    YAML: { parse: (s: string) => parseYaml(s) },
    gzipSync,
    gunzipSync,
    inflateSync,
    deflateSync,
    zstdCompress: (data: Uint8Array, opts?: { level?: number }) =>
      zstdCompressAsync(data, opts),
    zstdDecompress: (data: Uint8Array) => zstdDecompressAsync(data),
    zstdCompressSync,
    zstdDecompressSync,
    generateHeapSnapshot: generateHeapSnapshotShim,
    build: () => {
      throw new Error(
        'Bun.build 在 Android Node 运行时不可用（computer-use REPL 属桌面专属能力）',
      )
    },
  }
  return bun
}
