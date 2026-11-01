import { useState, type FormEvent, type ReactNode } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { ErrorNote } from '../components';

interface TokenResponse {
  accessToken: string;
}

interface LoginResponse {
  accessToken: string | null;
  twoFactorRequired: boolean;
  challengeToken: string | null;
}

function AuthCard({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="auth-page">
      <div className="card auth-card">
        <div className="brand">Kasa</div>
        <h1>{title}</h1>
        <p className="note note-warning auth-notice">
          Bu bir portföy demosudur. Gerçek e-posta veya başka yerde kullandığınız bir parola girmeyin.
        </p>
        {children}
      </div>
    </div>
  );
}

export function Login() {
  const { isSignedIn, signIn } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [challenge, setChallenge] = useState<string | null>(null);
  const [code, setCode] = useState('');

  if (isSignedIn) {
    return <Navigate to="/" replace />;
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const response = await api<LoginResponse>('/auth/login', { method: 'POST', body: { email, password } });
      if (response.twoFactorRequired) {
        setChallenge(response.challengeToken);
        setBusy(false);
      } else {
        signIn(response.accessToken!);
      }
    } catch (e) {
      setError(e instanceof ApiError && e.status === 401 ? 'E-posta veya parola hatalı.' : (e as Error).message);
      setBusy(false);
    }
  };

  const submitCode = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const { accessToken } = await api<TokenResponse>('/auth/login/two-factor', { method: 'POST', body: { challengeToken: challenge, code } });
      signIn(accessToken);
    } catch (e) {
      if (e instanceof ApiError && e.status === 429) {
        setError('Çok fazla hatalı deneme. 15 dakika sonra yeniden deneyin.');
      } else {
        setError(e instanceof ApiError && e.status === 401 ? 'Kod hatalı veya süresi dolmuş.' : (e as Error).message);
      }
      setCode('');
      setBusy(false);
    }
  };

  if (challenge) {
    return (
      <AuthCard title="Doğrulama kodu">
        <form onSubmit={submitCode} className="stack">
          <label className="field">
            Kod
            <input required autoFocus autoComplete="one-time-code" maxLength={32} value={code} onChange={(e) => setCode(e.target.value)} />
            <span className="hint">Doğrulama uygulamanızdaki 6 haneli kod veya bir kurtarma kodu.</span>
          </label>
          <ErrorNote message={error} />
          <button type="submit" className="button button-primary button-block" disabled={busy}>
            {busy ? 'Doğrulanıyor…' : 'Giriş yap'}
          </button>
        </form>
      </AuthCard>
    );
  }

  return (
    <AuthCard title="Giriş yapın">
      <form onSubmit={submit} className="stack">
        <label className="field">
          E-posta
          <input type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label className="field">
          Parola
          <input type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        </label>
        <ErrorNote message={error} />
        <button type="submit" className="button button-primary button-block" disabled={busy}>
          {busy ? 'Giriş yapılıyor…' : 'Giriş yap'}
        </button>
      </form>
      <p className="muted auth-switch"><Link to="/forgot-password">Parolamı unuttum</Link></p>
      <p className="muted auth-switch">Hesabınız yok mu? <Link to="/register">Kayıt olun</Link></p>
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
    <AuthCard title="Parolamı unuttum">
      {sent ? (
        <p className="note note-success" role="status">
          Bu e-postayla bir hesap varsa sıfırlama bağlantısı oluşturuldu. Bağlantı 30 dakika geçerlidir.
          Demo gerçek e-posta göndermez; bağlantı yalnızca yerel çalıştırmada konsola yazılır.
        </p>
      ) : (
        <form onSubmit={submit} className="stack">
          <label className="field">
            E-posta
            <input type="email" required autoFocus autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} />
          </label>
          <ErrorNote message={error} />
          <button type="submit" className="button button-primary button-block" disabled={busy}>
            {busy ? 'Gönderiliyor…' : 'Sıfırlama bağlantısı iste'}
          </button>
        </form>
      )}
      <p className="muted auth-switch"><Link to="/login">Giriş sayfasına dön</Link></p>
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
      setError(e instanceof ApiError && e.status === 410 ? 'Bu bağlantı kullanılmış veya süresi dolmuş. Yeni bir bağlantı isteyin.' : (e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthCard title="Yeni parola belirleyin">
      {done ? (
        <p className="note note-success" role="status">Parolanız değişti ve açık oturumlar kapandı. <Link to="/login">Giriş yapın</Link></p>
      ) : (
        <form onSubmit={submit} className="stack">
          <label className="field">
            Yeni parola
            <input type="password" required autoFocus minLength={12} maxLength={72} autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
            <span className="hint">En az 12 karakter</span>
          </label>
          <ErrorNote message={error} />
          <button type="submit" className="button button-primary button-block" disabled={busy}>
            {busy ? 'Kaydediliyor…' : 'Parolayı değiştir'}
          </button>
        </form>
      )}
    </AuthCard>
  );
}

export function Register() {
  const { isSignedIn, signIn } = useAuth();
  const [businessName, setBusinessName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (isSignedIn) {
    return <Navigate to="/" replace />;
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
      setError(e instanceof ApiError && e.status === 409 ? 'Bu e-posta zaten kayıtlı.' : (e as Error).message);
      setBusy(false);
    }
  };

  return (
    <AuthCard title="İşletme hesabı oluşturun">
      <form onSubmit={submit} className="stack">
        <label className="field">
          İşletme adı
          <input required maxLength={200} autoComplete="organization" value={businessName} onChange={(e) => setBusinessName(e.target.value)} />
        </label>
        <label className="field">
          E-posta
          <input type="email" required autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label className="field">
          Parola
          <input type="password" required minLength={12} maxLength={72} autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
          <span className="hint">En az 12 karakter</span>
        </label>
        <ErrorNote message={error} />
        <button type="submit" className="button button-primary button-block" disabled={busy}>
          {busy ? 'Oluşturuluyor…' : 'Hesap oluştur'}
        </button>
      </form>
      <p className="muted auth-switch">Hesabınız var mı? <Link to="/login">Giriş yapın</Link></p>
    </AuthCard>
  );
}
