import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { getDesktopHost } from '@/lib/desktopHost'
import { publicAssetPath } from '@/lib/publicAsset'

/**
 * [cc-haha-android 定制] 「关于安卓端适配」页。
 *
 * 与上游的 AboutSettings（关于 cc-haha）并列，构成设置里的两个关于页。
 * 正文用中文硬编码：本页描述的是中文项目的移植层，面向中文用户；
 * 如后续需要多语言，再把文案抽到 i18n 键。
 */
export function AndroidPortAbout() {
  const [version, setVersion] = useState('?')
  useEffect(() => {
    void getDesktopHost().app.getVersion().then(setVersion).catch(() => undefined)
  }, [])
  const open = (url: string) => {
    void getDesktopHost().shell.open(url).catch(() => window.open(url, '_blank'))
  }

  return <div className="flex flex-col gap-4">
    <Card radius="xl" surface="low" padding="none" className="w-full p-6">
      <h2 className="text-xl font-semibold">cc-haha Android 全量版</h2>
      <p className="mt-2 text-sm text-[var(--color-text-secondary)]">版本 {version} · Apache-2.0</p>
      <p className="mt-4 text-sm leading-6">
        安卓端移植层：Kotlin 壳 + Bun→Node 兼容层 + 本地工具链集成。
        无需连接电脑，在手机上完成编码、编译与运行，支持 Linux / Windows / Android 三端编译。
      </p>
      <div className="mt-4">
        <Button onClick={() => open('https://github.com/Vantasi-Imilus/cc-haha-for-Android')}>
          打开项目仓库
        </Button>
      </div>
    </Card>

    <Card radius="xl" surface="low" padding="none" className="w-full p-6">
      <h3 className="text-base font-semibold">内核说明</h3>
      <p className="mt-2 text-sm leading-6">
        本应用不内置 Claude Code / cc-haha 的内核产物，由使用者在首次启动时自行获取源码
        并在设备端构建（支持 GitCode 镜像、官方包、本地压缩包三种来源）。
        内核产物会生成校验清单并自动备份，每次启动校验完整性，损坏时自动从备份恢复。
      </p>
    </Card>

    <Card radius="xl" surface="low" padding="none" className="w-full p-6">
      <h3 className="text-base font-semibold">支持这个项目</h3>
      <p className="mt-2 text-sm text-[var(--color-text-secondary)]">
        如果这个项目帮到了你，欢迎请我喝杯奶茶：
      </p>
      <img
        src={publicAssetPath('sponsor/wechat-reward.png')}
        alt="微信赞赏码"
        className="mx-auto mt-4 w-56 rounded-lg"
      />
      <p className="mt-4 text-xs leading-5 text-[var(--color-text-tertiary)]">
        赞助完全自愿：不构成购买服务，不影响项目许可与功能（本项目以 Apache-2.0 发布，
        所有人平等获得相同权利），也不承诺功能优先级或时间表。
      </p>
    </Card>
  </div>
}
