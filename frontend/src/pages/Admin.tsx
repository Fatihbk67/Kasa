import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { adminApi, getAdminToken, setAdminToken } from '../adminApi';
import { EmptyState, ErrorNote, FormDialog, formatDate, formatDateTime, Loading, PageHeader } from '../components';
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
  const [token, setToken] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await adminApi('/overview', 'GET', token);
      setAdminToken(token);
      onSignedIn();
    } catch (e) {
      setError((e as Error).message);
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
          <label className="field">
            Yönetici anahtarı
            <input type="password" required autoComplete="off" value={token} onChange={(e) => setToken(e.target.value)} />
          </label>
          <ErrorNote message={error} />
          <button type="submit" className="button button-primary button-block" disabled={busy}>
            {busy ? 'Doğrulanıyor…' : 'Giriş yap'}
          </button>
        </form>
      </div>
    </div>
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
