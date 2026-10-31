import { useState, type FormEvent, type ReactNode } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { ErrorNote } from '../components';

interface TokenResponse {
  accessToken: string;
}

function AuthCard({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="auth-page">
      <div className="card auth-card">
        <div className="brand">Kasa</div>
        <h1>{title}</h1>
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

  if (isSignedIn) {
    return <Navigate to="/" replace />;
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const { accessToken } = await api<TokenResponse>('/auth/login', { method: 'POST', body: { email, password } });
      signIn(accessToken);
    } catch (e) {
      setError(e instanceof ApiError && e.status === 401 ? 'E-posta veya parola hatalı.' : (e as Error).message);
      setBusy(false);
    }
  };

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
      <p className="muted auth-switch">Hesabınız yok mu? <Link to="/register">Kayıt olun</Link></p>
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
