/**
 * cc-haha-android 运行时打包脚本。
 *
 * 用 esbuild 把上游（upstream/ 目录，保持原样未修改）的各个入口打包为
 * 可在 Android Node 运行时（Termux 系 node 二进制）上直接执行的单文件：
 *   - server         —— 本地 HTTP/WebSocket 服务（H5 界面的宿主）
 *   - cli            —— 终端 TUI（可选，在终端 App 中调试用）
 *   - recovery       —— 本地恢复 CLI
 *   - adapter-*      —— 8 个 IM 接入进程
 *
 * 兼容层机制（全部在 compat/ 中，上游零修改）：
 *  1. onLoad 插件：入口文件顶部注入 `import compat/setup.js`（ESM 求值
 *     顺序保证 setup 先于一切业务模块执行）；
 *  2. 同一插件：把 `import.meta.main` 替换为 __ENTRY_MAIN__（define=true，
 *     每个 bundle 独立，其入口即主模块）；
 *  3. 把 Bun 专有的 `import.meta.dir` 替换为 __META_DIR__（define 为运行时
 *     表达式，语义：Android 上无源码树，一律回落到 env/进程 cwd）；
 *  4. alias：bun:sqlite → compat/bun-sqlite（node:sqlite 包装）、
 *     bun:bundle → compat/bun-bundle（feature()）、bun:ffi → 抛错占位；
 *  5. onResolve 插件：src/* 前缀导入映射回 upstream/src（上游 tsconfig paths
 *     有 "src/*": "./src/*"，共 633 处）；@anthropic-ai/sandbox-runtime
 *     映射到占位模块（desktop 沙箱能力，Android 不可用）；
 *  6. 原生/可选模块 external 化：bufferutil、utf-8-validate（ws 的
 *     可选加速，运行时回退纯 JS）、modifiers-napi、audio-capture.node
 *     （desktop 键盘/语音，调用处均有 try/catch 守卫）。
 */
import esbuild from 'esbuild'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, '..')
const upstream = path.join(root, 'upstream')
const compat = path.join(root, 'compat')

const setupModule = path.join(compat, 'setup.ts')

/** 计算入口文件到 setup.ts 的相对导入说明符（Windows 上用相对路径最稳） */
function setupImportFor(entryPath) {
  let rel = path
    .relative(path.dirname(entryPath), setupModule)
    .replace(/\\/g, '/')
  if (!rel.startsWith('.')) rel = './' + rel
  return rel
}

const ENTRIES = {
  server: 'src/server/index.ts',
  cli: 'src/entrypoints/cli.tsx',
  recovery: 'src/localRecoveryCli.ts',
  'adapter-telegram': 'adapters/telegram/index.ts',
  'adapter-feishu': 'adapters/feishu/index.ts',
  'adapter-wechat': 'adapters/wechat/index.ts',
  'adapter-dingtalk': 'adapters/dingtalk/index.ts',
  'adapter-whatsapp': 'adapters/whatsapp/index.ts',
  'adapter-qq': 'adapters/qq/index.ts',
  'adapter-slack': 'adapters/slack/index.ts',
  'adapter-wecom': 'adapters/wecom/index.ts',
}

function pathToFileUrl(p) {
  const abs = p.replace(/\\/g, '/')
  return /^[a-zA-Z]:/.test(abs) ? `file:///${abs}` : `file://${abs}`
}

const entryPaths = new Set(
  Object.values(ENTRIES).map((rel) =>
    path.resolve(upstream, rel).replace(/\\/g, '/'),
  ),
)

/**
 * [cc-haha-android 定制] 让文件浏览 API 允许额外根目录。
 *
 * 背景：upstream 的可浏览白名单 = home + /tmp + 会话 workDir（由 sessions.ts 注册），
 * 因此 Android 上「内部存储」快捷按钮的目标 /storage/emulated/0（workDir 的父目录）
 * 会被 403 拒绝，表现为按钮点了没反应。
 *
 * 这里在构建期往 filesystemAccessRoots 注入"从环境变量追加根目录"的代码，
 * 保持 upstream 源码零修改（改的是本项目自己的构建脚本）。
 */
const androidAccessRoots = {
  name: 'cc-haha-android-access-roots',
  setup(build) {
    build.onLoad({ filter: /filesystemAccessRoots\.ts$/ }, async (args) => {
      let src = await fs.promises.readFile(args.path, 'utf8')
      const anchor = 'const registeredRoots = new Set<string>()'
      if (src.includes(anchor)) {
        src = src.replace(
          anchor,
          anchor +
            '\n// [cc-haha-android] 由环境变量追加可浏览根目录（Android 公共存储）\n' +
            ";(process.env.CC_HAHA_EXTRA_ROOTS || '')\n" +
            "  .split(':')\n" +
            '  .filter(Boolean)\n' +
            '  .forEach((r) => registeredRoots.add(canonicalizeFilesystemAccessPath(r)))\n',
        )
      }
      return { contents: src, loader: 'ts' }
    })
  },
}

/**
 * [cc-haha-android 定制] 让 /update-config skill 的 schema 生成可降级。
 *
 * 上游 bug：该 skill 用 `toJSONSchema(SettingsSchema())` 生成文档，而
 * SettingsSchema 的 enabledPlugins 字段含 `z.undefined()` —— zod v4 的
 * toJSONSchema 无法表示 undefined，抛 "Undefined cannot be represented in
 * JSON Schema"，导致整个 skill 加载失败。
 *
 * 这里只给这一处调用包上 try/catch（schema 文档降级为提示文本），
 * 不动 SettingsSchema 本身，因此配置的运行时校验行为不受影响。
 */
const androidSkillSchemaFallback = {
  name: 'cc-haha-android-skill-schema-fallback',
  setup(build) {
    build.onLoad({ filter: /updateConfig\.ts$/ }, async (args) => {
      let src = await fs.promises.readFile(args.path, 'utf8')
      const anchor = "const jsonSchema = toJSONSchema(SettingsSchema(), { io: 'input' })"
      if (src.includes(anchor)) {
        src = src.replace(
          anchor,
          'let jsonSchema\n' +
            '  try {\n' +
            "    jsonSchema = toJSONSchema(SettingsSchema(), { io: 'input' })\n" +
            '  } catch (e) {\n' +
            '    // [cc-haha-android] 个别平台/类型无法转 JSON Schema，降级为提示文本\n' +
            '    return `(settings schema unavailable: ${e instanceof Error ? e.message : String(e)})`\n' +
            '  }',
        )
      }
      return { contents: src, loader: 'ts' }
    })
  },
}

/** 入口注入 setup + import.meta.main / import.meta.dir 替换 + text 导入改写 */
const TEXT_IMPORT_RE =
  /import\s+([A-Za-z_$][\w$]*)\s+from\s+(["'])([^"']+)\2\s+with\s*\{\s*type:\s*["']text["']\s*\}/g

const entryTransform = {
  name: 'cc-haha-android-entry',
  setup(build) {
    build.onLoad({ filter: /\.(ts|tsx)$/ }, async (args) => {
      const abs = args.path.replace(/\\/g, '/')
      const isEntry = entryPaths.has(abs)
      if (!isEntry) return null
      let src = await fs.promises.readFile(args.path, 'utf8')
      src = src
        .replaceAll('import.meta.main', '__ENTRY_MAIN__')
        .replaceAll('import.meta.dir', '__META_DIR__')
      src = src.replace(TEXT_IMPORT_RE, (_m, name, _q, spec) => {
        return `import ${name} from ${JSON.stringify('virtual-text:' + spec)}`
      })
      src = `import ${JSON.stringify(setupImportFor(args.path))};
${src}`
      return { contents: src, loader: 'tsx' }
    })
    // 非入口文件也做 import.meta 替换与 text 导入改写
    build.onLoad({ filter: /\.(ts|tsx)$/ }, async (args) => {
      const abs = args.path.replace(/\\/g, '/')
      if (entryPaths.has(abs)) return null // 已处理
      // [cc-haha-android] 放行给 androidSkillSchemaFallback 插件处理。
      // 该文件里的 `type: 'text'` 是 MCP 消息类型而非 import 断言，会被下面的
      // 字符串匹配误判为需要本插件处理，从而拦掉后续插件的注入。
      if (abs.endsWith('/skills/bundled/updateConfig.ts')) return null
      let src = await fs.promises.readFile(args.path, 'utf8')
      if (
        !src.includes('import.meta.main') &&
        !src.includes('import.meta.dir') &&
        !src.includes("type: 'text'") &&
        !src.includes('type: "text"')
      ) {
        return null
      }
      src = src.replaceAll('import.meta.main', '__ENTRY_MAIN__')
      src = src.replaceAll('import.meta.dir', '__META_DIR__')
      src = src.replace(TEXT_IMPORT_RE, (_m, name, _q, spec) => {
        return `import ${name} from ${JSON.stringify('virtual-text:' + spec)}`
      })
      return { contents: src, loader: 'tsx' }
    })
    // virtual-text: 相对 specifier → 真实文件（namespace 隔离）
    build.onResolve({ filter: /^virtual-text:/ }, (args) => ({
      path: path
        .resolve(path.dirname(args.importer), args.path.slice('virtual-text:'.length))
        .replace(/\\/g, '/'),
      namespace: 'text-file',
    }))
    build.onLoad({ filter: /.*/, namespace: 'text-file' }, async (args) => {
      const content = await fs.promises.readFile(args.path, 'utf8')
      return {
        contents: `export default ${JSON.stringify(content)}`,
        loader: 'js',
      }
    })
  },
}

function import_meta_prefix(setupUrl) {
  return `import ${setupUrl};\n`
}

/** 上游 TS 约定：import './foo.js' 实际指向 foo.ts / foo.tsx / foo/index.ts */
function remapJsToTs(absPath) {
  if (fs.existsSync(absPath)) return null // 真实 .js 文件交给默认解析
  const base = absPath.slice(0, -3)
  for (const cand of [
    base + '.ts',
    base + '.tsx',
    path.join(base, 'index.ts'),
    path.join(base, 'index.tsx'),
  ]) {
    if (fs.existsSync(cand)) return cand
  }
  return null
}

const tsExtensionRemap = {
  name: 'cc-haha-android-ts-ext',
  setup(build) {
    build.onResolve({ filter: /\.js$/ }, (args) => {
      if (!args.path.startsWith('.')) return null
      const abs = path.resolve(path.dirname(args.importer), args.path)
      const cand = remapJsToTs(abs)
      return cand ? { path: cand } : null
    })
  },
}

/** 模块解析：src/* 前缀、sandbox-runtime 占位、可选原生模块 external */
const resolveCompat = {
  name: 'cc-haha-android-resolve',
  setup(build) {
    build.onResolve({ filter: /^src\// }, (args) => {
      const abs = path.join(upstream, args.path).replace(/\\/g, '/')
      if (fs.existsSync(abs)) return { path: abs }
      // src/* 导入同样遵循 .js → .ts 约定
      const cand = remapJsToTs(abs)
      return cand ? { path: cand } : { path: abs }
    })
    build.onResolve({ filter: /^@anthropic-ai\/sandbox-runtime/ }, () => ({
      path: path.join(compat, 'stub-sandbox-runtime.ts').replace(/\\/g, '/'),
    }))
    build.onResolve(
      {
        filter: /^(bufferutil|utf-8-validate|modifiers-napi|audio-capture\.node|audio-capture-napi)$/,
      },
      (args) => ({ path: args.path, external: true }),
    )
    // 可选 provider SDK：均为动态 import 的懒加载路径（Bedrock/Foundry/
    // Vertex/Azure 凭据流、mcpb 插件构建），Android 上缺失时由上游
    // try/catch 优雅降级；external 化避免打包失败与启动期解析崩溃。
    build.onResolve(
      {
        filter:
          /^(@anthropic-ai\/(bedrock-sdk|foundry-sdk|vertex-sdk|mcpb)|@azure\/identity)$/,
      },
      (args) => ({ path: args.path, external: true }),
    )
    // OpenTelemetry 各 exporter 均为可选动态加载（上游 OTel 初始化按
    // 环境变量懒加载并容错）；Android 上不装这些 exporter 包。
    build.onResolve(
      {
        filter: /^@opentelemetry\/exporter-/,
      },
      (args) => ({ path: args.path, external: true }),
    )
  },
}

async function buildOne(name, relEntry, watch) {
  // CC_HAHA_NOMINIFY=1 时产非压缩版（供 V8 snapshot 构建用，
  // 保留可读的 main 标识便于后处理替换）
  const noMinify = process.env.CC_HAHA_NOMINIFY === '1'
  // CC_HAHA_CJS=1 时输出 CJS（node --build-snapshot 的入口必须 CJS）
  const cjs = process.env.CC_HAHA_CJS === '1'
  // CC_HAHA_SPLIT=1 时 code splitting（配合 V8 compile cache，
  // 首启慢、后续启动秒级）
  const split = process.env.CC_HAHA_SPLIT === '1'
  const base = {
    entryPoints: [path.join(upstream, relEntry)],
    bundle: true,
    platform: 'node',
    format: 'esm',
    target: 'node22',
    sourcemap: false,
    treeShaking: true,
    minify: !noMinify,
    legalComments: 'none',
    charset: 'utf8',
    alias: {
      'bun:sqlite': path.join(compat, 'bun-sqlite.ts'),
      'bun:bundle': path.join(compat, 'bun-bundle.ts'),
      'bun:ffi': path.join(compat, 'bun-ffi.ts'),
      '@ant/claude-for-chrome-mcp': path.join(
        upstream,
        'stubs/ant-claude-for-chrome-mcp.ts',
      ),
      'color-diff-napi': path.join(upstream, 'stubs/color-diff-napi.ts'),
    },
    define: {
      // CJS/snapshot 版：入口不自动执行 main（由 deserialize 回调触发），
      // 同时把 replWorker 等模块的 TLA 分支 DCE 掉（CJS 不支持 TLA）
      __ENTRY_MAIN__: cjs ? 'false' : 'true',
      __META_DIR__: '__META_DIR_VALUE__',
    },
    banner: {
      js: [
        'import { createRequire as __createRequireForBundle } from "node:module";',
        'const require = __createRequireForBundle(import.meta.url);',
        'const __META_DIR_VALUE__ = process.env.CC_HAHA_DIST_DIR || ".";',
      ].join('\n'),
    },
    plugins: [
      entryTransform,
      androidAccessRoots,
      androidSkillSchemaFallback,
      tsExtensionRemap,
      resolveCompat,
    ],
    logLevel: 'info',
    // 上游个别依赖（bun-types 等）是类型层，运行时无影响
    loader: { '.ts': 'ts', '.tsx': 'tsx', '.md': 'text' },
  }
  await esbuild.build({
    ...base,
    outfile: path.join(
      root,
      'dist',
      `${name}${noMinify ? '-nomin' : ''}${cjs ? '-cjs' : ''}.js`,
    ),
    format: cjs ? 'cjs' : 'esm',
    define: {
      __ENTRY_MAIN__: cjs ? 'false' : 'true',
      __META_DIR__: '__META_DIR_VALUE__',
    },
  })
  console.log(`[build] ${name}.js done`)

  if (split && (name === 'cli' || name === 'server')) {
    const splitDir = path.join(root, 'dist', `${name}-split`)
    await esbuild.build({
      ...base,
      outdir: splitDir,
      entryNames: '[name]-[hash]',
      splitting: true,
      chunkNames: 'chunks/[name]-[hash]',
      define: {
        __ENTRY_MAIN__: 'true',
        __META_DIR__: '__META_DIR_VALUE__',
      },
    })
    // 固定入口文件名（hash 名不便引用；入口是目录里除 chunks 外的 .js）
    for (const f of fs.readdirSync(splitDir)) {
      if (f !== 'chunks' && f.endsWith('.js') && f !== `${name}.js`) {
        fs.renameSync(path.join(splitDir, f), path.join(splitDir, `${name}.js`))
      }
    }
    console.log(`[build] ${name}-split done`)
  }
}

async function main() {
  const watch = process.argv.includes('--watch')
  const only = process.env.CC_HAHA_ONLY
  const targets = Object.entries(ENTRIES).filter(
    ([name]) => !only || name === only,
  )
  const tasks = targets.map(([name, rel]) => buildOne(name, rel, watch))
  await Promise.all(tasks)
  console.log('[build] 全部完成 →', path.join(root, 'dist'))
}

main().catch((e) => {
  console.error('[build] 失败:', e)
  process.exit(1)
})
