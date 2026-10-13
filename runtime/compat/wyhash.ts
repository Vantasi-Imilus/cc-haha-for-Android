/**
 * FNV-1a 64 位确定性哈希（BigInt 实现）。
 *
 * 说明：上游在 Bun 下使用 Bun.hash（wyhash），本运行时下无法获得与 Bun
 * 相同的哈希值。由于 cc-haha-android 的数据（会话索引、缓存键、会话存储键）
 * 全部在安卓本地产生与消费、不存在跨运行时持久化需求，因此这里只需要一个
 * 确定性哈希即可满足全部调用方语义：
 *   - companion.ts: BigInt(hash) & 0xffffffffn
 *   - promptCacheBreakDetection.ts: typeof hash === 'bigint' 分支
 *   - sessionStoragePortable.ts: hash.toString(36)（跨重启稳定）
 *   - hash.ts: hash.toString()
 */

const FNV_OFFSET = 0xcbf29ce484222325n
const FNV_PRIME = 0x100000001b3n
const MASK64 = 0xffffffffffffffffn

function toBytes(input: string | number): Uint8Array {
  if (typeof input === 'string') {
    return new TextEncoder().encode(input)
  }
  // 数字：按 8 字节小端表示（与 bun 对 number 输入的处理思路一致）
  const buf = new ArrayBuffer(8)
  const view = new DataView(buf)
  view.setFloat64(0, input, true)
  return new Uint8Array(buf)
}

/**
 * hash(input, seed) —— 签名对齐 Bun.hash
 * seed 参数：Bun.hash 的 seed 是 number；hashPair 会把上一次的 hash 结果
 * （bigint）作为 seed 传入，这里统一 BigInt 化。
 */
export function fnv1a64(
  input: string | number,
  seed: number | bigint = 0n,
): bigint {
  const bytes = toBytes(input)
  let h = (BigInt(seed) ^ FNV_OFFSET) & MASK64
  for (let i = 0; i < bytes.length; i++) {
    h ^= BigInt(bytes[i])
    h = (h * FNV_PRIME) & MASK64
  }
  return h
}
