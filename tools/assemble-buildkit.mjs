/**
 * 组装 Android assets 的 buildkit.pkg —— 设备端内核构建套件。
 *
 * 背景：APK 不再内置 cc-haha / Claude Code 内核产物（esbuild bundles）。
 * 首次启动时由用户在设备上获取源码并在设备端构建。本脚本把构建所需的
 * "合法可分发"材料打进 APK：
 *
 *   buildkit/
 *   ├── compat/                 # 我们的 Bun→Node 适配层（原创代码）
 *   ├── build/                  # build.mjs + android-preload.mjs（原创代码）
 *   ├── package.json            # 构建脚本的依赖声明
 *   ├── node_modules/           # 公开 npm 依赖（esbuild 换成 linux-arm64）
 *   └── adapters-node_modules/  # upstream/adapters 的公开依赖（构建时挂载）
 *
 * 用法：node tools/assemble-buildkit.mjs
 * 产物：android/app/src/main/assets/buildkit.pkg
 */
import fs from 'node:fs'
import path from 'node:path'
import { execSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, '..')
const runtimeDir = path.join(root, 'runtime')
const upstreamAdapters = path.join(runtimeDir, 'upstream', 'adapters')
const staging = path.join(root, 'build', 'buildkit-staging')
const kit = path.join(staging, 'buildkit')
const esbuildCache = path.join(root, 'build', 'esbuild-linux-arm64')

/** 设备用不到的其它平台专属包（体积大），打包时跳过 */
const SKIP_DIRS = new Set([
  '@esbuild/win32-x64',
  '@esbuild/win32-arm64',
  '@esbuild/win32-ia32',
  '@esbuild/darwin-x64',
  '@esbuild/darwin-arm64',
  '@esbuild/linux-x64',
  '@esbuild/linux-arm',
  '@esbuild/linux-ia32',
  '@esbuild/freebsd-x64',
  '@esbuild/openbsd-x64',
  '@esbuild/netbsd-x64',
  '@esbuild/sunos-x64',
  '@esbuild/android-arm',
  '@esbuild/android-x64',
])

const ESBUILD_VERSION = '0.25.12'
const ESBUILD_MIRROR =
  'https://registry.npmmirror.com/@esbuild/linux-arm64/-/linux-arm64-' + ESBUILD_VERSION + '.tgz'

function rmrf(p) {
  fs.rmSync(p, { recursive: true, force: true })
}

/** 递归拷贝，跳过 EXCLUDE，保留符号链接 */
function copyTree(src, dest, rel = '') {
  fs.mkdirSync(dest, { recursive: true })
  for (const e of fs.readdirSync(src, { withFileTypes: true })) {
    const relPath = rel ? rel + '/' + e.name : e.name
    if (SKIP_DIRS.has(relPath)) continue
    const s = path.join(src, e.name)
    const d = path.join(dest, e.name)
    if (e.isDirectory()) copyTree(s, d, relPath)
    else if (e.isSymbolicLink()) {
      try {
        fs.symlinkSync(fs.readlinkSync(s), d)
      } catch {
        try {
          fs.copyFileSync(s, d)
        } catch {
          /* skip */
        }
      }
    } else fs.copyFileSync(s, d)
  }
}

/** 确保 build/esbuild-linux-arm64 存在（含 package.json 与 bin/esbuild） */
async function ensureEsbuildArm64() {
  const bin = path.join(esbuildCache, 'bin', 'esbuild')
  if (fs.existsSync(bin)) return
  console.log(`下载 esbuild linux-arm64 ${ESBUILD_VERSION} ...`)
  const res = await fetch(ESBUILD_MIRROR)
  if (!res.ok) throw new Error(`下载失败: HTTP ${res.status}`)
  const tgz = path.join(root, 'build', 'esbuild-arm64.tgz')
  fs.mkdirSync(path.dirname(tgz), { recursive: true })
  fs.writeFileSync(tgz, Buffer.from(await res.arrayBuffer()))
  rmrf(esbuildCache)
  fs.mkdirSync(esbuildCache, { recursive: true })
  // tgz 内为 package/*，剥掉一层；Windows 下必须传正斜杠路径
  const tgzArg = tgz.replace(/\\/g, '/')
  const cacheArg = esbuildCache.replace(/\\/g, '/')
  execSync(`tar --force-local -xzf "${tgzArg}" -C "${cacheArg}" --strip-components=1`, {
    stdio: 'inherit',
  })
  fs.rmSync(tgz, { force: true })
  if (!fs.existsSync(bin)) throw new Error('esbuild 解包失败，未找到 bin/esbuild')
}

function dirSize(p) {
  let total = 0
  const walk = (d) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const f = path.join(d, e.name)
      if (e.isDirectory()) walk(f)
      else if (e.isFile()) total += fs.statSync(f).size
    }
  }
  walk(p)
  return total
}

async function main() {
  for (const req of ['compat', 'build', 'node_modules', 'package.json']) {
    const p = path.join(runtimeDir, req)
    if (!fs.existsSync(p)) {
      console.error(`❌ 缺少 runtime/${req}`)
      process.exit(1)
    }
  }
  await ensureEsbuildArm64()

  rmrf(staging)
  fs.mkdirSync(kit, { recursive: true })

  console.log('拷贝 compat / build / package.json ...')
  copyTree(path.join(runtimeDir, 'compat'), path.join(kit, 'compat'))
  copyTree(path.join(runtimeDir, 'build'), path.join(kit, 'build'))
  fs.copyFileSync(path.join(runtimeDir, 'package.json'), path.join(kit, 'package.json'))

  console.log('拷贝 node_modules（跳过其它平台包）...')
  copyTree(path.join(runtimeDir, 'node_modules'), path.join(kit, 'node_modules'))

  console.log('放入 esbuild linux-arm64 ...')
  const esbDest = path.join(kit, 'node_modules', '@esbuild', 'linux-arm64')
  copyTree(esbuildCache, esbDest)

  if (fs.existsSync(upstreamAdapters)) {
    console.log('拷贝 upstream/adapters/node_modules ...')
    copyTree(
      path.join(upstreamAdapters, 'node_modules'),
      path.join(kit, 'adapters-node_modules'),
    )
  } else {
    console.warn('⚠️  未找到 runtime/upstream/adapters/node_modules，跳过适配器依赖')
  }

  const outFile = path.join(root, 'android/app/src/main/assets/buildkit.pkg')
  rmrf(outFile)
  const outArg = outFile.replace(/\\/g, '/')
  const stagingArg = staging.replace(/\\/g, '/')
  console.log('打包中（体积较大，请稍候）...')
  execSync(`tar --force-local -czf "${outArg}" -C "${stagingArg}" buildkit`, {
    stdio: 'inherit',
    maxBuffer: 1024 * 1024 * 512,
  })
  const mb = (fs.statSync(outFile).size / 1024 / 1024).toFixed(1)
  const raw = (dirSize(kit) / 1024 / 1024).toFixed(1)
  console.log(`✅ ${outFile}`)
  console.log(`   原始 ${raw} MB → 压缩 ${mb} MB`)
  rmrf(staging)
}

main().catch((e) => {
  console.error('❌', e.message)
  process.exit(1)
})
