import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { adminApi, asAdminSession, getAdminToken, setAdminToken } from '../adminApi';
import { api, ApiError } from '../api';
import { EmptyState, ErrorNote, FormDialog, formatDate, formatDateTime, Loading, OneTimeSecret, PageHeader } from '../components';
import { formatMoney } from '../money';
import type { PlatformOverview, ReconciliationDetail, ReconciliationRun } from '../types';
import { useFetch } from '../useFetch';

const KIND_LABELS: Record<string, string> = {
  MISSING_IN_BANK: 'Bizde var, bankada yok',
  MISSING_IN_KASA: 'Bankada var, bizde yok',
  AMOUNT_MISMATCH: 'Tutar farklı',
  DETAILS_MISMATCH: 'Tür veya para birimi farklı',
  DUPLICATE_IN_BANK: 'Dosyada çift kayıt',
  LEDGER_DRIFT: 'Bakiye defterle uyuşmuyor',
};

function yesterday(): string {
  const date = new Date();
  date.setDate(date.getDate() - 1);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

function amountOrDash(value: number | null): string {
  return value === null ? '—' : formatMoney(value, 'TRY');
}

function AdminShell({ children, onSignOut }: { children: ReactNode; onSignOut: () => void }) {
  return (
    <div className="admin-page">
      <header className="admin-bar">
        <Link className="brand admin-brand" to="/admin">Kasa · Platform yönetimi</Link>
        <button type="button" className="link-button" onClick={onSignOut}>Çıkış yap</button>
      </header>
      <main className="content admin-content">{children}</main>
    </div>
  );
}

function AdminSignIn({ onSignedIn }: { onSignedIn: () => void }) {
  const [useKey, setUseKey] = useState(false);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [token, setToken] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (useKey) {
        await adminApi('/overview', 'GET', token);
        setAdminToken(token);
      } else {
        const { accessToken } = await api<{ accessToken: string }>('/public/admin-login', { method: 'POST', body: { email, password, code } });
        setAdminToken(asAdminSession(accessToken));
      }
      onSignedIn();
    } catch (e) {
      const wrong = e instanceof ApiError && e.status === 401;
      setError(wrong ? (useKey ? 'Anahtar geçersiz.' : 'E-posta, parola veya kod hatalı.') : (e as Error).message);
      setBusy(false);
    }
  };

  return (
    <div className="auth-page">
      <div className="card auth-card">
        <div className="brand">Kasa</div>
        <h1>Platform yönetimi</h1>
        <p className="muted">Bu bölüm işletmeler için değil, platformu işleten ekip içindir.</p>
        <form onSubmit={submit} className="stack">
          {useKey ? (
            <label className="field">
              Yönetici anahtarı
              <input type="password" required autoComplete="off" value={token} onChange={(e) => setToken(e.target.value)} />
              <span className="hint">Makineler ve ilk hesabı oluşturmak içindir. İşlemler kişiye yazılmaz.</span>
            </label>
          ) : (
            <>
              <label className="field">
                E-posta
                <input type="email" required autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} />
              </label>
              <label className="field">
                Parola
                <input type="password" required autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
              </label>
              <label className="field">
                Doğrulama kodu
                <input inputMode="numeric" required autoComplete="one-time-code" maxLength={6} value={code} onChange={(e) => setCode(e.target.value)} />
              </label>
            </>
          )}
          <ErrorNote message={error} />
          <button type="submit" className="button button-primary button-block" disabled={busy}>
            {busy ? 'Doğrulanıyor…' : 'Giriş yap'}
          </button>
        </form>
        <p className="muted auth-switch">
          <button type="button" className="link-button" onClick={() => setUseKey(!useKey)}>
            {useKey ? 'Hesabımla gireyim' : 'Yönetici anahtarıyla gir'}
          </button>
        </p>
      </div>
    </div>
  );
}

interface AdminAccount {
  id: string;
  email: string;
  disabled: boolean;
  createdAt: string;
}

interface AdminAuditEntry {
  id: number;
  createdAt: string;
  actor: string;
  action: string;
  target: string | null;
  ip: string | null;
}

const ADMIN_ACTIONS: Record<string, string> = {
  ADMIN_CREATED: 'Yönetici oluşturuldu',
  ADMIN_DISABLED: 'Yönetici kapatıldı',
  ADMIN_LOGIN: 'Giriş yapıldı',
  ADMIN_LOGIN_FAILED: 'Başarısız giriş',
  ADMIN_LOGIN_LOCKED: 'Kilitli hesaba giriş denemesi',
  SETTLEMENT_RUN: 'Hesap kesimi çalıştırıldı',
  MERCHANT_APPROVED: 'İşletme onaylandı',
  MERCHANT_SUSPENDED: 'İşletme askıya alındı',
  MERCHANT_REACTIVATED: 'İşletme yeniden açıldı',
  RECONCILIATION_RUN: 'Mutabakat çalıştırıldı',
};

interface MerchantRow {
  id: string;
  name: string;
  status: 'PENDING' | 'ACTIVE' | 'SUSPENDED';
  statusReason: string | null;
  createdAt: string;
  reviewedAt: string | null;
  reviewedBy: string | null;
  ownerEmail: string | null;
  memberCount: number;
  paymentCount: number;
  captured: number;
}

const MERCHANT_STATUS: Record<MerchantRow['status'], { label: string; tone: string }> = {
  PENDING: { label: 'Onay bekliyor', tone: 'warning' },
  ACTIVE: { label: 'Etkin', tone: 'success' },
  SUSPENDED: { label: 'Askıda', tone: 'danger' },
};

function Merchants({ onChanged }: { onChanged: () => void }) {
  const [filter, setFilter] = useState<'' | MerchantRow['status']>('PENDING');
  const merchants = useFetch(() => adminApi<MerchantRow[]>(`/merchants${filter ? `?status=${filter}` : ''}`), [filter]);
  const [suspending, setSuspending] = useState<MerchantRow | null>(null);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);

  const act = async (merchant: MerchantRow, action: 'approve' | 'reactivate') => {
    setError(null);
    try {
      await adminApi(`/merchants/${merchant.id}/${action}`, 'POST');
      merchants.reload();
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const suspend = async () => {
    await adminApi(`/merchants/${suspending!.id}/suspend`, 'POST', undefined, { reason });
    setSuspending(null);
    setReason('');
    merchants.reload();
    onChanged();
  };

  return (
    <>
      <div className="section-header">
        <h2>İşletmeler</h2>
        <label className="field field-inline">
          Durum
          <select value={filter} onChange={(e) => setFilter(e.target.value as typeof filter)}>
            <option value="PENDING">Onay bekleyen</option>
            <option value="ACTIVE">Etkin</option>
            <option value="SUSPENDED">Askıda</option>
            <option value="">Tümü</option>
          </select>
        </label>
      </div>
      <ErrorNote message={merchants.error ?? error} />
      {merchants.loading ? <Loading /> : merchants.data && merchants.data.length > 0 ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">İşletme</th>
                <th scope="col">Durum</th>
                <th scope="col" className="numeric hide-narrow">Ödeme</th>
                <th scope="col" className="hide-narrow">Kayıt</th>
                <th scope="col"><span className="visually-hidden">İşlemler</span></th>
              </tr>
            </thead>
            <tbody>
              {merchants.data.map((merchant) => (
                <tr key={merchant.id}>
                  <td>
                    {merchant.name}
                    <span className="hint row-hint">{merchant.ownerEmail ?? '—'} · {merchant.memberCount} üye</span>
                    {merchant.statusReason && <span className="hint row-hint">Gerekçe: {merchant.statusReason}</span>}
                  </td>
                  <td>
                    <span className={`badge badge-${MERCHANT_STATUS[merchant.status].tone}`}>{MERCHANT_STATUS[merchant.status].label}</span>
                    {merchant.reviewedBy && <span className="hint row-hint">{merchant.reviewedBy === 'platform-admin' ? 'Ortak anahtar' : merchant.reviewedBy}</span>}
                  </td>
                  <td className="numeric hide-narrow">
                    {merchant.paymentCount}
                    <span className="hint row-hint">{formatMoney(merchant.captured, 'TRY')}</span>
                  </td>
                  <td className="hide-narrow muted">{formatDateTime(merchant.createdAt)}</td>
                  <td className="numeric">
                    <span className="row-actions">
                      {merchant.status === 'PENDING' && <button type="button" className="button button-small button-primary" onClick={() => act(merchant, 'approve')}>Onayla</button>}
                      {merchant.status === 'SUSPENDED' && <button type="button" className="button button-small" onClick={() => act(merchant, 'reactivate')}>Yeniden aç</button>}
                      {merchant.status !== 'SUSPENDED' && (
                        <button type="button" className="button button-small" onClick={() => setSuspending(merchant)}>
                          {merchant.status === 'PENDING' ? 'Reddet' : 'Askıya al'}
                        </button>
                      )}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : !merchants.error && <div className="card"><EmptyState title={filter === 'PENDING' ? 'Onay bekleyen işletme yok' : 'İşletme yok'} /></div>}

      {suspending && (
        <FormDialog title={suspending.status === 'PENDING' ? 'Başvuruyu reddet' : 'İşletmeyi askıya al'} submitLabel="Onayla" danger
          onClose={() => setSuspending(null)} onSubmit={suspend}>
          <p><strong>{suspending.name}</strong> yeni ödeme alamaz ve hesap kesimi durur. İadeler ve itirazlar çalışmaya devam eder.</p>
          <label className="field">
            Gerekçe
            <textarea required autoFocus maxLength={500} rows={3} value={reason} onChange={(e) => setReason(e.target.value)} />
            <span className="hint">İşletme bu metni panelinde görür.</span>
          </label>
        </FormDialog>
      )}
    </>
  );
}

function AdminAccounts() {
  const accounts = useFetch(() => adminApi<AdminAccount[]>('/accounts'), []);
  const audit = useFetch(() => adminApi<AdminAuditEntry[]>('/audit-log'), []);
  const [creating, setCreating] = useState(false);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [created, setCreated] = useState<{ email: string; totpSecret: string } | null>(null);
  const [disabling, setDisabling] = useState<AdminAccount | null>(null);

  const create = async () => {
    try {
      const result = await adminApi<{ email: string; totpSecret: string }>('/accounts', 'POST', undefined, { email, password });
      setCreated(result);
      setCreating(false);
      setEmail('');
      setPassword('');
      accounts.reload();
      audit.reload();
    } catch (e) {
      throw e instanceof ApiError && e.status === 409 ? new Error('Bu e-postanın zaten yönetici hesabı var.') : e;
    }
  };

  const disable = async () => {
    try {
      await adminApi(`/accounts/${disabling!.id}/disable`, 'POST');
    } catch (e) {
      throw e instanceof ApiError && e.status === 409 ? new Error('Kendi hesabınızı kapatamazsınız.') : e;
    }
    setDisabling(null);
    accounts.reload();
    audit.reload();
  };

  return (
    <>
      <div className="section-header">
        <h2>Yöneticiler</h2>
        <button type="button" className="button button-small" onClick={() => setCreating(true)}>Yönetici ekle</button>
      </div>
      {created && <OneTimeSecret title={`${created.email} için kurulum anahtarı`} value={created.totpSecret} />}
      <ErrorNote message={accounts.error} />
      {accounts.loading ? <Loading /> : accounts.data && accounts.data.length > 0 ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">E-posta</th>
                <th scope="col">Durum</th>
                <th scope="col" className="hide-narrow">Oluşturulma</th>
                <th scope="col"><span className="visually-hidden">İşlemler</span></th>
              </tr>
            </thead>
            <tbody>
              {accounts.data.map((account) => (
                <tr key={account.id}>
                  <td>{account.email}</td>
                  <td>{account.disabled ? <span className="badge badge-neutral">Kapalı</span> : <span className="badge badge-success">Etkin</span>}</td>
                  <td className="hide-narrow muted">{formatDateTime(account.createdAt)}</td>
                  <td className="numeric">
                    {!account.disabled && <button type="button" className="button button-small" onClick={() => setDisabling(account)}>Kapat</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : !accounts.error && <div className="card"><EmptyState title="Henüz yönetici yok">Ortak anahtar yerine kişiye özel hesap açın.</EmptyState></div>}

      <div className="section-header"><h2>Yönetici işlem kayıtları</h2></div>
      <ErrorNote message={audit.error} />
      {audit.data && audit.data.length > 0 ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">İşlem</th>
                <th scope="col">Yapan</th>
                <th scope="col" className="hide-narrow">Tarih</th>
              </tr>
            </thead>
            <tbody>
              {audit.data.map((entry) => (
                <tr key={entry.id}>
                  <td>
                    {ADMIN_ACTIONS[entry.action] ?? entry.action}
                    {entry.target && <span className="hint row-hint">{entry.target}</span>}
                  </td>
                  <td>
                    {entry.actor === 'platform-admin' ? 'Ortak anahtar' : entry.actor}
                    {entry.ip && <span className="hint row-hint">{entry.ip}</span>}
                  </td>
                  <td className="hide-narrow muted">{formatDateTime(entry.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : !audit.loading && !audit.error && <div className="card"><EmptyState title="Henüz kayıt yok" /></div>}

      {creating && (
        <FormDialog title="Yönetici ekle" submitLabel="Oluştur" onClose={() => setCreating(false)} onSubmit={create}>
          <label className="field">
            E-posta
            <input type="email" required autoFocus value={email} onChange={(e) => setEmail(e.target.value)} />
          </label>
          <label className="field">
            Parola
            <input type="password" required minLength={12} maxLength={72} autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
            <span className="hint">En az 12 karakter. İki adımlı doğrulama zorunludur; kurulum anahtarı oluşturunca gösterilir.</span>
          </label>
        </FormDialog>
      )}
      {disabling && (
        <FormDialog title="Yöneticiyi kapat" submitLabel="Kapat" danger onClose={() => setDisabling(null)} onSubmit={disable}>
          <p><strong>{disabling.email}</strong> erişimini hemen kaybeder ve açık oturumları kapanır.</p>
        </FormDialog>
      )}
    </>
  );
}

function useAdminSession() {
  const [signedIn, setSignedIn] = useState(() => getAdminToken() !== null);
  const signOut = () => {
    setAdminToken(null);
    setSignedIn(false);
  };
  return { signedIn, signOut, markSignedIn: () => setSignedIn(true) };
}

function Tile({ label, value, alert }: { label: string; value: number; alert?: boolean }) {
  return (
    <div className="card stat">
      <span className="stat-label">{label}</span>
      <span className={alert && value > 0 ? 'stat-value stat-value-small negative' : 'stat-value stat-value-small'}>
        {value.toLocaleString('tr-TR')}
      </span>
    </div>
  );
}

function AdminDashboard({ onSignOut }: { onSignOut: () => void }) {
  const navigate = useNavigate();
  const overview = useFetch(() => adminApi<PlatformOverview>('/overview'), []);
  const runs = useFetch(() => adminApi<ReconciliationRun[]>('/reconciliation/runs'), []);
  const [reconciling, setReconciling] = useState(false);
  const [settling, setSettling] = useState(false);
  const [date, setDate] = useState(yesterday);
  const [notice, setNotice] = useState<string | null>(null);
  // Remounts the accounts section, so its log shows a decision made in the merchants section.
  const [refreshed, setRefreshed] = useState(0);

  useEffect(() => {
    if (overview.errorStatus === 401) {
      onSignOut();
    }
  }, [overview.errorStatus, onSignOut]);

  const runReconciliation = async () => {
    const detail = await adminApi<ReconciliationDetail>(`/reconciliation/runs?date=${date}`, 'POST');
    navigate(`/admin/reconciliation/${detail.run.id}`);
  };

  const runSettlement = async () => {
    const result = await adminApi<{ payoutsCreated: number }>('/settlements/run', 'POST');
    setSettling(false);
    setNotice(result.payoutsCreated > 0 ? `${result.payoutsCreated} hesap kesimi oluşturuldu.` : 'Yeni hesap kesimi oluşmadı: bugünkü hesap kesimi yapılmış veya ödenecek tutar yok.');
    overview.reload();
  };

  return (
    <AdminShell onSignOut={onSignOut}>
      <PageHeader
        title="Genel durum"
        actions={(
          <>
            <button type="button" className="button" onClick={() => setSettling(true)}>Hesap kesimini çalıştır</button>
            <button type="button" className="button button-primary" onClick={() => setReconciling(true)}>Mutabakat çalıştır</button>
          </>
        )}
      />
      {notice && <p className="note note-success" role="status">{notice}</p>}
      <ErrorNote message={overview.error} />
      {overview.loading && !overview.data ? <Loading /> : overview.data && (
        <section className="stats stats-wide" aria-label="Platform özeti">
          <Tile label="İşletme" value={overview.data.tenantCount} />
          <Tile label="Toplam ödeme" value={overview.data.paymentCount} />
          <Tile label="Son 24 saat tahsilat" value={overview.data.capturesLastDay} />
          <Tile label="Sonucu bilinmeyen işlem" value={overview.data.pendingOperations} alert />
          <Tile label="Yoldaki hesap kesimi" value={overview.data.pendingPayouts} />
          <Tile label="Başarısız hesap kesimi" value={overview.data.failedPayouts} alert />
          <Tile label="Sıradaki webhook" value={overview.data.queuedWebhooks} />
          <Tile label="Başarısız webhook" value={overview.data.failedWebhooks} alert />
        </section>
      )}

      <Merchants onChanged={() => {
        overview.reload();
        setRefreshed((count) => count + 1);
      }} />

      <div className="section-header"><h2>Mutabakat geçmişi</h2></div>
      <ErrorNote message={runs.error} />
      {runs.loading ? <Loading /> : runs.data && runs.data.length > 0 ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Banka dosyası</th>
                <th scope="col">Sonuç</th>
                <th scope="col" className="numeric">Satır</th>
                <th scope="col" className="numeric">Fark</th>
                <th scope="col" className="hide-narrow">Çalıştırma</th>
              </tr>
            </thead>
            <tbody>
              {runs.data.map((run) => (
                <tr key={run.id} className="row-link" onClick={() => navigate(`/admin/reconciliation/${run.id}`)}>
                  <td><a href={`/admin/reconciliation/${run.id}`} onClick={(e) => e.preventDefault()}>{formatDate(run.statementDate)}</a></td>
                  <td>
                    {run.status === 'FAILED'
                      ? <span className="badge badge-danger">Dosya reddedildi</span>
                      : run.discrepancyCount > 0
                        ? <span className="badge badge-warning">Fark var</span>
                        : <span className="badge badge-success">Uyumlu</span>}
                  </td>
                  <td className="numeric">{run.bankLineCount}</td>
                  <td className="numeric">{run.discrepancyCount}</td>
                  <td className="hide-narrow muted">{formatDateTime(run.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : !runs.error && <div className="card"><EmptyState title="Henüz mutabakat çalıştırılmadı" /></div>}

      <AdminAccounts key={refreshed} />

      {reconciling && (
        <FormDialog title="Mutabakat çalıştır" submitLabel="Çalıştır" onClose={() => setReconciling(false)} onSubmit={runReconciliation}>
          <label className="field">
            Banka dosyası tarihi
            <input type="date" required value={date} onChange={(e) => setDate(e.target.value)} />
          </label>
          <p className="muted">Sonuç geçmişe eklenir. Mutabakat farkları raporlar, kayıtları değiştirmez.</p>
        </FormDialog>
      )}
      {settling && (
        <FormDialog title="Hesap kesimini çalıştır" submitLabel="Çalıştır" onClose={() => setSettling(false)} onSubmit={runSettlement}>
          <p>Şu ana kadar tamamlanan işlemler işletmelere ödenir. İşletme başına günde bir hesap kesimi oluşur.</p>
        </FormDialog>
      )}
    </AdminShell>
  );
}

function RunDetail({ onSignOut }: { onSignOut: () => void }) {
  const { id } = useParams();
  const detail = useFetch(() => adminApi<ReconciliationDetail>(`/reconciliation/runs/${id}`), [id]);
  const [kind, setKind] = useState('');
  const kinds = useMemo(() => [...new Set(detail.data?.discrepancies.map((row) => row.kind) ?? [])], [detail.data]);
  const rows = detail.data?.discrepancies.filter((row) => !kind || row.kind === kind) ?? [];

  useEffect(() => {
    if (detail.errorStatus === 401) {
      onSignOut();
    }
  }, [detail.errorStatus, onSignOut]);

  return (
    <AdminShell onSignOut={onSignOut}>
      <p className="breadcrumb"><Link to="/admin">Genel durum</Link> / Mutabakat</p>
      <ErrorNote message={detail.error} />
      {detail.loading && !detail.data ? <Loading /> : detail.data && (
        <>
          <PageHeader
            title={`${formatDate(detail.data.run.statementDate)} mutabakatı`}
            description={`${detail.data.run.bankLineCount} banka satırı, ${detail.data.run.matchedCount} eşleşme, ${detail.data.run.discrepancyCount} fark`}
          />
          {detail.data.run.status === 'FAILED' && (
            <p className="note note-danger">Banka dosyası bozuk olduğu için tamamen reddedildi: {detail.data.run.error}</p>
          )}
          {detail.data.discrepancies.length > 0 ? (
            <>
              <div className="toolbar">
                <label className="field field-inline">
                  Fark türü
                  <select value={kind} onChange={(e) => setKind(e.target.value)}>
                    <option value="">Tümü</option>
                    {kinds.map((value) => <option key={value} value={value}>{KIND_LABELS[value] ?? value}</option>)}
                  </select>
                </label>
              </div>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th scope="col">Fark</th>
                      <th scope="col">Referans</th>
                      <th scope="col" className="numeric">Bizde</th>
                      <th scope="col" className="numeric">Bankada</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((row, index) => (
                      <tr key={`${row.kind}-${row.bankReference ?? row.sourceId}-${index}`}>
                        <td>
                          {KIND_LABELS[row.kind] ?? row.kind}
                          {row.tenantId && <span className="hint row-hint">İşletme: <code>{row.tenantId.slice(0, 8)}</code></span>}
                        </td>
                        <td><code>{row.bankReference ?? '—'}</code></td>
                        <td className="numeric">{amountOrDash(row.expectedAmount)}</td>
                        <td className="numeric">{amountOrDash(row.actualAmount)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          ) : detail.data.run.status === 'COMPLETED' && (
            <div className="card"><EmptyState title="Fark yok">Banka dosyası kayıtlarımızla tamamen uyuşuyor.</EmptyState></div>
          )}
        </>
      )}
    </AdminShell>
  );
}

export function AdminHome() {
  const session = useAdminSession();
  return session.signedIn
    ? <AdminDashboard onSignOut={session.signOut} />
    : <AdminSignIn onSignedIn={session.markSignedIn} />;
}

export function AdminRunDetail() {
  const session = useAdminSession();
  return session.signedIn
    ? <RunDetail onSignOut={session.signOut} />
    : <AdminSignIn onSignedIn={session.markSignedIn} />;
}
