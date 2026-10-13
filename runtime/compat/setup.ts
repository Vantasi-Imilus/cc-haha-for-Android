/**
 * 运行时初始化（必须在任何上游业务模块求值之前执行）。
 *
 * 由各入口 wrapper 的第一个 import 触发，职责：
 *  1. process.platform 伪装为 'linux'
 *     —— 上游有 322 处 process.platform 分支；Android 的 Node 上该值为
 *        'android'，绝大多数分支会落入 unknown/非支持路径。伪装为 linux
 *        后 getPlatform() 走 linux 分支（/proc/version 在 Android 上存在且
 *        不含 microsoft/wsl 标记，返回 'linux'），全部 POSIX 假设成立
 *        （本运行时的工具链目录确实提供 POSIX 环境：sh/bash/coreutils）。
 *  2. 注入 globalThis.Bun（Node 实现，见 bun-shim.ts）
 *  3. 安装 WebSocket 兼容包装（Bun 风格 options → ws 包）
 *  4. 环境默认值与 MACRO 全局（对齐上游 preload.ts）
 *  5. CALLER_DIR 工作目录切换（对齐 preload.ts 行为）
 */
import { createBun } from './bun-shim.js'
import { installWebSocket } from './web-socket.js'

let installed = false

export function setup(): void {
  if (installed) return
  installed = true

  // 1. 平台伪装（在一切模块求值前；configurable 保护避免重复定义报错）
  try {
    Object.defineProperty(process, 'platform', {
      value: 'linux',
      configurable: true,
    })
  } catch {
    /* 某些环境下不可写则忽略 */
  }

  // 2. 全局 Bun
  try {
    ;(globalThis as Record<string, unknown>).Bun = createBun()
  } catch {
    /* noop */
  }

  // 2.5 V8 编译缓存：splitting 构建下 476 个 chunk 首次编译慢，
  // 缓存落盘后后续启动秒级（缓存目录随运行时目录持久化）
  try {
    const { enableCompileCache } = require('node:module') as {
      enableCompileCache?: (dir?: string) => unknown
    }
    if (typeof enableCompileCache === 'function') {
      const cacheDir =
        process.env.CC_HAHA_COMPILE_CACHE_DIR ??
        `${process.env.CLAUDE_CONFIG_DIR ?? ''}/compile-cache`
      enableCompileCache(cacheDir || undefined)
    }
  } catch {
    /* node < 22.8 无此 API */
  }

  // 3. WebSocket 包装
  installWebSocket()

  // 4. 环境默认值 + MACRO（对齐 preload.ts）
  process.env.CLAUDE_CODE_LOCAL_SKIP_REMOTE_PREFETCH ??= '1'
  const buildTime =
    process.env.CLAUDE_CODE_LOCAL_BUILD_TIME ?? new Date().toISOString()
  ;(globalThis as Record<string, unknown>).MACRO = {
    VERSION: process.env.CLAUDE_CODE_LOCAL_VERSION ?? '999.0.0-android',
    PACKAGE_URL: 'cc-haha-android',
    NATIVE_PACKAGE_URL: 'cc-haha-android',
    BUILD_TIME: buildTime,
    FEEDBACK_CHANNEL: 'android',
    VERSION_CHANGELOG: '',
    ISSUES_EXPLAINER: '',
  }

  // 5. 工作目录切换（对齐 preload.ts:19-21）
  if (process.env.CALLER_DIR) {
    try {
      process.chdir(process.env.CALLER_DIR)
    } catch {
      /* 目录不存在时保持当前目录 */
    }
  }

  // 6. 启动阶段追踪（诊断用）：CC_HAHA_STARTUP_TRACE=1 时每 20 秒把
  // performance marks dump 到 stderr。CLI 内部用 profileCheckpoint()
  // 打点（utils/startupProfiler.ts），marks 是全局的，可据此定位
  // 初始化卡在哪一步（真机上 CLI 初始化数分钟，需可视化时间线）。
  if (process.env.CC_HAHA_STARTUP_TRACE === '1') {
    // 异常追踪：CLI 收到消息后无响应且无输出时，最常见的原因是异常被
    // 上层 catch 吞掉。这些钩子只打印不拦截（不改上游行为）。
    try {
      process.on('unhandledRejection', (reason) => {
        const r = reason as Error & { stack?: string }
        console.error(
          `[exc-trace] unhandledRejection: ${r?.message ?? String(reason)}\n${
            r?.stack?.split('\n').slice(0, 12).join('\n') ?? ''
          }`,
        )
      })
      process.on('uncaughtException', (err) => {
        console.error(
          `[exc-trace] uncaughtException: ${err?.message}\n${
            err?.stack?.split('\n').slice(0, 12).join('\n') ?? ''
          }`,
        )
      })
      // 输出通道追踪：确认 CLI 是否尝试产生输出
      const origStdoutWrite = process.stdout.write.bind(process.stdout)
      process.stdout.write = ((chunk: unknown, ...rest: unknown[]) => {
        const s = String(chunk)
        if (s.length > 0) {
          console.error(`[out-trace] stdout ${s.length}B ${s.slice(0, 100).replace(/\n/g, ' ')}`)
        }
        return (origStdoutWrite as (...a: unknown[]) => boolean)(chunk, ...rest)
      }) as typeof process.stdout.write
    } catch (e) {
      console.error('[exc-trace] hook 安装失败:', (e as Error).message)
    }

    // 网络层追踪：CLI 处理消息卡住时，最可能是某个网络请求挂起/重试。
    // hook fetch 与 http(s).request，输出目标与耗时。
    try {
      const origFetch = globalThis.fetch
      globalThis.fetch = async function (
        input: unknown,
        init?: unknown,
      ): Promise<Response> {
        const url = String(
          typeof input === 'string'
            ? input
            : (input as { url?: string })?.url ?? input,
        )
        const t0 = Date.now()
        console.error(`[net-trace] fetch -> ${url.slice(0, 160)}`)
        try {
          const r = await origFetch(input as never, init as never)
          console.error(
            `[net-trace] fetch <- ${r.status} ${Date.now() - t0}ms ${url.slice(0, 100)}`,
          )
          return r
        } catch (e) {
          console.error(
            `[net-trace] fetch xx ${Date.now() - t0}ms ${(e as Error).message} ${url.slice(0, 100)}`,
          )
          throw e
        }
      }
      const nodeHttp = require('node:http') as {
        request: (...a: unknown[]) => unknown
      }
      const nodeHttps = require('node:https') as {
        request: (...a: unknown[]) => unknown
      }
      const wrapRequest = (mod: { request: (...a: unknown[]) => unknown }, tag: string) => {
        const orig = mod.request
        mod.request = function (...args: unknown[]) {
          const first = args[0]
          const target =
            typeof first === 'string'
              ? first
              : JSON.stringify(first)?.slice(0, 160)
          console.error(`[net-trace] ${tag}.request -> ${target}`)
          return orig.apply(mod, args)
        }
      }
      wrapRequest(nodeHttp, 'http')
      wrapRequest(nodeHttps, 'https')
    } catch (e) {
      console.error('[net-trace] hook 安装失败:', (e as Error).message)
    }

    const startedAt = Date.now()
    const timer = setInterval(() => {
      try {
        const marks = performance.getEntriesByType('mark') as PerformanceEntry[]
        const last = marks.slice(-6).map(
          (m) => `${m.name}@${Math.round(m.startTime)}ms`,
        )
        const mem = process.memoryUsage()
        console.error(
          `[startup-trace] +${Math.round((Date.now() - startedAt) / 1000)}s ` +
            `marks=${marks.length} last=[${last.join(', ')}] ` +
            `rss=${Math.round(mem.rss / 1048576)}MB heap=${Math.round(mem.heapUsed / 1048576)}MB`,
        )
      } catch {
        /* noop */
      }
    }, 20_000)
    timer.unref?.()
  }
}

setup()
