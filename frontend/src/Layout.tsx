import { NavLink, Outlet } from 'react-router-dom';
import { api } from './api';
import { useAuth } from './auth';
import type { Tenant } from './types';
import { useFetch } from './useFetch';

const NAV = [
  { to: '/', label: 'Genel bakış', end: true },
  { to: '/analytics', label: 'Analiz', end: false },
  { to: '/payments', label: 'Ödemeler', end: false },
  { to: '/payouts', label: 'Hesap kesimleri', end: false },
  { to: '/developers', label: 'Geliştiriciler', end: false },
];

export function Layout() {
  const { signOut } = useAuth();
  const tenant = useFetch(() => api<Tenant>('/tenant'), []);

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">Kasa</div>
        <nav aria-label="Ana menü">
          {NAV.map((item) => (
            <NavLink key={item.to} to={item.to} end={item.end} className="nav-link">
              {item.label}
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-footer">
          <span className="merchant-name">{tenant.data?.name}</span>
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
