/**
 * Android 构建前导脚本（node --import 加载）。
 *
 * 背景：Node.js 在 Android 上 process.platform 返回 'android'，而 esbuild 等
 * 工具按 Node 官方平台名解析平台专属二进制包，于是会去找 @esbuild/android-arm64
 * （npm 上不存在），导致构建失败。
 *
 * 处理：Android 内核即 Linux，把 process.platform 统一伪装为 'linux'，
 * 使 @esbuild/linux-arm64 被正确加载。与 compat/setup.ts 的运行时伪装保持一致。
 *
 * 用法：node --import ./build/android-preload.mjs build/build.mjs
 */
if (process.platform === 'android') {
  Object.defineProperty(process, 'platform', {
    value: 'linux',
    configurable: true,
  })
}
