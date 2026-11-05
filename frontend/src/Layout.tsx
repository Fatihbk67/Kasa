import { useCallback, useState } from 'react';
import { CommandPalette, useCommandShortcut, type Command } from './CommandPalette';
import { Link, NavLink, Outlet } from 'react-router-dom';
import { useAuth } from './auth';
import { canOperate, isOwner, MeProvider, ROLE_LABELS, useMe } from './me';
import { NotificationBell } from './Notifications';
import { api, ApiError } from './api';
import { ToastProvider, useToast } from './toast';
import { Brand } from './components';
import { DemoBanner, isDemo } from './Demo';
import { saveTheme, storedTheme, THEME_LABELS, type ThemeChoice } from './theme';
import { LanguagePicker, t, tk } from './i18n';

interface NavItem {
  to: string;
  label: string;
  end?: boolean;
  operatorOnly?: boolean;
  ownerOnly?: boolean;
}

const NAV: { title: string | null; items: NavItem[] }[] = [
  {
    title: null,
    items: [
      { to: '/', label: tk('Genel bakış'), end: true },
      { to: '/analytics', label: tk('Analiz') },
    ],
  },
  {
    title: tk('Para'),
    items: [
      { to: '/payments', label: tk('Ödemeler') },
      { to: '/subscriptions', label: tk('Abonelikler') },
      { to: '/payment-links', label: tk('Ödeme bağlantıları') },
      { to: '/disputes', label: tk('İtirazlar') },
      { to: '/risk', label: tk('Risk ve dolandırıcılık'), operatorOnly: true },
      { to: '/payouts', label: tk('Hesap kesimleri') },
    ],
  },
  {
    title: tk('Müşteriler'),
    items: [{ to: '/customers', label: tk('Müşteriler') }],
  },
  {
    title: tk('Hesap'),
    items: [
      { to: '/developers', label: tk('Geliştiriciler'), operatorOnly: true },
      { to: '/team', label: tk('Ekip'), operatorOnly: true },
      { to: '/audit', label: tk('Denetim günlüğü'), operatorOnly: true },
      { to: '/onboarding', label: tk('İşletme başvurusu'), ownerOnly: true },
      { to: '/settings', label: tk('Ayarlar') },
    ],
  },
];

/** Shown until the address is confirmed. Resending is limited on the server, so a busy button cannot flood a mailbox. */
function VerifyEmailBanner({ email }: { email: string }) {
  const notify = useToast();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const resend = async () => {
    setBusy(true);
    setError(null);
    try {
      await api('/settings/email-verification', { method: 'POST' });
      notify(t('{email} adresine yeni bir doğrulama bağlantısı gönderildi.', { email }));
    } catch (e) {
      setError(e instanceof ApiError && e.status === 429 ? t('Çok sık istendi. Biraz sonra yeniden deneyin.') : (e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="note note-warning verify-banner" role="status">
      <span>
        <strong>{t('E-posta adresinizi doğrulayın.')}</strong> {t('{email} adresine bir doğrulama bağlantısı gönderdik. Doğrulamadan işletme başvurusu yapılamaz.', { email })}
        {error && <> {error}</>}
      </span>
      <button type="button" className="button button-small" onClick={resend} disabled={busy}>{busy ? t('Gönderiliyor…') : t('Yeniden gönder')}</button>
    </div>
  );
}

function ThemePicker() {
  const [theme, setTheme] = useState<ThemeChoice>(storedTheme);
  const choose = (choice: ThemeChoice) => {
    setTheme(choice);
    saveTheme(choice);
  };
  return (
    <div className="segmented theme-picker" role="group" aria-label={t('Görünüm')}>
      {(Object.keys(THEME_LABELS) as ThemeChoice[]).map((choice) => (
        <button key={choice} type="button" aria-pressed={theme === choice} onClick={() => choose(choice)}>
          {t(THEME_LABELS[choice])}
        </button>
      ))}
    </div>
  );
}

const IS_MAC = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform);

function Shell() {
  const { signOut } = useAuth();
  const { me } = useMe();
  // Only matters on a phone, where the menu would otherwise push every page below the fold.
  const [menuOpen, setMenuOpen] = useState(false);
  const [searching, setSearching] = useState(false);
  const openSearch = useCallback(() => setSearching(true), []);
  useCommandShortcut(openSearch);

  const visible = NAV.map((group) => ({
    ...group,
    items: group.items.filter((item) => (!item.operatorOnly || canOperate(me)) && (!item.ownerOnly || isOwner(me))),
  }));
  const pages: Command[] = visible.flatMap((group) => group.items.map((item) => ({ to: item.to, label: t(item.label), group: group.title ? t(group.title) : t('Panel') })));

  return (
    <div className="shell">
      <aside className={menuOpen ? 'sidebar sidebar-open' : 'sidebar'}>
        <div className="sidebar-top">
          <Brand />
          <NotificationBell />
          <button type="button" className="button button-small menu-toggle" aria-expanded={menuOpen} aria-controls="main-menu"
            onClick={() => setMenuOpen(!menuOpen)}>
            {menuOpen ? t('Kapat') : t('Menü')}
          </button>
        </div>
        <button type="button" className="search-trigger sidebar-menu" onClick={openSearch} aria-keyshortcuts={IS_MAC ? 'Meta+K' : 'Control+K'}>
          <span>{t('Ara…')}</span>
          <kbd>{IS_MAC ? '⌘K' : 'Ctrl K'}</kbd>
        </button>
        <nav id="main-menu" aria-label={t('Ana menü')} className="sidebar-menu">
          {visible.map((group) => {
            if (group.items.length === 0) {
              return null;
            }
            return (
              <div className="nav-group" key={group.title ?? 'start'}>
                {group.title && <span className="nav-title">{t(group.title)}</span>}
                {group.items.map((item) => (
                  <NavLink key={item.to} to={item.to} end={item.end} className="nav-link" onClick={() => setMenuOpen(false)}>
                    {t(item.label)}
                  </NavLink>
                ))}
              </div>
            );
          })}
        </nav>
        <div className="sidebar-footer sidebar-menu">
          <span className="merchant-name">{me?.tenantName}</span>
          {me && <span className="hint">{me.email} · {t(ROLE_LABELS[me.role])}</span>}
          <span className="test-mode">{t('Test modu')}</span>
          <div className="preference-row">
            <ThemePicker />
            <LanguagePicker compact />
          </div>
          <button type="button" className="link-button" onClick={signOut}>{t('Çıkış yap')}</button>
        </div>
      </aside>
      <main className="content">
        {isDemo(me) && <DemoBanner />}
        {me && !me.emailVerified && <VerifyEmailBanner email={me.email} />}
        {me?.tenantStatus === 'PENDING' && (
          <p className="note note-warning" role="status">
            {t('Hesabınız platform onayı bekliyor. Bu sürede paneli gezebilir, ekibinizi ve ayarlarınızı hazırlayabilirsiniz; onaydan sonra ödeme alabilirsiniz.')}
            {isOwner(me) && <> <Link to="/onboarding">{t('İşletme başvurusuna git')}</Link></>}
          </p>
        )}
        {me?.tenantStatus === 'SUSPENDED' && (
          <p className="note note-danger" role="alert">
            {me.tenantStatusReason ? t('Hesabınız askıya alındı: {reason}', { reason: me.tenantStatusReason }) : t('Hesabınız askıya alındı.')}
            {' '}{t('Yeni ödeme alınamaz ve hesap kesimi durduruldu. İadeler ve itirazlar çalışmaya devam eder.')}
          </p>
        )}
        <Outlet />
      </main>
      {searching && <CommandPalette pages={pages} onClose={() => setSearching(false)} />}
    </div>
  );
}

export function Layout() {
  return (
    <MeProvider>
      <ToastProvider>
        <Shell />
      </ToastProvider>
    </MeProvider>
  );
}
