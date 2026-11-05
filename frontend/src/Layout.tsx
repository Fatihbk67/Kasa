import { useState } from 'react';
import { Link, NavLink, Outlet } from 'react-router-dom';
import { useAuth } from './auth';
import { canOperate, isOwner, MeProvider, ROLE_LABELS, useMe } from './me';
import { NotificationBell } from './Notifications';
import { api, ApiError } from './api';
import { ToastProvider, useToast } from './toast';
import { Brand } from './components';
import { saveTheme, storedTheme, THEME_LABELS, type ThemeChoice } from './theme';

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
      { to: '/', label: 'Genel bakış', end: true },
      { to: '/analytics', label: 'Analiz' },
    ],
  },
  {
    title: 'Para',
    items: [
      { to: '/payments', label: 'Ödemeler' },
      { to: '/subscriptions', label: 'Abonelikler' },
      { to: '/payment-links', label: 'Ödeme bağlantıları' },
      { to: '/disputes', label: 'İtirazlar' },
      { to: '/risk', label: 'Risk ve dolandırıcılık', operatorOnly: true },
      { to: '/payouts', label: 'Hesap kesimleri' },
    ],
  },
  {
    title: 'Müşteriler',
    items: [{ to: '/customers', label: 'Müşteriler' }],
  },
  {
    title: 'Hesap',
    items: [
      { to: '/developers', label: 'Geliştiriciler', operatorOnly: true },
      { to: '/team', label: 'Ekip', operatorOnly: true },
      { to: '/audit', label: 'Denetim günlüğü', operatorOnly: true },
      { to: '/onboarding', label: 'İşletme başvurusu', ownerOnly: true },
      { to: '/settings', label: 'Ayarlar' },
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
      notify(`${email} adresine yeni bir doğrulama bağlantısı gönderildi.`);
    } catch (e) {
      setError(e instanceof ApiError && e.status === 429 ? 'Çok sık istendi. Biraz sonra yeniden deneyin.' : (e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="note note-warning verify-banner" role="status">
      <span>
        <strong>E-posta adresinizi doğrulayın.</strong> {email} adresine bir doğrulama bağlantısı gönderdik. Doğrulamadan işletme başvurusu yapılamaz.
        {error && <> {error}</>}
      </span>
      <button type="button" className="button button-small" onClick={resend} disabled={busy}>{busy ? 'Gönderiliyor…' : 'Yeniden gönder'}</button>
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
    <div className="segmented theme-picker" role="group" aria-label="Görünüm">
      {(Object.keys(THEME_LABELS) as ThemeChoice[]).map((choice) => (
        <button key={choice} type="button" aria-pressed={theme === choice} onClick={() => choose(choice)}>
          {THEME_LABELS[choice]}
        </button>
      ))}
    </div>
  );
}

function Shell() {
  const { signOut } = useAuth();
  const { me } = useMe();
  // Only matters on a phone, where the menu would otherwise push every page below the fold.
  const [menuOpen, setMenuOpen] = useState(false);

  return (
    <div className="shell">
      <aside className={menuOpen ? 'sidebar sidebar-open' : 'sidebar'}>
        <div className="sidebar-top">
          <Brand />
          <NotificationBell />
          <button type="button" className="button button-small menu-toggle" aria-expanded={menuOpen} aria-controls="main-menu"
            onClick={() => setMenuOpen(!menuOpen)}>
            {menuOpen ? 'Kapat' : 'Menü'}
          </button>
        </div>
        <nav id="main-menu" aria-label="Ana menü" className="sidebar-menu">
          {NAV.map((group) => {
            const items = group.items.filter((item) => (!item.operatorOnly || canOperate(me)) && (!item.ownerOnly || isOwner(me)));
            if (items.length === 0) {
              return null;
            }
            return (
              <div className="nav-group" key={group.title ?? 'start'}>
                {group.title && <span className="nav-title">{group.title}</span>}
                {items.map((item) => (
                  <NavLink key={item.to} to={item.to} end={item.end} className="nav-link" onClick={() => setMenuOpen(false)}>
                    {item.label}
                  </NavLink>
                ))}
              </div>
            );
          })}
        </nav>
        <div className="sidebar-footer sidebar-menu">
          <span className="merchant-name">{me?.tenantName}</span>
          {me && <span className="hint">{me.email} · {ROLE_LABELS[me.role]}</span>}
          <span className="test-mode">Test modu</span>
          <ThemePicker />
          <button type="button" className="link-button" onClick={signOut}>Çıkış yap</button>
        </div>
      </aside>
      <main className="content">
        {me && !me.emailVerified && <VerifyEmailBanner email={me.email} />}
        {me?.tenantStatus === 'PENDING' && (
          <p className="note note-warning" role="status">
            Hesabınız platform onayı bekliyor. Bu sürede paneli gezebilir, ekibinizi ve ayarlarınızı hazırlayabilirsiniz; onaydan sonra ödeme alabilirsiniz.
            {isOwner(me) && <> <Link to="/onboarding">İşletme başvurusuna git</Link></>}
          </p>
        )}
        {me?.tenantStatus === 'SUSPENDED' && (
          <p className="note note-danger" role="alert">
            Hesabınız askıya alındı{me.tenantStatusReason ? `: ${me.tenantStatusReason}` : '.'} Yeni ödeme alınamaz ve hesap kesimi durduruldu. İadeler ve itirazlar çalışmaya devam eder.
          </p>
        )}
        <Outlet />
      </main>
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
