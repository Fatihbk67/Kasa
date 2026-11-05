import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom';
import { asAdminSession, setAdminToken } from '../adminApi';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { Brand, ErrorNote, usePageTitle } from '../components';
import { GoogleButton, GoogleSignup, type GoogleResult } from '../GoogleSignIn';
import { LanguagePicker, t } from '../i18n';
import { LegalLinks } from './Legal';

interface TokenResponse {
  accessToken: string;
}

interface LoginResponse {
  accessToken: string | null;
  twoFactorRequired: boolean;
  challengeToken: string | null;
  adminChallengeToken: string | null;
}

function AuthCard({ title, children }: { title: string; children: ReactNode }) {
  usePageTitle(title);
  return (
    <div className="auth-page">
      <div className="card auth-card">
        <div className="auth-card-top">
          <Brand />
          <LanguagePicker compact />
        </div>
        <h1>{title}</h1>
        <p className="note note-warning auth-notice">
          {t('Bu bir portföy demosudur. Doğrulama e-postası girdiğiniz adrese gönderilir; başka yerde kullandığınız bir parola girmeyin.')}
        </p>
        {children}
      </div>
      <LegalLinks />
    </div>
  );
}

export function Login() {
  const { isSignedIn, signIn } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const navigate = useNavigate();
  const [response, setResponse] = useState<LoginResponse | null>(null);
  const [step, setStep] = useState<'password' | 'choose' | 'merchant-code' | 'admin-code' | 'google-signup'>('password');
  const [code, setCode] = useState('');
  const [googleResult, setGoogleResult] = useState<GoogleResult | null>(null);

  if (isSignedIn) {
    return <Navigate to="/" replace />;
  }

  const continueWithGoogle = (result: GoogleResult) => {
    if (result.registrationRequired) {
      setGoogleResult(result);
      setStep('google-signup');
      return;
    }
    const login: LoginResponse = { accessToken: result.accessToken, twoFactorRequired: result.twoFactorRequired, challengeToken: result.challengeToken, adminChallengeToken: null };
    setResponse(login);
    enterMerchant(login);
  };

  const enterMerchant = (result: LoginResponse) => {
    if (result.twoFactorRequired) {
      setStep('merchant-code');
    } else {
      signIn(result.accessToken!);
    }
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const result = await api<LoginResponse>('/auth/login', { method: 'POST', body: { email, password } });
      setResponse(result);
      setBusy(false);
      const merchant = result.accessToken !== null || result.twoFactorRequired;
      if (result.adminChallengeToken && merchant) {
        setStep('choose');
      } else if (result.adminChallengeToken) {
        setStep('admin-code');
      } else {
        enterMerchant(result);
      }
    } catch (e) {
      setError(e instanceof ApiError && e.status === 401 ? t('E-posta veya parola hatalı.') : (e as Error).message);
      setBusy(false);
    }
  };

  const submitCode = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (step === 'admin-code') {
        const { accessToken } = await api<TokenResponse>('/auth/login/admin', {
          method: 'POST',
          body: { challengeToken: response!.adminChallengeToken, code },
        });
        setAdminToken(asAdminSession(accessToken));
        navigate('/admin');
        return;
      }
      const { accessToken } = await api<TokenResponse>('/auth/login/two-factor', {
        method: 'POST',
        body: { challengeToken: response!.challengeToken, code },
      });
      signIn(accessToken);
    } catch (e) {
      if (e instanceof ApiError && e.status === 429) {
        setError(t('Çok fazla hatalı deneme. 15 dakika sonra yeniden deneyin.'));
      } else {
        setError(e instanceof ApiError && e.status === 401 ? t('Kod hatalı veya süresi dolmuş.') : (e as Error).message);
      }
      setCode('');
      setBusy(false);
    }
  };

  if (step === 'google-signup' && googleResult) {
    return (
      <AuthCard title={t('İşletme hesabı oluşturun')}>
        <GoogleSignup result={googleResult} onCancel={() => setStep('password')} />
      </AuthCard>
    );
  }

  if (step === 'choose') {
    return (
      <AuthCard title={t('Nereye gitmek istiyorsunuz?')}>
        <p className="muted">{t('Bu e-posta hem bir işletmeye hem de platform yönetimine ait.')}</p>
        <div className="stack">
          <button type="button" className="button button-primary button-block" onClick={() => enterMerchant(response!)}>{t('İşletme paneli')}</button>
          <button type="button" className="button button-block" onClick={() => setStep('admin-code')}>{t('Platform yönetimi')}</button>
        </div>
      </AuthCard>
    );
  }

  if (step === 'merchant-code' || step === 'admin-code') {
    return (
      <AuthCard title={step === 'admin-code' ? t('Yönetici doğrulama kodu') : t('Doğrulama kodu')}>
        <form onSubmit={submitCode} className="stack">
          <label className="field">
            {t('Kod')}
            <input required autoFocus autoComplete="one-time-code" maxLength={32} value={code} onChange={(e) => setCode(e.target.value)} />
            <span className="hint">
              {step === 'admin-code' ? t('Doğrulama uygulamanızdaki 6 haneli yönetici kodu.') : t('Doğrulama uygulamanızdaki 6 haneli kod veya bir kurtarma kodu.')}
            </span>
          </label>
          <ErrorNote message={error} />
          <button type="submit" className="button button-primary button-block" disabled={busy}>
            {busy ? t('Doğrulanıyor…') : t('Giriş yap')}
          </button>
        </form>
      </AuthCard>
    );
  }

  return (
    <AuthCard title={t('Giriş yapın')}>
      <GoogleButton context="signin" onResult={continueWithGoogle} />
      <form onSubmit={submit} className="stack">
        <label className="field">
          {t('E-posta')}
          <input type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label className="field">
          {t('Parola')}
          <input type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        </label>
        <ErrorNote message={error} />
        <button type="submit" className="button button-primary button-block" disabled={busy}>
          {busy ? t('Giriş yapılıyor…') : t('Giriş yap')}
        </button>
      </form>
      <p className="muted auth-switch"><Link to="/forgot-password">{t('Parolamı unuttum')}</Link></p>
      <p className="muted auth-switch">{t('Hesabınız yok mu?')} <Link to="/register">{t('Kayıt olun')}</Link></p>
    </AuthCard>
  );
}

export function ForgotPassword() {
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api('/auth/password-reset/request', { method: 'POST', body: { email } });
      setSent(true);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthCard title={t('Parolamı unuttum')}>
      {sent ? (
        <p className="note note-success" role="status">
          {t('Bu e-postayla bir hesap varsa sıfırlama bağlantısı gönderildi. Bağlantı 30 dakika geçerlidir; gelen kutunuzu ve istenmeyen klasörünü kontrol edin.')}
        </p>
      ) : (
        <form onSubmit={submit} className="stack">
          <label className="field">
            {t('E-posta')}
            <input type="email" required autoFocus autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} />
          </label>
          <ErrorNote message={error} />
          <button type="submit" className="button button-primary button-block" disabled={busy}>
            {busy ? t('Gönderiliyor…') : t('Sıfırlama bağlantısı iste')}
          </button>
        </form>
      )}
      <p className="muted auth-switch"><Link to="/login">{t('Giriş sayfasına dön')}</Link></p>
    </AuthCard>
  );
}

export function ResetPassword() {
  const { token } = useParams();
  const [password, setPassword] = useState('');
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api('/auth/password-reset/confirm', { method: 'POST', body: { token, newPassword: password } });
      setDone(true);
    } catch (e) {
      setError(e instanceof ApiError && e.status === 410 ? t('Bu bağlantı kullanılmış veya süresi dolmuş. Yeni bir bağlantı isteyin.') : (e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthCard title={t('Yeni parola belirleyin')}>
      {done ? (
        <p className="note note-success" role="status">{t('Parolanız değişti ve açık oturumlar kapandı.')} <Link to="/login">{t('Giriş yapın')}</Link></p>
      ) : (
        <form onSubmit={submit} className="stack">
          <label className="field">
            {t('Yeni parola')}
            <input type="password" required autoFocus minLength={12} maxLength={72} autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
            <span className="hint">{t('En az 12 karakter')}</span>
          </label>
          <ErrorNote message={error} />
          <button type="submit" className="button button-primary button-block" disabled={busy}>
            {busy ? t('Kaydediliyor…') : t('Parolayı değiştir')}
          </button>
        </form>
      )}
    </AuthCard>
  );
}

export function Register() {
  const { isSignedIn, signIn } = useAuth();
  const navigate = useNavigate();
  const [businessName, setBusinessName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [googleResult, setGoogleResult] = useState<GoogleResult | null>(null);

  if (isSignedIn) {
    return <Navigate to="/" replace />;
  }

  // A Google account that already has a Kasa account simply signs in; one with a second factor finishes on the login page.
  const continueWithGoogle = (result: GoogleResult) => {
    if (result.registrationRequired) {
      setGoogleResult(result);
    } else if (result.accessToken) {
      signIn(result.accessToken);
    } else {
      navigate('/login');
    }
  };

  if (googleResult) {
    return (
      <AuthCard title={t('İşletme hesabı oluşturun')}>
        <GoogleSignup result={googleResult} onCancel={() => setGoogleResult(null)} />
      </AuthCard>
    );
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const { accessToken } = await api<TokenResponse>('/auth/register', {
        method: 'POST',
        body: { businessName, email, password },
      });
      signIn(accessToken);
    } catch (e) {
      setError(e instanceof ApiError && e.status === 409 ? t('Bu e-posta zaten kayıtlı.') : (e as Error).message);
      setBusy(false);
    }
  };

  return (
    <AuthCard title={t('İşletme hesabı oluşturun')}>
      <GoogleButton context="signup" onResult={continueWithGoogle} />
      <form onSubmit={submit} className="stack">
        <label className="field">
          {t('İşletme adı')}
          <input required maxLength={200} autoComplete="organization" value={businessName} onChange={(e) => setBusinessName(e.target.value)} />
        </label>
        <label className="field">
          {t('E-posta')}
          <input type="email" required autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label className="field">
          {t('Parola')}
          <input type="password" required minLength={12} maxLength={72} autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
          <span className="hint">{t('En az 12 karakter')}</span>
        </label>
        <p className="hint">{t('Yeni işletmeler platform onayından sonra ödeme alabilir. Bu sürede panel açıktır.')}</p>
        <ErrorNote message={error} />
        <button type="submit" className="button button-primary button-block" disabled={busy}>
          {busy ? t('Oluşturuluyor…') : t('Hesap oluştur')}
        </button>
      </form>
      <p className="muted auth-switch">{t('Hesabınız var mı?')} <Link to="/login">{t('Giriş yapın')}</Link></p>
    </AuthCard>
  );
}

/**
 * Opened from the link in the verification email. React's development mode runs effects twice, and a link works
 * only once, so the request is guarded to go out a single time.
 */
export function VerifyEmail() {
  const { token } = useParams();
  const { isSignedIn } = useAuth();
  const [state, setState] = useState<'checking' | 'done' | 'used' | 'error'>('checking');
  const [error, setError] = useState<string | null>(null);
  const sent = useRef(false);

  useEffect(() => {
    if (sent.current) {
      return;
    }
    sent.current = true;
    api('/auth/verify-email', { method: 'POST', body: { token } })
      .then(() => setState('done'))
      .catch((e: unknown) => {
        if (e instanceof ApiError && e.status === 410) {
          setState('used');
        } else {
          setState('error');
          setError((e as Error).message);
        }
      });
  }, [token]);

  return (
    <AuthCard title={t('E-posta doğrulama')}>
      {state === 'checking' && <p className="muted" role="status">{t('Bağlantınız kontrol ediliyor…')}</p>}
      {state === 'done' && (
        <p className="note note-success" role="status">
          {t('E-posta adresiniz doğrulandı.')} {isSignedIn ? <Link to="/onboarding">{t('İşletme başvurusuna devam edin')}</Link> : <Link to="/login">{t('Giriş yapın')}</Link>}
        </p>
      )}
      {state === 'used' && (
        <p className="note note-warning">
          {t('Bu bağlantı kullanılmış veya süresi dolmuş. Adresiniz zaten doğrulanmış olabilir; değilse panelden yeni bir bağlantı isteyin.')}
          {' '}<Link to={isSignedIn ? '/' : '/login'}>{isSignedIn ? t('Panele dön') : t('Giriş yapın')}</Link>
        </p>
      )}
      {state === 'error' && <ErrorNote message={error} />}
    </AuthCard>
  );
}
