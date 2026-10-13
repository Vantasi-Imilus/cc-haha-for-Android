/**
 * @anthropic-ai/sandbox-runtime 的占位实现（Android 不可用）。
 *
 * 背景：上游仅在桌面端的沙箱能力里使用该包（macOS seatbelt / Linux
 * bubblewrap）。Android 上这些不可用，且该包为原生实现无法打包。
 *
 * **重要**：本 stub 必须实现上游实际调用的**全部方法**。
 * 早期版本只实现了 3 个方法，导致 `SandboxManager.isSupportedPlatform()`
 * 调用 undefined 抛异常 —— 该异常在 CLI 内部被吞掉，表现为「CLI 收到
 * 用户消息后无任何响应」（真机实测定位）。
 *
 * 设计原则：
 *  - `isSupportedPlatform()` 返回 false → 适配层走「沙箱不可用」分支
 *  - `wrapWithSandbox(cmd)` 原样返回命令（不包装）
 *  - 其余 getter 返回安全默认值（空配置/false/undefined）
 */
import path from 'node:path'
import os from 'node:os'

const NOT_SUPPORTED = 'sandbox-runtime 在 Android Node 运行时不可用（桌面专属能力）'

/** 违规事件存储的空实现 */
class StubViolationStore {
  add(): void {
    /* noop */
  }
  getAll(): unknown[] {
    return []
  }
  clear(): void {
    /* noop */
  }
  subscribe(): () => void {
    return () => {
      /* noop */
    }
  }
  on(): void {
    /* noop */
  }
}

const violationStore = new StubViolationStore()

/**
 * 沙箱管理器占位。所有方法均为安全降级实现。
 * 用 class 以满足 `InstanceType` 与静态方法调用两种用法。
 */
export class SandboxManager {
  constructor(..._args: unknown[]) {
    /* 允许被 new，不做任何事 */
  }

  // ─── 静态方法（上游通过 BaseSandboxManager.xxx 调用）───

  static isSupportedPlatform(): boolean {
    return false
  }

  static async initialize(..._args: unknown[]): Promise<void> {
    /* 无需初始化 */
  }

  static async reset(..._args: unknown[]): Promise<void> {
    /* noop */
  }

  static async updateConfig(..._args: unknown[]): Promise<void> {
    /* noop */
  }

  static async checkDependencies(): Promise<{ available: boolean; missing: string[] }> {
    return { available: false, missing: [] }
  }

  static async wrapWithSandbox(command: string, ..._args: unknown[]): Promise<string> {
    return command
  }

  static annotateStderrWithSandboxFailures(stderr: string, ..._args: unknown[]): string {
    return stderr
  }

  static async cleanupAfterCommand(..._args: unknown[]): Promise<void> {
    /* noop */
  }

  static async waitForNetworkInitialization(..._args: unknown[]): Promise<void> {
    /* noop */
  }

  static getSandboxViolationStore(): StubViolationStore {
    return violationStore
  }

  static getFsReadConfig(): undefined {
    return undefined
  }

  static getFsWriteConfig(): undefined {
    return undefined
  }

  static getNetworkRestrictionConfig(): undefined {
    return undefined
  }

  static getIgnoreViolations(): undefined {
    return undefined
  }

  static getAllowLocalBinding(): boolean {
    return false
  }

  static getAllowUnixSockets(): string[] {
    return []
  }

  static getEnableWeakerNestedSandbox(): boolean {
    return false
  }

  static getProxyPort(): number | undefined {
    return undefined
  }

  static getSocksProxyPort(): number | undefined {
    return undefined
  }

  static getLinuxHttpSocketPath(): string | undefined {
    return undefined
  }

  static getLinuxSocksSocketPath(): string | undefined {
    return undefined
  }
}

/** macOS 沙箱命令包装（computer-use REPL 用）：原样返回 */
export function wrapCommandWithSandboxMacOS(
  command: string,
  _options?: unknown,
): string {
  return command
}

export const SandboxRuntimeConfigSchema = {
  parse: (x: unknown) => x,
  safeParse: (x: unknown) => ({ success: true, data: x }),
}

export const SandboxViolationStore = StubViolationStore

export const sandboxRuntimeVersion = 'unavailable-android'

/** 依赖检查（部分调用方用顶层函数形式） */
export function checkSandboxDependencies(): { available: boolean; missing: string[] } {
  return { available: false, missing: [] }
}

/** 便于诊断：记录为什么不可用 */
export function getUnavailableReason(): string {
  return NOT_SUPPORTED
}

// 保留导入以避免 tree-shaking 报未使用（path/os 供未来扩展）
void path
void os

export default SandboxManager
