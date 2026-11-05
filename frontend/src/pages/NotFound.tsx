import { Link, useLocation } from 'react-router-dom';
import { useAuth } from '../auth';
import { Brand, usePageTitle } from '../components';
import { t } from '../i18n';

/** A mistyped or outdated address says so, instead of silently landing somewhere else. */
export function NotFound() {
  const { isSignedIn } = useAuth();
  const { pathname } = useLocation();
  usePageTitle(t('Sayfa bulunamadı'));
  return (
    <div className="auth-page">
      <div className="card auth-card not-found">
        <Brand />
        <p className="not-found-code" aria-hidden="true">404</p>
        <h1>{t('Sayfa bulunamadı')}</h1>
        <p className="muted">
          {t('{path} adresinde bir sayfa yok. Bağlantı eksik kopyalanmış ya da sayfa kaldırılmış olabilir.', { path: pathname })}
        </p>
        <Link className="button button-primary button-block" to={isSignedIn ? '/' : '/login'}>
          {isSignedIn ? t('Panele dön') : t('Giriş sayfasına git')}
        </Link>
      </div>
    </div>
  );
}
