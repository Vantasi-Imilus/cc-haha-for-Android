/**
 * bun:ffi 的占位实现（esbuild alias 目标）。
 *
 * 上游唯一使用点是 upstreamproxy/upstreamproxy.ts:229（require('bun:ffi')
 * 后调用 dlopen 做流量转发进程共享内存）。该功能在 Android Node 运行时
 * 下不可用；提供显式抛错的占位，调用方（若未捕获）会得到清晰报错而非
 * 模块解析失败。
 */

export function dlopen(_path: string, _symbols: unknown): unknown {
  throw new Error('bun:ffi 在 Android Node 运行时不可用（upstreamproxy 功能不支持）')
}

export const CString = { byteLength: () => 0, ptr: () => 0 }
export const CStringArray = { byteLength: () => 0, ptr: () => 0 }
export const ptr = {}
export const JSCallback = {}
export const suffix = Symbol('suffix')
export default { dlopen }
