import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from './auth';
import { canOperate, MeProvider, ROLE_LABELS, useMe } from './me';

const NAV = [
  { to: '/', label: 'Genel bakış', end: true, operatorOnly: false },
  { to: '/analytics', label: 'Analiz', end: false, operatorOnly: false },
  { to: '/payments', label: 'Ödemeler', end: false, operatorOnly: false },
  { to: '/disputes', label: 'İtirazlar', end: false, operatorOnly: false },
  { to: '/customers', label: 'Müşteriler', end: false, operatorOnly: false },
  { to: '/payment-links', label: 'Ödeme bağlantıları', end: false, operatorOnly: false },
  { to: '/payouts', label: 'Hesap kesimleri', end: false, operatorOnly: false },
  { to: '/developers', label: 'Geliştiriciler', end: false, operatorOnly: true },
  { to: '/team', label: 'Ekip', end: false, operatorOnly: true },
  { to: '/audit', label: 'Denetim günlüğü', end: false, operatorOnly: true },
  { to: '/settings', label: 'Ayarlar', end: false, operatorOnly: false },
];

function Shell() {
  const { signOut } = useAuth();
  const { me } = useMe();

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">Kasa</div>
        <nav aria-label="Ana menü">
          {NAV.filter((item) => !item.operatorOnly || canOperate(me)).map((item) => (
            <NavLink key={item.to} to={item.to} end={item.end} className="nav-link">
              {item.label}
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-footer">
          <span className="merchant-name">{me?.tenantName}</span>
          {me && <span className="hint">{me.email} · {ROLE_LABELS[me.role]}</span>}
          <span className="test-mode">Test modu</span>
          <button type="button" className="link-button" onClick={signOut}>Çıkış yap</button>
        </div>
      </aside>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}

export function Layout() {
  return (
    <MeProvider>
      <Shell />
    </MeProvider>
  );
}
