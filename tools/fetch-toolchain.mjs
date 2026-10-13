/**
 * 从 Termux 官方仓库拉取工具链并组装 assets/toolchain.tar.gz。
 *
 * 机制：
 *  1. 下载 binary-aarch64 Packages.gz 索引并解析
 *     （Package/Version/Filename/Depends/Replaces/Provides）
 *  2. 从清单出发解析依赖闭包（Depends 的 OR 组取第一个存在的候选）
 *  3. 逐包下载 .deb（缓存）→ 用 Python 解包 ar 归档 → 提取 data.tar.xz
 *     → 解包进 staging/（Termux prefix 布局）
 *  4. 打 toolchain.tar.gz → android/app/src/main/assets/
 *
 * 用法：
 *   node tools/fetch-toolchain.mjs [包名...]
 *   node tools/fetch-toolchain.mjs --preset core   # 预置清单
 */
import fs from 'node:fs'
import path from 'node:path'
import { execSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, '..')
const REPO = 'https://packages.termux.dev/apt/termux-main'
const INDEX_URL = `${REPO}/dists/stable/main/binary-aarch64/Packages.gz`
const cacheDir = path.join(root, 'build/toolchain-cache')
const stagingDir = path.join(root, 'build/toolchain-staging')

/** 预置进 APK 的核心工具链（用户确认：开箱即用 Python/Node/C-C++/Git） */
const PRESET_CORE = [
  'python',
  'clang',
  'lld',
  'binutils',
  'git',
  'make',
  'cmake',
  'ninja',
  'pkg-config',
  'bash',
  'coreutils',
  'ripgrep',
  'openssh',
  'tar',
  'xz-utils',
  'gzip',
  'sed',
  'gawk',
  'grep',
  'findutils',
  'patch',
  'unzip',
  'zip',
  'curl',
]

const PRESET_ANDROID_BUILD = [
  'openjdk-17',
  'kotlin',
  'gradle',
  'aapt2',
  'aapt',
  'd8',
  'apksigner',
]

const PRESET_WINDOWS = ['llvm-mingw-w64']

const PRESETS = {
  core: PRESET_CORE,
  'android-build': PRESET_ANDROID_BUILD,
  windows: PRESET_WINDOWS,
}

function rmrf(p) {
  for (let i = 0; i < 3; i++) {
    try {
      fs.rmSync(p, { recursive: true, force: true })
      return
    } catch (e) {
      if (i === 2) console.warn(`清理失败（可忽略）: ${p} — ${e.message}`)
    }
  }
}

async function httpGet(url, dest) {
  const res = await fetch(url)
  if (!res.ok) throw new Error(`HTTP ${res.status} ${url}`)
  const buf = Buffer.from(await res.arrayBuffer())
  fs.writeFileSync(dest, buf)
  return buf
}

/** 解析 Packages.gz → Map<pkg, {version, filename, depends}> */
function parseIndex(rawText) {
  const entries = {}
  let cur = null
  for (const line of rawText.split('\n')) {
    if (line.startsWith('Package: ')) {
      cur = { name: line.slice(9).trim(), depends: [] }
      entries[cur.name] = cur
    } else if (line.startsWith('Version: ')) cur.version = line.slice(9).trim()
    else if (line.startsWith('Filename: ')) cur.filename = line.slice(10).trim()
    else if (line.startsWith('Depends: ')) {
      cur.depends = parseDepends(line.slice(9).trim())
    }
  }
  return entries
}

/** "a (>= 1.0), b | c" → [ [a], [b, c] ]（OR 组） */
function parseDepends(s) {
  if (!s) return []
  return s.split(',').map((part) =>
    part
      .trim()
      .split('|')
      .map((alt) => alt.trim().replace(/\s*\([^)]*\)\s*/g, '').trim())
      .filter(Boolean),
  )
}

/** 依赖闭包（OR 组选第一个存在的包） */
function resolveClosure(index, roots) {
  const resolved = new Map()
  const queue = [...roots]
  while (queue.length) {
    const name = queue.shift()
    if (resolved.has(name)) continue
    const entry = index[name]
    if (!entry) {
      console.warn(`⚠️ 包不存在（跳过）: ${name}`)
      continue
    }
    resolved.set(name, entry)
    for (const group of entry.depends) {
      const pick = group.find((alt) => index[alt]) ?? group[0]
      if (!resolved.has(pick)) queue.push(pick)
    }
  }
  return resolved
}

function extractDeb(debPath, destPrefix) {
  const py = path.join(here, 'extract_deb.py')
  execSync(
    `python "${py.replace(/\\/g, '/')}" "${destPrefix.replace(/\\/g, '/')}" "${debPath.replace(/\\/g, '/')}"`,
    { stdio: 'inherit' },
  )
}

function tarIt(outFile, dir) {
  const outArg = outFile.replace(/\\/g, '/')
  const dirArg = dir.replace(/\\/g, '/')
  execSync(`tar --force-local -czf "${outArg}" -C "${dirArg}" .`, {
    stdio: 'inherit',
    maxBuffer: 1024 * 1024 * 100,
  })
}

async function main() {
  fs.mkdirSync(cacheDir, { recursive: true })
  rmrf(stagingDir)
  fs.mkdirSync(stagingDir, { recursive: true })

  const args = process.argv.slice(2)
  const usePreset = args.includes('--preset')
  let roots = args.filter((a) => !a.startsWith('--'))
  let presetName = null
  if (usePreset) {
    presetName = roots[0] ?? 'core'
    const preset = PRESETS[presetName]
    if (!preset) {
      console.error(`未知 preset: ${presetName}（可选: ${Object.keys(PRESETS).join(', ')}）`)
      process.exit(1)
    }
    roots = preset
  }
  if (!roots.length) {
    console.error('用法: node tools/fetch-toolchain.mjs [--preset core|android-build|windows] [包名...]')
    process.exit(1)
  }

  // 1. 索引
  console.log('📥 下载包索引...')
  const indexGz = path.join(cacheDir, 'Packages.gz')
  await httpGet(INDEX_URL, indexGz)
  const raw = execSync(
    `python -c "import gzip,sys;sys.stdout.buffer.write(gzip.open(r'${indexGz.replace(/\\/g, '/')}','rb').read())"`,
    { maxBuffer: 1024 * 1024 * 100 },
  ).toString('utf8')
  const index = parseIndex(raw)
  console.log(`   索引共 ${Object.keys(index).length} 个包`)

  // 2. 依赖闭包
  const closure = resolveClosure(index, roots)
  console.log(`📦 依赖闭包共 ${closure.size} 个包`)

  // 3. 下载 + 解包
  let i = 0
  for (const [name, entry] of closure) {
    i++
    const debPath = path.join(cacheDir, `${name}_${entry.version}.deb`)
    if (!fs.existsSync(debPath)) {
      const url = `${REPO}/${entry.filename}`
      process.stdout.write(`  [${i}/${closure.size}] ${name} ...`)
      await httpGet(url, debPath)
      console.log(' ✓')
    } else {
      console.log(`  [${i}/${closure.size}] ${name}（缓存）`)
    }
    extractDeb(debPath, stagingDir)
  }

  // 3.5 布局重排：<staging>/data/data/com.termux/files/usr → <staging>/toolchain/usr
  const termuxUsr = path.join(stagingDir, 'data/data/com.termux/files/usr')
  if (fs.existsSync(termuxUsr)) {
    const target = path.join(stagingDir, 'toolchain/usr')
    fs.mkdirSync(target, { recursive: true })
    for (const e of fs.readdirSync(termuxUsr)) {
      fs.renameSync(path.join(termuxUsr, e), path.join(target, e))
    }
    rmrf(path.join(stagingDir, 'data'))
  }

  // 4. 打 tar.gz（按 preset/清单命名，互不覆盖）
  const suffix = presetName ?? 'custom'
  const outFile = path.join(
    root,
    `android/app/src/main/assets/toolchain-${suffix}.pkg`,
  )
  fs.mkdirSync(path.dirname(outFile), { recursive: true })
  rmrf(outFile)
  console.log(`🗜️  打包 toolchain-${suffix}.pkg ...`)
  tarIt(outFile, stagingDir)
  const sizeMB = (fs.statSync(outFile).size / 1024 / 1024).toFixed(1)
  console.log(`✅ ${outFile} (${sizeMB} MB)`)
}

main().catch((e) => {
  console.error('❌', e.message)
  process.exit(1)
})
