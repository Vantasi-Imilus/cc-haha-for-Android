/**
 * 从 Termux 官方仓库下载 Node.js 运行时（aarch64）及其依赖闭包，解包到本地
 * 缓存目录，供 assemble-runtime.mjs 组装 Android assets 使用。
 *
 * 为什么用 Termux 的 node：
 *   Android 没有官方 Node.js 构建。Termux 的包是 Android（bionic libc）
 *   原生 aarch64 二进制，可直接在设备上运行（真机实测通过）。
 *
 * 用法：
 *   node tools/fetch-node.mjs                # 默认 Node 26（Termux nodejs 包）
 *   node tools/fetch-node.mjs --lts          # 用 nodejs-lts（更保守）
 *   node tools/fetch-node.mjs --check        # 只检查已有缓存
 *
 * 产物：build/termux-node/data/data/com.termux/files/usr/{bin,lib,...}
 * 之后运行 assemble-runtime.mjs 时会自动使用该目录。
 */
import fs from 'node:fs'
import path from 'node:path'
import { execSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, '..')
const REPO = 'https://packages.termux.dev/apt/termux-main'
const INDEX_URL = `${REPO}/dists/stable/main/binary-aarch64/Packages.gz`
const cacheDir = path.join(root, 'build/termux-node-cache')
const outDir = path.join(root, 'build/termux-node')

const ROOT_PACKAGE = process.argv.includes('--lts') ? 'nodejs-lts' : 'nodejs'
const CHECK_ONLY = process.argv.includes('--check')

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
  fs.writeFileSync(dest, Buffer.from(await res.arrayBuffer()))
}

/** 解析 Packages 索引 → Map<name, {filename, depends}> */
function parseIndex(rawText) {
  const entries = {}
  let cur = null
  for (const line of rawText.split('\n')) {
    if (line.startsWith('Package: ')) {
      cur = { name: line.slice(9).trim(), depends: [] }
      entries[cur.name] = cur
    } else if (line.startsWith('Filename: ')) cur.filename = line.slice(10).trim()
    else if (line.startsWith('Depends: ')) {
      cur.depends = line
        .slice(9)
        .trim()
        .split(',')
        .map((part) =>
          part
            .trim()
            .split('|')
            .map((alt) => alt.trim().replace(/\s*\([^)]*\)\s*/g, '').trim())
            .filter(Boolean),
        )
        .filter((g) => g.length > 0)
    }
  }
  return entries
}

/** 依赖闭包（OR 组取第一个存在的候选） */
function resolveClosure(index, roots) {
  const resolved = new Map()
  const queue = [...roots]
  while (queue.length) {
    const name = queue.shift()
    if (resolved.has(name)) continue
    const entry = index[name]
    if (!entry) {
      console.warn(`⚠️  包不存在（跳过）: ${name}`)
      continue
    }
    resolved.set(name, entry)
    for (const group of entry.depends) {
      const pick = group.find((alt) => index[alt]) ?? group[0]
      if (pick && !resolved.has(pick)) queue.push(pick)
    }
  }
  return resolved
}

async function main() {
  const nodeBin = path.join(outDir, 'data/data/com.termux/files/usr/bin/node')
  if (CHECK_ONLY) {
    console.log(fs.existsSync(nodeBin) ? `✅ 已有缓存: ${outDir}` : '❌ 无缓存')
    return
  }

  fs.mkdirSync(cacheDir, { recursive: true })
  rmrf(outDir)

  console.log(`📥 下载包索引（目标包: ${ROOT_PACKAGE}）...`)
  const indexGz = path.join(cacheDir, 'Packages.gz')
  await httpGet(INDEX_URL, indexGz)
  const raw = execSync(
    `python -c "import gzip,sys;sys.stdout.buffer.write(gzip.open(r'${indexGz.replace(/\\/g, '/')}','rb').read())"`,
    { maxBuffer: 1024 * 1024 * 100 },
  ).toString('utf8')
  const index = parseIndex(raw)
  console.log(`   索引共 ${Object.keys(index).length} 个包`)

  const closure = resolveClosure(index, [ROOT_PACKAGE])
  console.log(`📦 依赖闭包共 ${closure.size} 个包: ${[...closure.keys()].join(', ')}`)

  const py = path.join(here, 'extract_deb.py')
  let i = 0
  for (const [name, entry] of closure) {
    i++
    const debPath = path.join(cacheDir, `${name}.deb`)
    if (!fs.existsSync(debPath)) {
      process.stdout.write(`  [${i}/${closure.size}] 下载 ${name} ...`)
      await httpGet(`${REPO}/${entry.filename}`, debPath)
      console.log(' ✓')
    } else {
      console.log(`  [${i}/${closure.size}] ${name}（缓存）`)
    }
    execSync(
      `python "${py.replace(/\\/g, '/')}" "${outDir.replace(/\\/g, '/')}" "${debPath.replace(/\\/g, '/')}"`,
      { stdio: 'pipe' },
    )
  }

  if (!fs.existsSync(nodeBin)) {
    throw new Error(`解包完成但未找到 node 二进制: ${nodeBin}`)
  }
  const size = Number(
    execSync(
      process.platform === 'win32'
        ? `powershell -c "(Get-ChildItem -Recurse '${outDir.replace(/\\/g, '/')}' | Measure-Object -Property Length -Sum).Sum"`
        : `du -sb ${outDir} | cut -f1`,
    ).toString().trim(),
  )
  console.log(`✅ Node 运行时就绪：${outDir}（${(size / 1024 / 1024).toFixed(0)} MB）`)
  console.log('   下一步：node tools/assemble-runtime.mjs')
}

main().catch((e) => {
  console.error('❌', e.message)
  process.exit(1)
})
