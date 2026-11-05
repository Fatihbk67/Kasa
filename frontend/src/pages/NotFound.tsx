import { Link, useLocation } from 'react-router-dom';
import { useAuth } from '../auth';
import { Brand, usePageTitle } from '../components';

/** A mistyped or outdated address says so, instead of silently landing somewhere else. */
export function NotFound() {
  const { isSignedIn } = useAuth();
  const { pathname } = useLocation();
  usePageTitle('Sayfa bulunamadı');
  return (
    <div className="auth-page">
      <div className="card auth-card not-found">
        <Brand />
        <p className="not-found-code" aria-hidden="true">404</p>
        <h1>Sayfa bulunamadı</h1>
        <p className="muted">
          <code>{pathname}</code> adresinde bir sayfa yok. Bağlantı eksik kopyalanmış ya da sayfa kaldırılmış olabilir.
        </p>
        <Link className="button button-primary button-block" to={isSignedIn ? '/' : '/login'}>
          {isSignedIn ? 'Panele dön' : 'Giriş sayfasına git'}
        </Link>
      </div>
    </div>
  );
}
