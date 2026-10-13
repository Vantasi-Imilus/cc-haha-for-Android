/**
 * bun:bundle 的 feature() 实现（esbuild alias 目标）。
 *
 * 上游 197 个文件在模块求值期同步调用 feature('NAME') 做条件门控
 * （典型：feature('X') ? require('...') : null）。真值来源是启动参数
 * `--feature=NAME`（可重复，见 bin/claude-haha 与 sidecar 编译配置）。
 * 因此本实现只需在模块求值期解析 process.argv 即可，完全同步。
 */

const FEATURE_PREFIX = '--feature='

const enabled = new Set<string>()
for (const arg of process.argv) {
  if (arg.startsWith(FEATURE_PREFIX)) {
    enabled.add(arg.slice(FEATURE_PREFIX.length))
  }
}

export function feature(name: string): boolean {
  return enabled.has(name)
}
