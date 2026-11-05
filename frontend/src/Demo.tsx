import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from './api';
import { useAuth } from './auth';
import { ErrorNote } from './components';
import { signInProviders } from './GoogleSignIn';
import { t } from './i18n';
import type { Me } from './types';

/** The shared sample business's visitor account; its address can never receive mail. */
export const DEMO_EMAIL = 'ziyaretci@demo.invalid';

export function isDemo(me: Me | null): boolean {
  return me?.email === DEMO_EMAIL;
}

/** Opens the sample business without an account, for anyone who only wants to look around. */
export function DemoButton() {
  const { signIn } = useAuth();
  const [available, setAvailable] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    void signInProviders().then((providers) => current && setAvailable(providers.demo));
    return () => {
      current = false;
    };
  }, []);

  if (!available) {
    return null;
  }

  const open = async () => {
    setBusy(true);
    setError(null);
    try {
      const { accessToken } = await api<{ accessToken: string }>('/auth/demo', { method: 'POST' });
      signIn(accessToken);
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  return (
    <div className="demo-entry">
      <div>
        <strong>{t('Hesap açmadan göz atın')}</strong>
        <span className="hint">{t('Örnek bir işletmenin 45 günlük ödemeleri, analizi ve hesap kesimleri. Yalnızca görüntülenir.')}</span>
      </div>
      <button type="button" className="button button-block" onClick={open} disabled={busy}>
        {busy ? t('Açılıyor…') : t('Demo hesabıyla dene')}
      </button>
      <ErrorNote message={error} />
    </div>
  );
}

/** Says plainly that this is the shared sample, and how to get an account that can actually take payments. */
export function DemoBanner() {
  const { signOut } = useAuth();
  const navigate = useNavigate();
  return (
    <p className="note note-info demo-banner" role="status">
      <span>
        <strong>{t('Demo hesabındasınız.')}</strong>{' '}
        {t('Örnek verileri gezebilir ama değiştiremezsiniz. Ödeme almak, ekip kurmak ve API anahtarı oluşturmak için kendi ücretsiz hesabınızı açın.')}
      </span>
      <button type="button" className="button button-small button-primary" onClick={() => {
        signOut();
        navigate('/register');
      }}>{t('Hesap aç')}</button>
    </p>
  );
}
