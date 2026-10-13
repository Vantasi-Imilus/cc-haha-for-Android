import { useEffect } from 'react'
import { useTranslation } from '@/i18n'
import { useUIStore } from '@/stores/uiStore'
import { SettingsPill } from '@/components/settings/SettingsSection'
import { ProviderSettings } from './ProviderSettings'
import { H5GeneralSettings } from './H5GeneralSettings'
// [cc-haha-android 定制] H5 版补回两个关于页：
// 上游只在桌面版（DesktopSettings）提供 about 标签，H5 版没有。
// 本移植与上游作者有约定需保留 cc-haha 印记，故并列「关于 cc-haha」与「关于安卓端适配」。
import { AboutSettings } from './AboutSettings'
import { AndroidPortAbout } from './AndroidPortAbout'

export function H5Settings() {
  const t = useTranslation()
  const active = useUIStore((state) => state.activeSettingsTab)
  const pending = useUIStore((state) => state.pendingSettingsTab)
  const requested = pending ?? active
  const selected =
    requested === 'general' ? 'general'
      : requested === 'about' ? 'about'
        : requested === 'aboutPort' ? 'aboutPort'
          : 'providers'
  useEffect(() => {
    useUIStore.getState().setActiveSettingsTab(selected)
    if (pending) useUIStore.getState().setPendingSettingsTab(null)
  }, [pending, selected])
  return <div className="flex min-h-0 min-w-0 flex-1 flex-col bg-[var(--color-surface)]">
    <nav aria-label={t('sidebar.settings')} className="flex shrink-0 [&_button]:min-h-11 flex-wrap gap-2 border-b border-[var(--color-border)] p-3">
      <SettingsPill selected={selected === 'providers'} onClick={() => useUIStore.getState().setActiveSettingsTab('providers')}>{t('settings.tab.providers')}</SettingsPill>
      <SettingsPill selected={selected === 'general'} onClick={() => useUIStore.getState().setActiveSettingsTab('general')}>{t('settings.tab.general')}</SettingsPill>
      {/* [cc-haha-android 定制] 两个关于页：上游项目 / 安卓端适配 */}
      <SettingsPill selected={selected === 'about'} onClick={() => useUIStore.getState().setActiveSettingsTab('about')}>{t('settings.tab.about')}</SettingsPill>
      <SettingsPill selected={selected === 'aboutPort'} onClick={() => useUIStore.getState().setActiveSettingsTab('aboutPort')}>{t('settings.tab.aboutPort')}</SettingsPill>
    </nav>
    <div className="min-h-0 min-w-0 flex-1 overflow-y-auto overflow-x-hidden px-4 py-5 sm:px-6">
      <p className="mb-5 text-xs leading-5 text-[var(--color-text-tertiary)]">{t('h5Settings.scope')}</p>
      {selected === 'providers' ? <ProviderSettings browserMode />
        : selected === 'about' ? <AboutSettings />
          : selected === 'aboutPort' ? <AndroidPortAbout />
            : <H5GeneralSettings />}
    </div>
  </div>
}
