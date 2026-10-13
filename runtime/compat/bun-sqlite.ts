/**
 * bun:sqlite 的 node:sqlite 实现（esbuild alias 目标）。
 *
 * 上游调用面（调研确认）：
 *   - new Database(path[, {readonly:true}])
 *   - db.exec(sql)（DDL/PRAGMA/BEGIN/COMMIT）
 *   - db.query(sql)（缓存语句）
 *   - db.prepare<T,P>(sql) → stmt.get(...)/.all(...)/.run(...)/.finalize()
 *   - db.run(sql, ...params)（保险起见提供）
 *   - db.transaction(fn) → 可立即调用的函数
 *   - db.close() / db.clearQueryCache()
 *   - 绑定全部为位置式 ? + 变参标量
 *
 * 行为对齐要点（与 bun:sqlite 差异处已显式处理）：
 *   - bun 默认开外键：enableForeignKeyConstraints: true
 *   - bun 默认 WAL 日志模式：打开时执行 PRAGMA journal_mode = WAL
 *   - bun 自动创建父目录：构造时 mkdirSync(dirname, recursive)
 *   - bun 的 .get() 无行返回 null；node 返回 undefined → 统一为 null
 *   - node 无 statement.finalize() → no-op 兼容
 */
import { DatabaseSync } from 'node:sqlite'
import fs from 'node:fs'
import path from 'node:path'

type Row = Record<string, unknown>

class Statement {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private stmt: any
  constructor(stmt: any) {
    this.stmt = stmt
  }
  get(...params: unknown[]): Row | null {
    const r = this.stmt.get(...params)
    return r === undefined ? null : (r as Row)
  }
  all(...params: unknown[]): Row[] {
    return this.stmt.all(...params) as Row[]
  }
  run(...params: unknown[]): { changes: number; lastInsertRowid: number | bigint } {
    return this.stmt.run(...params)
  }
  finalize(): void {
    // node:sqlite 语句由 GC 回收，无显式 finalize；no-op 兼容 bun 语义
  }
  values(...params: unknown[]): IterableIterator<unknown[]> {
    return this.stmt.iterate(...params)
  }
}

export class Database {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private db: any
  private cache = new Map<string, Statement>()

  constructor(filename: string, opts?: { readonly?: boolean }) {
    const isMemory = filename === ':memory:' || filename.startsWith('file:')
    if (!isMemory) {
      try {
        fs.mkdirSync(path.dirname(path.resolve(filename)), { recursive: true })
      } catch {
        /* 父目录可能已存在或不可写，交给 DatabaseSync 报权威错误 */
      }
    }
    this.db = new DatabaseSync(filename, {
      readOnly: !!opts?.readonly,
      enableForeignKeyConstraints: true,
    })
    if (!opts?.readonly && !isMemory) {
      try {
        this.db.exec('PRAGMA journal_mode = WAL')
      } catch {
        /* 某些文件系统不支持 WAL（如只读介质），忽略 */
      }
    }
  }

  exec(sql: string): void {
    this.db.exec(sql)
  }

  query(sql: string): Statement {
    let stmt = this.cache.get(sql)
    if (!stmt) {
      stmt = new Statement(this.db.prepare(sql))
      this.cache.set(sql, stmt)
    }
    return stmt
  }

  prepare(sql: string): Statement {
    return new Statement(this.db.prepare(sql))
  }

  run(sql: string, ...params: unknown[]): { changes: number; lastInsertRowid: number | bigint } {
    return this.db.prepare(sql).run(...params)
  }

  transaction<T extends (...args: any[]) => any>(fn: T): T {
    const db = this.db
    return ((...args: unknown[]) => {
      db.exec('BEGIN')
      try {
        const r = fn(...args)
        db.exec('COMMIT')
        return r
      } catch (e) {
        try {
          db.exec('ROLLBACK')
        } catch {
          /* noop */
        }
        throw e
      }
    }) as T
  }

  close(): void {
    this.cache.clear()
    this.db.close()
  }

  clearQueryCache(): void {
    this.cache.clear()
  }

  loadExtension(): void {
    throw new Error('bun:sqlite loadExtension 在 Android Node 运行时不可用')
  }
}

export default Database
