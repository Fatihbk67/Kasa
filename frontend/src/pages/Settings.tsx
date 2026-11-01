import { useEffect, useState, type FormEvent } from 'react';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { ErrorNote, PageHeader } from '../components';
import { isOwner, ROLE_LABELS, useMe } from '../me';

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

export function Settings() {
  const { me } = useMe();
  return (
    <>
      <PageHeader title="Ayarlar" description={me ? `${me.email} · ${ROLE_LABELS[me.role]}` : undefined} />
      <div className="settings">
        {isOwner(me) && <BusinessSettings />}
        <PasswordSettings />
      </div>
    </>
  );
}
