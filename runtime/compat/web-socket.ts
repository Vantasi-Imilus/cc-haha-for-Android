/**
 * 全局 WebSocket 的兼容包装。
 *
 * 背景：上游 4 处（services/mcp/client.ts、cli/transports/WebSocketTransport.ts、
 * remote/SessionsWebSocket.ts、services/voiceStreamSTT.ts）在检测到 Bun 时
 * 会向 `new globalThis.WebSocket(url, { headers, proxy, tls, protocols })`
 * 传 Bun 专有选项。真机实测 Node 26 内置 WebSocket（undici）不会报错但会
 * 静默忽略这些选项——headers/proxy/tls 全部失效。
 *
 * 本包装用 ws 包（上游依赖，纯 JS）实现 DOM 风格 WebSocket 接口并完整
 * 映射 Bun 选项：
 *   headers → ws 构造选项 headers
 *   proxy   → https-proxy-agent 的 agent
 *   tls     → ws 构造选项 ca/cert/key/rejectUnauthorized/servername
 *   protocols → ws 构造选项 protocols（也支持 DOM 第二位置参数形式）
 */
import { WebSocket as WsWebSocket } from 'ws'
import { HttpsProxyAgent } from 'https-proxy-agent'

type BunTlsOptions = {
  ca?: string | Buffer | Array<string | Buffer>
  cert?: string | Buffer | Array<string | Buffer>
  key?: string | Buffer | Array<string | Buffer>
  pfx?: string | Buffer
  passphrase?: string
  rejectUnauthorized?: boolean
  servername?: string
}

type BunWsOptions = {
  headers?: Record<string, string>
  proxy?: string
  tls?: BunTlsOptions
  protocols?: string | string[]
  agent?: unknown
}

export class AndroidWebSocket extends EventTarget {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSING = 2
  static readonly CLOSED = 3

  readyState = AndroidWebSocket.CONNECTING
  binaryType = 'blob'
  readonly url: string

  onopen: ((ev: Event) => void) | null = null
  onmessage: ((ev: MessageEvent) => void) | null = null
  onclose: ((ev: CloseEvent) => void) | null = null
  onerror: ((ev: Event) => void) | null = null

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private _ws: any

  constructor(url: string, protocolsOrOptions?: string | string[] | BunWsOptions) {
    super()
    this.url = url

    const opts: Record<string, unknown> = {}
    if (typeof protocolsOrOptions === 'string') {
      opts.protocols = protocolsOrOptions
    } else if (Array.isArray(protocolsOrOptions)) {
      opts.protocols = protocolsOrOptions
    } else if (protocolsOrOptions && typeof protocolsOrOptions === 'object') {
      if (protocolsOrOptions.protocols) opts.protocols = protocolsOrOptions.protocols
      if (protocolsOrOptions.headers) opts.headers = protocolsOrOptions.headers
      const tls = protocolsOrOptions.tls
      if (tls) {
        if (tls.ca !== undefined) opts.ca = tls.ca
        if (tls.cert !== undefined) opts.cert = tls.cert
        if (tls.key !== undefined) opts.key = tls.key
        if (tls.pfx !== undefined) opts.pfx = tls.pfx
        if (tls.passphrase !== undefined) opts.passphrase = tls.passphrase
        if (tls.rejectUnauthorized !== undefined) opts.rejectUnauthorized = tls.rejectUnauthorized
        if (tls.servername !== undefined) opts.servername = tls.servername
      }
      if (protocolsOrOptions.proxy) {
        try {
          opts.agent = new HttpsProxyAgent(protocolsOrOptions.proxy)
        } catch {
          /* 非法 proxy URL：忽略，走直连（与 upstream 容错语义一致） */
        }
      }
      if (protocolsOrOptions.agent) opts.agent = protocolsOrOptions.agent
    }

    const ws = new WsWebSocket(url, opts)
    this._ws = ws

    ws.on('open', () => {
      this.readyState = AndroidWebSocket.OPEN
      const ev = new Event('open')
      this.dispatchEvent(ev)
      this.onopen?.(ev)
    })
    ws.on('message', (data: Buffer, isBinary: boolean) => {
      const payload = isBinary ? data : data.toString()
      if (process.env.CC_HAHA_STARTUP_TRACE === '1') {
        const s = typeof payload === 'string' ? payload : payload.toString()
        console.error(
          `[ws-trace] recv ${s.length}B ${s.slice(0, 120).replace(/\n/g, ' ')}`,
        )
      }
      const ev = new MessageEvent('message', { data: payload })
      this.dispatchEvent(ev)
      this.onmessage?.(ev)
    })
    ws.on('close', (code: number, reason: Buffer) => {
      this.readyState = AndroidWebSocket.CLOSED
      const ev = new CloseEvent('close', {
        code,
        reason: reason.toString(),
        wasClean: code === 1000 || code === 1001,
      })
      this.dispatchEvent(ev)
      this.onclose?.(ev)
    })
    // 心跳关键：CLI 的 WebSocketTransport 在 Bun 分支监听 'pong' 事件
    // 判定连接存活（pongReceived）。不转发会导致 ping/pong 心跳超时，
    // CLI 主动断开 sdk 连接进入重连循环。
    ws.on('pong', (data: Buffer) => {
      const ev = new MessageEvent('pong', { data: data.toString() })
      this.dispatchEvent(ev)
    })
    ws.on('error', (err: Error) => {
      const ev = new Event('error')
      this.dispatchEvent(ev)
      this.onerror?.(ev)
      // 若无监听器，抛出以暴露错误（对齐浏览器语义：error 事件不可吞）
      if (!this.onerror) {
        // 上游普遍自行注册 error 监听；此处静默避免 uncaught 崩溃
      }
    })
  }

  send(data: string | Uint8Array | ArrayBuffer): void {
    if (process.env.CC_HAHA_STARTUP_TRACE === '1') {
      const s = typeof data === 'string' ? data : String(data)
      console.error(
        `[ws-trace] send ${s.length}B ${s.slice(0, 120).replace(/\n/g, ' ')}`,
      )
    }
    this._ws.send(data)
  }

  close(code?: number, reason?: string): void {
    if (this.readyState === AndroidWebSocket.CLOSED) return
    this.readyState = AndroidWebSocket.CLOSING
    try {
      this._ws.close(code, reason)
    } catch {
      this._ws.terminate()
    }
  }

  terminate(): void {
    this._ws.terminate()
  }

  ping(data?: Buffer): void {
    this._ws.ping(data)
  }
}

export function installWebSocket(): void {
  try {
    ;(globalThis as Record<string, unknown>).WebSocket = AndroidWebSocket
  } catch {
    /* 只读全局时放弃（不影响 serve 侧的 ws 使用） */
  }
}
