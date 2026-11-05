import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, ApiError } from './api';
import { useAuth } from './auth';
import { ErrorNote } from './components';
import { lang, t } from './i18n';

/** What our server answers once Google has vouched for the person. */
export interface GoogleResult {
  accessToken: string | null;
  twoFactorRequired: boolean;
  challengeToken: string | null;
  registrationRequired: boolean;
  signupToken: string | null;
  email: string | null;
  suggestedName: string | null;
}

interface GoogleIdApi {
  initialize(options: { client_id: string; callback: (response: { credential: string }) => void; ux_mode?: 'popup'; context?: string }): void;
  renderButton(element: HTMLElement, options: Record<string, string | number>): void;
}

declare global {
  interface Window {
    google?: { accounts: { id: GoogleIdApi } };
  }
}

const SCRIPT = 'https://accounts.google.com/gsi/client';
let providers: Promise<Providers> | null = null;
let script: Promise<void> | null = null;
let scriptLang: string | null = null;

/** The ways in that the server offers besides email and password. */
export interface Providers {
  googleClientId: string | null;
  demo: boolean;
}

/** Asked once per page load: whether Google sign-in is set up (and its public client id), and whether the demo is on. */
export function signInProviders(): Promise<Providers> {
  providers ??= api<Providers>('/auth/providers').catch(() => ({ googleClientId: null, demo: false }));
  return providers;
}

function googleClientId(): Promise<string | null> {
  return signInProviders().then((result) => result.googleClientId);
}

/** Google fixes the button's language when its script loads, so another language means loading it again. */
function loadScript(): Promise<void> {
  if (script && scriptLang !== lang()) {
    document.querySelectorAll(`script[src^="${SCRIPT}"]`).forEach((element) => element.remove());
    delete window.google;
    script = null;
  }
  scriptLang = lang();
  script ??= new Promise((resolve, reject) => {
    const element = document.createElement('script');
    element.src = `${SCRIPT}?hl=${lang()}`;
    element.async = true;
    element.onload = () => resolve();
    element.onerror = () => {
      script = null;
      reject(new Error('Google could not be reached'));
    };
    document.head.appendChild(element);
  });
  return script;
}

/**
 * Google's own button, which keeps the account picker on Google's side. Nothing is shown when the server has no
 * client id configured, or when Google cannot be reached, so the email form always remains.
 */
export function GoogleButton({ context, onResult }: { context: 'signin' | 'signup'; onResult: (result: GoogleResult) => void }) {
  const container = useRef<HTMLDivElement>(null);
  const [available, setAvailable] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const latest = useRef(onResult);
  latest.current = onResult;

  useEffect(() => {
    let cancelled = false;
    googleClientId().then(async (clientId) => {
      if (!clientId || cancelled) {
        return;
      }
      try {
        await loadScript();
      } catch {
        return;
      }
      const google = window.google?.accounts.id;
      if (!google || cancelled || !container.current) {
        return;
      }
      google.initialize({
        client_id: clientId,
        ux_mode: 'popup',
        context,
        callback: async ({ credential }) => {
          setError(null);
          try {
            latest.current(await api<GoogleResult>('/auth/google', { method: 'POST', body: { credential } }));
          } catch (e) {
            setError(e instanceof ApiError && e.status === 401
              ? t('Google hesabınız doğrulanamadı. E-posta adresi Google tarafından onaylanmış bir hesapla yeniden deneyin.')
              : (e as Error).message);
          }
        },
      });
      google.renderButton(container.current, {
        type: 'standard',
        theme: 'outline',
        size: 'large',
        shape: 'rectangular',
        text: context === 'signup' ? 'signup_with' : 'continue_with',
        logo_alignment: 'center',
        locale: lang(),
        width: Math.min(400, Math.round(container.current.getBoundingClientRect().width) || 320),
      });
      setAvailable(true);
    });
    return () => {
      cancelled = true;
    };
  }, [context]);

  return (
    <div className={available ? 'google-signin' : 'google-signin google-signin-pending'}>
      <div ref={container} className="google-button" />
      <ErrorNote message={error} />
      {available && <p className="divider-label">{t('veya e-postayla')}</p>}
    </div>
  );
}

/** The one question Google cannot answer for a new account: the name of the business. */
export function GoogleSignup({ result, onCancel }: { result: GoogleResult; onCancel: () => void }) {
  const { signIn } = useAuth();
  const [businessName, setBusinessName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const { accessToken } = await api<{ accessToken: string }>('/auth/google/register', {
        method: 'POST',
        body: { signupToken: result.signupToken, businessName },
      });
      signIn(accessToken);
    } catch (e) {
      setError(e instanceof ApiError && e.status === 409 ? t('Bu e-postayla az önce bir hesap açıldı. Giriş sayfasından Google ile devam edin.')
        : e instanceof ApiError && e.status === 401 ? t('İşlemin süresi doldu. Google ile yeniden başlayın.')
          : (e as Error).message);
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} className="stack">
      <p className="note note-success" role="status">
        {t('Google hesabınız doğrulandı: {email}. Hesabınızı açmak için işletmenizin adını yazın.', { email: result.email ?? '' })}
      </p>
      <label className="field">
        {t('İşletme adı')}
        <input required autoFocus maxLength={200} autoComplete="organization" value={businessName}
          placeholder={t('Örnek: Moda Kahve')}
          onChange={(e) => setBusinessName(e.target.value)} />
      </label>
      <p className="hint">{t('Yeni işletmeler platform onayından sonra ödeme alabilir. Bu sürede panel açıktır.')}</p>
      <ErrorNote message={error} />
      <button type="submit" className="button button-primary button-block" disabled={busy}>
        {busy ? t('Oluşturuluyor…') : t('Hesabı oluştur')}
      </button>
      <button type="button" className="link-button" onClick={onCancel} disabled={busy}>{t('Vazgeç')}</button>
    </form>
  );
}
