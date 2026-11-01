import { useEffect, useState, type FormEvent } from 'react';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { CopyButton, ErrorNote, Loading, PageHeader } from '../components';
import { isOwner, ROLE_LABELS, useMe } from '../me';
import { useFetch } from '../useFetch';

function BusinessSettings() {
  const { me, reload } = useMe();
  const [name, setName] = useState('');
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (me) {
      setName(me.tenantName);
    }
  }, [me]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setSaved(false);
    setError(null);
    try {
      await api('/settings/tenant', { method: 'PUT', body: { name } });
      setSaved(true);
      reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="card settings-card">
      <h2>İşletme</h2>
      <form onSubmit={submit} className="stack">
        <label className="field">
          İşletme adı
          <input required maxLength={200} value={name} onChange={(e) => setName(e.target.value)} />
          <span className="hint">Müşterileriniz ödeme sayfasında bu adı görür.</span>
        </label>
        <ErrorNote message={error} />
        {saved && <p className="note note-success" role="status">Kaydedildi.</p>}
        <div><button type="submit" className="button button-primary" disabled={busy}>{busy ? 'Kaydediliyor…' : 'Kaydet'}</button></div>
      </form>
    </section>
  );
}

function PasswordSettings() {
  const { signIn } = useAuth();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setSaved(false);
    setError(null);
    try {
      const { accessToken } = await api<{ accessToken: string }>('/settings/password', {
        method: 'POST',
        body: { currentPassword: current, newPassword: next },
      });
      signIn(accessToken);
      setCurrent('');
      setNext('');
      setSaved(true);
    } catch (e) {
      setError(e instanceof ApiError && e.status === 422 ? 'Mevcut parola hatalı.' : (e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="card settings-card">
      <h2>Parola</h2>
      <form onSubmit={submit} className="stack">
        <label className="field">
          Mevcut parola
          <input type="password" required autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} />
        </label>
        <label className="field">
          Yeni parola
          <input type="password" required minLength={12} maxLength={72} autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} />
          <span className="hint">En az 12 karakter. Değiştirince diğer cihazlardaki oturumlarınız kapanır.</span>
        </label>
        <ErrorNote message={error} />
        {saved && <p className="note note-success" role="status">Parolanız değişti, diğer oturumlar kapatıldı.</p>}
        <div><button type="submit" className="button button-primary" disabled={busy}>{busy ? 'Değiştiriliyor…' : 'Parolayı değiştir'}</button></div>
      </form>
    </section>
  );
}

interface TwoFactorStatus {
  enabled: boolean;
  recoveryCodesLeft: number;
}

function TwoFactorSettings() {
  const { signIn } = useAuth();
  const status = useFetch(() => api<TwoFactorStatus>('/settings/two-factor'), []);
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [setup, setSetup] = useState<{ secret: string } | null>(null);
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(e instanceof ApiError && e.status === 422 ? 'Parola veya kod hatalı.' : (e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const begin = (event: FormEvent) => {
    event.preventDefault();
    void run(async () => {
      setSetup(await api<{ secret: string }>('/settings/two-factor/setup', { method: 'POST', body: { password } }));
      setPassword('');
    });
  };

  const enable = (event: FormEvent) => {
    event.preventDefault();
    void run(async () => {
      const result = await api<{ recoveryCodes: string[]; accessToken: string }>('/settings/two-factor/enable', { method: 'POST', body: { code } });
      signIn(result.accessToken);
      setRecoveryCodes(result.recoveryCodes);
      setSetup(null);
      setCode('');
      status.reload();
    });
  };

  const disable = (event: FormEvent) => {
    event.preventDefault();
    void run(async () => {
      const { accessToken } = await api<{ accessToken: string }>('/settings/two-factor/disable', { method: 'POST', body: { password, code } });
      signIn(accessToken);
      setPassword('');
      setCode('');
      setRecoveryCodes(null);
      status.reload();
    });
  };

  return (
    <section className="card settings-card">
      <h2>İki adımlı giriş</h2>
      <ErrorNote message={status.error} />
      {recoveryCodes && (
        <div className="note note-warning">
          <strong>Kurtarma kodlarınızı şimdi saklayın</strong>
          <p>Telefonunuza ulaşamazsanız her kod bir kez giriş sağlar. Bir daha gösterilmeyecek.</p>
          <code className="secret recovery-codes">{recoveryCodes.join('\n')}</code>
          <div><CopyButton value={recoveryCodes.join('\n')} /></div>
        </div>
      )}
      {status.loading && !status.data ? <Loading /> : status.data?.enabled ? (
        <form onSubmit={disable} className="stack">
          <p className="note note-success" role="status">Açık. {status.data.recoveryCodesLeft} kurtarma kodu kaldı.</p>
          <label className="field">
            Parola
            <input type="password" required autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
          </label>
          <label className="field">
            Doğrulama kodu
            <input inputMode="numeric" required autoComplete="one-time-code" maxLength={6} value={code} onChange={(e) => setCode(e.target.value)} />
          </label>
          <ErrorNote message={error} />
          <div><button type="submit" className="button" disabled={busy}>{busy ? 'Kapatılıyor…' : 'İki adımlı girişi kapat'}</button></div>
        </form>
      ) : setup ? (
        <form onSubmit={enable} className="stack">
          <p className="muted">Doğrulama uygulamanızda (Google Authenticator, Authy) elle ekleme seçeneğine bu anahtarı girin:</p>
          <div className="secret-row">
            <code className="secret">{setup.secret}</code>
            <CopyButton value={setup.secret} />
          </div>
          <label className="field">
            Uygulamadaki 6 haneli kod
            <input inputMode="numeric" required autoFocus autoComplete="one-time-code" maxLength={6} value={code} onChange={(e) => setCode(e.target.value)} />
            <span className="hint">Açınca diğer cihazlardaki oturumlarınız kapanır.</span>
          </label>
          <ErrorNote message={error} />
          <div><button type="submit" className="button button-primary" disabled={busy}>{busy ? 'Doğrulanıyor…' : 'Aç'}</button></div>
        </form>
      ) : (
        <form onSubmit={begin} className="stack">
          <p className="muted">Parolanız çalınsa bile telefonunuzdaki kod olmadan girilemez.</p>
          <label className="field">
            Parola
            <input type="password" required autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
          </label>
          <ErrorNote message={error} />
          <div><button type="submit" className="button button-primary" disabled={busy}>{busy ? 'Hazırlanıyor…' : 'Kuruluma başla'}</button></div>
        </form>
      )}
    </section>
  );
}

export function Settings() {
  const { me } = useMe();
  return (
    <>
      <PageHeader title="Ayarlar" description={me ? `${me.email} · ${ROLE_LABELS[me.role]}` : undefined} />
      <div className="settings">
        {isOwner(me) && <BusinessSettings />}
        <PasswordSettings />
        <TwoFactorSettings />
      </div>
    </>
  );
}
