import { useState } from 'react'
import { ArrowLeft, PackageCheck } from 'lucide-react'
import { useTranslation } from '@/i18n'
import { Button } from '@/components/ui/Button'
import { SegmentedControl } from '@/components/ui/SegmentedControl'
import { Connectors } from '@/pages/Connectors'
import { useMarketStore } from '@/stores/marketStore'
import { Market } from '@/pages/Market'
import { InstalledSkills } from '@/pages/InstalledSkills'

export function ExtensionMarket() {
  const t = useTranslation()
  const query = useMarketStore(state => state.query)
  const installedFilter = useMarketStore(state => state.filters.installed)
  const [section, setSection] = useState<'plugins' | 'skills'>('plugins')
  const [managing, setManaging] = useState(false)
  const myLabel = t(section === 'plugins' ? 'extensions.myPlugins' : 'extensions.mySkills')
  return <section className="flex h-full min-h-0 flex-col bg-[var(--color-surface)]">
    <header className="flex flex-wrap items-center gap-x-6 gap-y-2 border-b border-[var(--color-border)] px-6 py-3">
      <h1 className="text-lg font-semibold text-[var(--color-text-primary)]">{managing ? myLabel : t('sidebar.extensions')}</h1>
      <SegmentedControl label={t('extensions.sections')} appearance="underline" value={section} onChange={setSection} items={[{ value: 'plugins', label: t('extensions.plugins') }, { value: 'skills', label: t('extensions.skills') }]} />
      <div className="ml-auto">
        <Button size="base" variant="secondary" icon={managing ? <ArrowLeft size={15} aria-hidden="true" /> : <PackageCheck size={15} aria-hidden="true" />} onClick={() => setManaging(value => !value)}>
          {managing ? t('extensions.browse') : myLabel}
        </Button>
      </div>
    </header>
    <div className="flex min-h-0 flex-1 flex-col">
      {managing
        ? section === 'plugins' ? <Connectors key="installed" management /> : <div className="h-full overflow-y-auto"><div className="mx-auto max-w-7xl px-5 py-6 lg:px-8"><InstalledSkills /></div></div>
        : section === 'plugins' ? <Connectors key="catalog" mode="plugins" /> : <Market featured={<Connectors mode="skills" embedded externalQuery={query} installedFilter={installedFilter} />} />}
    </div>
  </section>
}
