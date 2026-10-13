/**
 * 组装 Android assets 的 runtime.tar.gz：
 *   runtime/
 *   ├── bin/node            # Termux 系 node 可执行文件
 *   ├── bin/cli-launcher    # 由 App 运行时生成（此处不含）
 *   ├── lib/*.so            # node 动态库依赖
 *   ├── dist/*.js           # esbuild 打包产物（server/cli/recovery/adapters）
 *   └── h5/                 # desktop/dist 构建产物（H5 工作台）
 *
 * 用法：node tools/assemble-runtime.mjs [--toolchain]
 * 依赖：
 *   - runtime/dist/（先 npm run build）
 *   - runtime/upstream/desktop/dist/（先构建 H5）
 *   - node 二进制：从 Termux 仓库下载的 .deb 解包目录（TERMUX_ROOT 环境
 *     变量指向解包后的 usr 目录，如 .../data/data/com.termux/files/usr）
 */
import fs from 'node:fs'
import path from 'node:path'
import { execSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, '..')
const runtimeDir = path.join(root, 'runtime')
const staging = path.join(root, 'build', 'runtime-staging')

/**
 * Termux node 环境位置（按优先级）：
 *   1. 环境变量 TERMUX_ROOT / TERMUX_USR（已有解包目录时指定）
 *   2. build/termux-node/...（tools/fetch-node.mjs 的标准产物）
 */
const DEFAULT_TERMUX_USR = path.join(
  root,
  'build/termux-node/data/data/com.termux/files/usr',
)
const TERMUX_USR =
  process.env.TERMUX_ROOT ?? process.env.TERMUX_USR ?? DEFAULT_TERMUX_USR

function rmrf(p) {
  for (let i = 0; i < 3; i++) {
    try {
      fs.rmSync(p, { recursive: true, force: true })
      return
    } catch (e) {
      if (i === 2) {
        console.warn(`清理失败（可忽略）: ${p} — ${e.message}`)
        return
      }
    }
  }
}

function copyDir(src, dest) {
  fs.mkdirSync(dest, { recursive: true })
  for (const e of fs.readdirSync(src, { withFileTypes: true })) {
    const s = path.join(src, e.name)
    const d = path.join(dest, e.name)
    if (e.isDirectory()) copyDir(s, d)
    else if (e.isSymbolicLink()) {
      // 符号链接转实体拷贝（Android 上 app 私有目录支持符号链接，
      // 但 tar 打包时更稳妥）
      try {
        const target = fs.readlinkSync(s)
        fs.symlinkSync(target, d)
      } catch {
        /* 保留原样失败则拷贝目标 */
        try {
          fs.copyFileSync(path.resolve(path.dirname(s), fs.readlinkSync(s)), d)
        } catch {
          /* skip */
        }
      }
    } else fs.copyFileSync(s, d)
  }
}

function main() {
  rmrf(staging)
  fs.mkdirSync(path.join(staging, 'bin'), { recursive: true })
  fs.mkdirSync(path.join(staging, 'lib'), { recursive: true })
  fs.mkdirSync(path.join(staging, 'dist'), { recursive: true })
  fs.mkdirSync(path.join(staging, 'h5'), { recursive: true })

  // 1. node 二进制与动态库
  if (!fs.existsSync(path.join(TERMUX_USR, 'bin/node'))) {
    console.error(`❌ 未找到 Termux node：${TERMUX_USR}/bin/node`)
    console.error('   请先运行 tools/fetch-node.mjs 下载并解包 Termux node 环境，')
    console.error('   或设置 TERMUX_ROOT 环境变量。')
    process.exit(1)
  }
  fs.copyFileSync(path.join(TERMUX_USR, 'bin/node'), path.join(staging, 'bin/node'))
  for (const f of fs.readdirSync(path.join(TERMUX_USR, 'lib'))) {
    if (f.endsWith('.so') || f.includes('.so.')) {
      const s = path.join(TERMUX_USR, 'lib', f)
      try {
        fs.copyFileSync(s, path.join(staging, 'lib', f))
      } catch {
        /* 符号链接跳过 */
      }
    }
  }

  // 2. 内核产物（dist/*.js）默认 **不随 APK 分发**。
  //
  // 原因：dist 是 upstream（cc-haha / Claude Code 内核）经 esbuild 打包的产物，
  // 打进 APK 等于在分发该内核。改为首次启动时由用户自行获取源码、在设备端构建
  // （参见 tools/assemble-buildkit.mjs 与 KernelManager）。
  //
  // 本地调试如需还原旧行为，显式加 --with-kernel（切勿用于发布）。
  const dist = path.join(runtimeDir, 'dist')
  if (process.argv.includes('--with-kernel')) {
    console.warn('⚠️  --with-kernel：把内核产物打进 APK（仅限本地调试，不要用于发布）')
    if (!fs.existsSync(path.join(dist, 'server-split', 'server.js'))) {
      console.error('❌ 未找到 runtime/dist/server-split/server.js，请先 CC_HAHA_SPLIT=1 npm run build')
      process.exit(1)
    }
    copyDir(path.join(dist, 'cli-split'), path.join(staging, 'dist', 'cli-split'))
    copyDir(path.join(dist, 'server-split'), path.join(staging, 'dist', 'server-split'))
    for (const f of fs.readdirSync(dist)) {
      if (f.endsWith('.js')) {
        fs.copyFileSync(path.join(dist, f), path.join(staging, 'dist', f))
      }
    }
  } else {
    console.log('ℹ️  跳过内核产物（dist/）—— 由设备端首次启动时构建')
  }

  // 3. H5
  const h5 = path.join(runtimeDir, 'upstream/desktop/dist')
  if (!fs.existsSync(path.join(h5, 'index.html'))) {
    console.error('❌ 未找到 desktop/dist/index.html，请先构建 H5')
    process.exit(1)
  }
  copyDir(h5, path.join(staging, 'h5'))

  // 4. 打 tar.gz（Windows 的 bsdtar 需要正斜杠路径，避免被当作远程主机）
  const outFile = path.join(root, 'android/app/src/main/assets/runtime.pkg')
  fs.mkdirSync(path.dirname(outFile), { recursive: true })
  rmrf(outFile)
  const outArg = outFile.replace(/\\/g, '/')
  const stagingArg = staging.replace(/\\/g, '/')
  execSync(`tar --force-local -czf "${outArg}" -C "${stagingArg}" .`, {
    stdio: 'inherit',
    maxBuffer: 1024 * 1024 * 100,
  })
  const sizeMB = (fs.statSync(outFile).size / 1024 / 1024).toFixed(1)
  console.log(`✅ ${outFile} (${sizeMB} MB)`)
  rmrf(staging)
}

main()
