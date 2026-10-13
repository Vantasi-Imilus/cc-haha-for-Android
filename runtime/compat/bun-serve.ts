/**
 * Bun.serve 的 Node 实现（node:http + ws）。
 *
 * 接口规格来自对 cc-haha 调用点的逐处调研：
 *   - serve({ port, hostname, idleTimeout, maxRequestBodySize, fetch, websocket })
 *   - fetch(req, server) 返回 Response | undefined（升级成功时 undefined）
 *   - websocket: { open(ws), message(ws, string|Buffer), close(ws, code, reason),
 *                  drain(ws), maxPayloadLength }
 *   - server: { port, hostname, stop(closeActive?), upgrade(req,{data})→bool,
 *               requestIP(req)→{address,family,port}|null }
 *   - ws 对象只用到: ws.data、ws.send(string)、ws.close(code, reason)
 */
import http from 'node:http'
import { Readable } from 'node:stream'
import { WebSocketServer } from 'ws'

type WsLike = unknown

export type ServeWebSocketHandler<D = unknown> = {
  open?: (ws: WsLike & { data?: D }) => void
  message?: (ws: WsLike, message: string | Buffer) => void
  close?: (ws: WsLike, code: number, reason: string) => void
  drain?: (ws: WsLike) => void
  maxPayloadLength?: number
}

export type ServeConfig<D = unknown> = {
  port?: number
  hostname?: string
  idleTimeout?: number
  maxRequestBodySize?: number
  fetch: (
    req: Request,
    server: BunServerLike,
  ) => Response | undefined | Promise<Response | undefined>
  websocket?: ServeWebSocketHandler<D>
}

export type BunServerLike = {
  port: number
  hostname: string
  stop(closeActiveConnections?: boolean): void
  upgrade(req: Request, opts?: { data?: unknown }): boolean
  requestIP(req: Request): { address: string; family: string; port: number } | null
}

const socketOf = new WeakMap<Request, import('node:net').Socket>()
const rawReqOf = new WeakMap<Request, http.IncomingMessage>()
const headOf = new WeakMap<Request, Buffer>()
const claimedSockets = new WeakSet<import('node:net').Socket>()

function toRequest(
  req: http.IncomingMessage,
  socket?: import('node:net').Socket,
  head?: Buffer,
): Request {
  const url = `http://${req.headers.host ?? '127.0.0.1'}${req.url ?? '/'}`
  const headers = new Headers()
  for (const [k, v] of Object.entries(req.headers)) {
    if (v === undefined) continue
    if (Array.isArray(v)) {
      for (const x of v) headers.append(k, x)
    } else {
      headers.append(k, v)
    }
  }
  let body: ReadableStream | undefined
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    body = Readable.toWeb(req) as unknown as ReadableStream
  }
  const request = new Request(url, {
    method: req.method,
    headers,
    body: body as BodyInit | undefined,
    // @ts-expect-error Node 需要 duplex 字段
    duplex: body ? 'half' : undefined,
  })
  if (socket) {
    socketOf.set(request, socket)
    rawReqOf.set(request, req)
    if (head) headOf.set(request, head)
  }
  return request
}

function writeResponse(res: http.ServerResponse, resp: Response): void {
  const { status } = resp
  const headers: Record<string, string | string[]> = {}
  resp.headers.forEach((v, k) => {
    if (
      k === 'content-length' ||
      k === 'transfer-encoding' ||
      k === 'set-cookie' ||
      k === 'connection'
    ) {
      return
    }
    const prev = headers[k]
    if (prev === undefined) headers[k] = v
    else if (Array.isArray(prev)) prev.push(v)
    else headers[k] = [prev, v]
  })
  const setCookies = (resp.headers as Headers & { getSetCookie?: () => string[] }).getSetCookie?.()
  if (setCookies && setCookies.length > 0) {
    res.setHeader('set-cookie', setCookies)
  }
  res.writeHead(status, headers)

  const body = resp.body
  if (!body) {
    res.end()
    return
  }
  if (body instanceof Blob) {
    const nodeStream = Readable.fromWeb(body.stream() as never)
    nodeStream.on('error', () => {
      try {
        res.destroy()
      } catch {
        /* noop */
      }
    })
    nodeStream.pipe(res)
    return
  }
  if (typeof body === 'string' || Buffer.isBuffer(body) || body instanceof Uint8Array) {
    res.end(body)
    return
  }
  if (typeof (body as ReadableStream).getReader === 'function') {
    const nodeStream = Readable.fromWeb(body as never)
    nodeStream.on('error', () => {
      try {
        res.destroy()
      } catch {
        /* noop */
      }
    })
    nodeStream.pipe(res)
    return
  }
  // 兜底：ArrayBuffer / 其他可序列化对象
  res.end(String(body))
}

export function createBunServe() {
  return function serve<D = unknown>(config: ServeConfig<D>): BunServerLike {
    const wss = new WebSocketServer({
      noServer: true,
      maxPayload: config.websocket?.maxPayloadLength ?? 16 * 1024 * 1024,
    })
    const clients = new Set<{ terminate(): void }>()

    const httpServer = http.createServer(async (req, res) => {
      let resp: Response | undefined
      try {
        if (config.maxRequestBodySize) {
          const cl = Number(req.headers['content-length'] ?? 0)
          if (cl > config.maxRequestBodySize) {
            res.writeHead(413, { 'content-type': 'text/plain' }).end('413 Payload Too Large')
            return
          }
        }
        const request = toRequest(req, req.socket)
        resp = await config.fetch(request, serverObj)
      } catch {
        if (!res.writableEnded) {
          try {
            res.writeHead(500, { 'content-type': 'text/plain' }).end('Internal Server Error')
          } catch {
            /* noop */
          }
        }
        return
      }
      if (resp === undefined) {
        if (!res.writableEnded) res.writeHead(204).end()
        return
      }
      try {
        writeResponse(res, resp)
      } catch {
        try {
          res.destroy()
        } catch {
          /* noop */
        }
      }
    })

    httpServer.on('upgrade', async (req, socket, head) => {
      try {
        const request = toRequest(req, socket, head)
        const resp = await config.fetch(request, serverObj)
        if (resp === undefined) {
          // Bun 语义：fetch 返回 undefined 表示已在内部完成 upgrade。
          // 若实际上没有被 ws 认领（违反契约），关闭裸连接避免悬挂。
          if (!claimedSockets.has(socket)) socket.destroy()
          console.log(
            `[bun-serve] upgrade ${req.url ?? '/'} -> ${
              claimedSockets.has(socket) ? 'UPGRADED' : 'DROPPED'
            }`,
          )
          return
        }
        // fetch 返回了 Response：以 HTTP 响应写回原始 socket
        console.log(
          `[bun-serve] upgrade ${req.url ?? '/'} REJECTED with ${resp.status}`,
        )
        const res = new http.ServerResponse(req)
        res.assignSocket(socket)
        try {
          writeResponse(res, resp)
        } catch {
          socket.destroy()
        }
      } catch {
        socket.destroy()
      }
    })

    const serverObj: BunServerLike = {
      get port() {
        // Bun.serve 是同步 API：返回时已监听完成、port 立即可用。
        // node:http 的 listen 是异步的——绑定完成前 address() 为 null。
        // 显式指定端口时直接返回它（调用方在 Bun.serve 返回后立即读
        // server.port 构造 sdk-url，等不到 listening 事件）。
        const a = httpServer.address()
        if (typeof a === 'object' && a) return a.port
        return config.port ?? 0
      },
      get hostname() {
        const a = httpServer.address()
        if (typeof a === 'object' && a) return a.address
        return config.hostname ?? '127.0.0.1'
      },
      stop(closeActiveConnections?: boolean) {
        if (closeActiveConnections) {
          for (const c of clients) {
            try {
              c.terminate()
            } catch {
              /* noop */
            }
          }
        }
        httpServer.close()
        try {
          ;(httpServer as http.Server & { closeAllConnections?: () => void }).closeAllConnections?.()
        } catch {
          /* noop */
        }
      },
      upgrade(req: Request, opts?: { data?: unknown }): boolean {
        const socket = socketOf.get(req)
        const rawReq = rawReqOf.get(req)
        if (!socket || !rawReq) return false
        try {
          wss.handleUpgrade(
            rawReq,
            socket,
            headOf.get(req) ?? Buffer.alloc(0),
            (ws: WsLike & { data?: D }) => {
              claimedSockets.add(socket)
              if (opts && opts.data !== undefined) ws.data = opts.data as D
              clients.add(ws as { terminate(): void })
              ;(ws as { on: Function }).on('message', (data: Buffer, isBinary: boolean) => {
                const msg = isBinary ? data : data.toString()
                config.websocket?.message?.(ws, msg)
              })
              ;(ws as { on: Function }).on('close', (code: number, reason: Buffer) => {
                clients.delete(ws as { terminate(): void })
                config.websocket?.close?.(ws, code, reason.toString())
              })
              ;(ws as { on: Function }).on('drain', () => {
                config.websocket?.drain?.(ws)
              })
              ;(ws as { on: Function }).on('error', () => {
                /* ws 库会把 error 之后 close；吞掉避免 uncaught */
              })
              config.websocket?.open?.(ws)
            },
          )
          return true
        } catch {
          return false
        }
      },
      requestIP(req: Request) {
        const socket = socketOf.get(req)
        if (!socket) return null
        const addr = socket.remoteAddress ?? '127.0.0.1'
        return {
          address: addr,
          family: addr.includes(':') ? 'IPv6' : 'IPv4',
          port: socket.remotePort ?? 0,
        }
      },
    }

    httpServer.listen(config.port ?? 0, config.hostname ?? '127.0.0.1')
    return serverObj
  }
}
