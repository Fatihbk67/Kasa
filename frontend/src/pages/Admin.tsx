import { useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { adminApi, getAdminToken, setAdminToken } from '../adminApi';
import { ApiError } from '../api';
import { Brand, EmptyState, ErrorNote, FormDialog, formatDate, formatDateTime, Loading, OneTimeSecret, PageHeader } from '../components';
import { formatMoney } from '../money';
import { formatIban } from '../identifiers';
import type { BusinessProfile, PlatformOverview, ReconciliationDetail, ReconciliationRun } from '../types';
import { BUSINESS_TYPES, bankName, SECTORS } from './Onboarding';
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

/** People sign in on the shared login page. Only the machine key, for the first account and emergencies, stays here. */
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
      setError(e instanceof ApiError && e.status === 401 ? 'Anahtar geçersiz.' : (e as Error).message);
      setBusy(false);
    }
  };

  return (
    <div className="auth-page">
      <div className="card auth-card">
        <Brand />
        <h1>Platform yönetimi</h1>
        <p className="muted">Yöneticiler de işletmelerle aynı giriş sayfasını kullanır: e-posta, parola, sonra doğrulama kodu.</p>
        <Link className="button button-primary button-block" to="/login">Giriş sayfasına git</Link>
        <details className="key-login">
          <summary>Yönetici anahtarıyla gir</summary>
          <form onSubmit={submit} className="stack">
            <label className="field">
              Yönetici anahtarı
              <input type="password" required autoComplete="off" value={token} onChange={(e) => setToken(e.target.value)} />
              <span className="hint">İlk yönetici hesabını açmak ve acil durumlar içindir. İşlemler kişiye yazılmaz.</span>
            </label>
            <ErrorNote message={error} />
            <button type="submit" className="button button-block" disabled={busy}>{busy ? 'Doğrulanıyor…' : 'Anahtarla gir'}</button>
          </form>
        </details>
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
  MERCHANT_APPLICATION_VIEWED: 'Başvuru görüntülendi',
  USER_ROLE_CHANGED: 'Kullanıcı rolü değişti',
  USER_DISABLED: 'Kullanıcı kapatıldı',
  USER_ENABLED: 'Kullanıcı açıldı',
  ROLE_REQUEST_APPROVED: 'Rol talebi onaylandı',
  ROLE_REQUEST_REJECTED: 'Rol talebi reddedildi',
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
  profileSubmittedAt: string | null;
}

const MERCHANT_STATUS: Record<MerchantRow['status'], { label: string; tone: string }> = {
  PENDING: { label: 'Onay bekliyor', tone: 'warning' },
  ACTIVE: { label: 'Etkin', tone: 'success' },
  SUSPENDED: { label: 'Askıda', tone: 'danger' },
};

/** The full application, IBAN included. Opening it is recorded in the platform log, since it shows personal data. */
function ApplicationDialog({ merchant, onClose, onApprove }: { merchant: MerchantRow; onClose: () => void; onApprove: () => Promise<void> }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const application = useFetch(() => adminApi<BusinessProfile>(`/merchants/${merchant.id}/application`), [merchant.id]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const profile = application.data;

  useEffect(() => {
    dialog.current?.showModal();
  }, []);

  const approve = async () => {
    setBusy(true);
    setError(null);
    try {
      await onApprove();
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  return (
    <dialog ref={dialog} className="dialog dialog-wide" onClose={onClose}>
      <h2>{merchant.name} · Başvuru</h2>
      <ErrorNote message={application.error} />
      {application.loading && !profile ? <Loading /> : profile && (
        <>
          <p className="hint">Gönderildi: {formatDateTime(profile.submittedAt)} · Sahip: {merchant.ownerEmail ?? '—'}</p>
          <dl className="details">
            <div><dt>İşletme türü</dt><dd>{BUSINESS_TYPES[profile.businessType].label}</dd></div>
            <div><dt>Yasal unvan</dt><dd>{profile.legalName}</dd></div>
            <div><dt>{profile.businessType === 'COMPANY' ? 'Vergi kimlik no' : 'T.C. kimlik no'}</dt><dd>{profile.taxId}</dd></div>
            <div><dt>Vergi dairesi</dt><dd>{profile.taxOffice}</dd></div>
            <div><dt>Sektör</dt><dd>{SECTORS[profile.sector]}</dd></div>
            <div><dt>Telefon</dt><dd>{profile.phone}</dd></div>
            <div><dt>Web sitesi</dt><dd>{profile.website ?? '—'}</dd></div>
            <div><dt>Şehir</dt><dd>{profile.city}</dd></div>
            <div className="details-id"><dt>Adres</dt><dd>{profile.address}</dd></div>
            <div className="details-id"><dt>IBAN · {bankName(profile.bankCode)}</dt><dd><code>{formatIban(profile.iban)}</code></dd></div>
          </dl>
          <p className="note note-warning">Onaydan önce IBAN sahibinin yasal unvanla aynı olduğunu ve vergi numarasının etkin olduğunu doğrulayın.</p>
        </>
      )}
      <ErrorNote message={error} />
      <div className="dialog-actions">
        <button type="button" className="button" onClick={onClose} disabled={busy}>Kapat</button>
        {merchant.status === 'PENDING' && profile && (
          <button type="button" className="button button-primary" onClick={approve} disabled={busy}>{busy ? 'İşleniyor…' : 'Başvuruyu onayla'}</button>
        )}
      </div>
    </dialog>
  );
}

function Merchants({ onChanged }: { onChanged: () => void }) {
  const [filter, setFilter] = useState<'' | MerchantRow['status']>('PENDING');
  const merchants = useFetch(() => adminApi<MerchantRow[]>(`/merchants${filter ? `?status=${filter}` : ''}`), [filter]);
  const [suspending, setSuspending] = useState<MerchantRow | null>(null);
  const [viewing, setViewing] = useState<MerchantRow | null>(null);
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
                    <span className="hint row-hint">{merchant.profileSubmittedAt ? `Başvuru: ${formatDateTime(merchant.profileSubmittedAt)}` : 'Başvuru formu henüz gönderilmedi'}</span>
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
                      {merchant.profileSubmittedAt && (
                        <button type="button" className={merchant.status === 'PENDING' ? 'button button-small button-primary' : 'button button-small'}
                          onClick={() => setViewing(merchant)}>
                          {merchant.status === 'PENDING' ? 'İncele' : 'Başvuru'}
                        </button>
                      )}
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

      {viewing && (
        <ApplicationDialog merchant={viewing} onClose={() => setViewing(null)}
          onApprove={async () => {
            await adminApi(`/merchants/${viewing.id}/approve`, 'POST');
            setViewing(null);
            merchants.reload();
            onChanged();
          }} />
      )}

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

const ADMIN_TABS = [
  { key: 'overview', label: 'Genel durum' },
  { key: 'merchants', label: 'İşletmeler' },
  { key: 'users', label: 'Kullanıcılar' },
  { key: 'requests', label: 'Talepler' },
  { key: 'admins', label: 'Yöneticiler' },
];

const ROLE_NAMES: Record<string, string> = { OWNER: 'Sahip', ADMIN: 'Yönetici', VIEWER: 'Görüntüleyici' };

interface PlatformUser {
  id: string;
  email: string;
  role: 'OWNER' | 'ADMIN' | 'VIEWER';
  disabled: boolean;
  twoFactor: boolean;
  createdAt: string;
  tenantId: string;
  tenantName: string;
  tenantStatus: string;
  pendingRequest: string | null;
}

interface PlatformRoleRequest {
  id: string;
  userId: string;
  email: string;
  tenantName: string;
  currentRole: string;
  requestedRole: string;
  reason: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELED';
  decidedBy: string | null;
  decisionNote: string | null;
  decidedAt: string | null;
  createdAt: string;
}

function conflictMessage(e: unknown): Error {
  if (e instanceof ApiError && e.status === 409) {
    return new Error('Bu değişiklik yapılamaz: işletmenin son sahibi olabilir, kişi kapalı olabilir veya karar zaten verilmiş olabilir.');
  }
  return e as Error;
}

function PlatformUsers({ onChanged }: { onChanged: () => void }) {
  const [draft, setDraft] = useState('');
  const [query, setQuery] = useState('');
  const users = useFetch(() => adminApi<PlatformUser[]>(`/users${query ? `?q=${encodeURIComponent(query)}` : ''}`), [query]);
  const [editing, setEditing] = useState<PlatformUser | null>(null);
  const [role, setRole] = useState('VIEWER');
  const [toggling, setToggling] = useState<PlatformUser | null>(null);

  const saveRole = async () => {
    try {
      await adminApi(`/users/${editing!.id}/role`, 'POST', undefined, { role });
    } catch (e) {
      throw conflictMessage(e);
    }
    setEditing(null);
    users.reload();
    onChanged();
  };

  const toggle = async () => {
    try {
      await adminApi(`/users/${toggling!.id}/${toggling!.disabled ? 'enable' : 'disable'}`, 'POST');
    } catch (e) {
      throw conflictMessage(e);
    }
    setToggling(null);
    users.reload();
    onChanged();
  };

  return (
    <>
      <PageHeader title="Kullanıcılar" description="Platformdaki bütün işletmelerin bütün üyeleri." />
      <form className="toolbar" role="search" onSubmit={(event) => {
        event.preventDefault();
        setQuery(draft.trim());
      }}>
        <label className="field field-inline">
          Ara
          <input type="search" placeholder="E-posta veya işletme adı" value={draft} onChange={(e) => setDraft(e.target.value)} />
        </label>
        <button type="submit" className="button">Ara</button>
      </form>
      <ErrorNote message={users.error} />
      {users.loading ? <Loading /> : users.data && users.data.length > 0 ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Kullanıcı</th>
                <th scope="col">Rol</th>
                <th scope="col" className="hide-narrow">Kayıt</th>
                <th scope="col"><span className="visually-hidden">İşlemler</span></th>
              </tr>
            </thead>
            <tbody>
              {users.data.map((user) => (
                <tr key={user.id}>
                  <td>
                    {user.email}
                    <span className="hint row-hint">{user.tenantName}{user.twoFactor ? ' · İki adımlı giriş açık' : ''}</span>
                  </td>
                  <td>
                    {user.disabled
                      ? <span className="badge badge-neutral">Kapalı</span>
                      : <span className="badge badge-info">{ROLE_NAMES[user.role]}</span>}
                    {user.pendingRequest && <span className="hint row-hint">Talep: {ROLE_NAMES[user.pendingRequest]}</span>}
                  </td>
                  <td className="hide-narrow muted">{formatDateTime(user.createdAt)}</td>
                  <td className="numeric">
                    <span className="row-actions">
                      {!user.disabled && (
                        <button type="button" className="button button-small" onClick={() => {
                          setRole(user.role);
                          setEditing(user);
                        }}>Rolü değiştir</button>
                      )}
                      <button type="button" className="button button-small" onClick={() => setToggling(user)}>
                        {user.disabled ? 'Aç' : 'Kapat'}
                      </button>
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : !users.error && <div className="card"><EmptyState title={query ? 'Aramanızla eşleşen kullanıcı yok' : 'Henüz kullanıcı yok'} /></div>}

      {editing && (
        <FormDialog title="Rolü değiştir" submitLabel="Kaydet" onClose={() => setEditing(null)} onSubmit={saveRole}>
          <p className="muted">{editing.email} · {editing.tenantName}</p>
          <label className="field">
            Rol
            <select value={role} onChange={(e) => setRole(e.target.value)}>
              {Object.entries(ROLE_NAMES).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
            <span className="hint">Hemen geçerli olur. İşletmenin son sahibi başka bir role alınamaz.</span>
          </label>
        </FormDialog>
      )}
      {toggling && (
        <FormDialog title={toggling.disabled ? 'Kullanıcıyı aç' : 'Kullanıcıyı kapat'} submitLabel={toggling.disabled ? 'Aç' : 'Kapat'}
          danger={!toggling.disabled} onClose={() => setToggling(null)} onSubmit={toggle}>
          <p>
            <strong>{toggling.email}</strong>{' '}
            {toggling.disabled ? 'yeniden giriş yapabilecek.' : 'erişimini hemen kaybeder ve açık oturumları kapanır.'}
          </p>
        </FormDialog>
      )}
    </>
  );
}

const REQUEST_STATUS: Record<PlatformRoleRequest['status'], { label: string; tone: string }> = {
  PENDING: { label: 'Bekliyor', tone: 'warning' },
  APPROVED: { label: 'Onaylandı', tone: 'success' },
  REJECTED: { label: 'Reddedildi', tone: 'danger' },
  CANCELED: { label: 'Geri çekildi', tone: 'neutral' },
};

function RoleRequests({ onChanged }: { onChanged: () => void }) {
  const [filter, setFilter] = useState<'' | PlatformRoleRequest['status']>('PENDING');
  const requests = useFetch(() => adminApi<PlatformRoleRequest[]>(`/role-requests${filter ? `?status=${filter}` : ''}`), [filter]);
  const [deciding, setDeciding] = useState<{ request: PlatformRoleRequest; approve: boolean } | null>(null);
  const [note, setNote] = useState('');

  const decide = async () => {
    const { request, approve } = deciding!;
    try {
      await adminApi(`/role-requests/${request.id}/${approve ? 'approve' : 'reject'}`, 'POST', undefined, { note });
    } catch (e) {
      throw conflictMessage(e);
    }
    setDeciding(null);
    setNote('');
    requests.reload();
    onChanged();
  };

  return (
    <>
      <PageHeader title="Rol talepleri" description="Üyeler başka bir rol istediğinde burada görünür. Onaylayınca rol hemen değişir." />
      <div className="toolbar">
        <label className="field field-inline">
          Durum
          <select value={filter} onChange={(e) => setFilter(e.target.value as typeof filter)}>
            <option value="PENDING">Bekleyen</option>
            <option value="APPROVED">Onaylanan</option>
            <option value="REJECTED">Reddedilen</option>
            <option value="">Tümü</option>
          </select>
        </label>
      </div>
      <ErrorNote message={requests.error} />
      {requests.loading ? <Loading /> : requests.data && requests.data.length > 0 ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Talep</th>
                <th scope="col">Durum</th>
                <th scope="col" className="hide-narrow">Tarih</th>
                <th scope="col"><span className="visually-hidden">İşlemler</span></th>
              </tr>
            </thead>
            <tbody>
              {requests.data.map((request) => (
                <tr key={request.id}>
                  <td>
                    {request.email}: {ROLE_NAMES[request.currentRole]} → <strong>{ROLE_NAMES[request.requestedRole]}</strong>
                    <span className="hint row-hint">{request.tenantName} · “{request.reason}”</span>
                    {request.decisionNote && <span className="hint row-hint">Not: {request.decisionNote}</span>}
                  </td>
                  <td>
                    <span className={`badge badge-${REQUEST_STATUS[request.status].tone}`}>{REQUEST_STATUS[request.status].label}</span>
                    {request.decidedBy && <span className="hint row-hint">{request.decidedBy === 'platform-admin' ? 'Ortak anahtar' : request.decidedBy}</span>}
                  </td>
                  <td className="hide-narrow muted">{formatDateTime(request.createdAt)}</td>
                  <td className="numeric">
                    {request.status === 'PENDING' && (
                      <span className="row-actions">
                        <button type="button" className="button button-small button-primary" onClick={() => setDeciding({ request, approve: true })}>Onayla</button>
                        <button type="button" className="button button-small" onClick={() => setDeciding({ request, approve: false })}>Reddet</button>
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : !requests.error && <div className="card"><EmptyState title={filter === 'PENDING' ? 'Bekleyen talep yok' : 'Talep yok'} /></div>}

      {deciding && (
        <FormDialog title={deciding.approve ? 'Talebi onayla' : 'Talebi reddet'} submitLabel={deciding.approve ? 'Onayla' : 'Reddet'}
          danger={!deciding.approve} onClose={() => setDeciding(null)} onSubmit={decide}>
          <p>
            <strong>{deciding.request.email}</strong> · {ROLE_NAMES[deciding.request.currentRole]} → {ROLE_NAMES[deciding.request.requestedRole]}
          </p>
          <label className="field">
            {deciding.approve ? 'Not (isteğe bağlı)' : 'Gerekçe'}
            <textarea required={!deciding.approve} autoFocus maxLength={500} rows={3} value={note} onChange={(e) => setNote(e.target.value)} />
            <span className="hint">Kişi bu notu ayarlar sayfasında görür.</span>
          </label>
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
  // Remounts the accounts section, so its log shows a decision made in another section.
  const [refreshed, setRefreshed] = useState(0);
  const [params, setParams] = useSearchParams();
  const tab = ADMIN_TABS.some((item) => item.key === params.get('tab')) ? params.get('tab')! : 'overview';
  // Fetched on every tab, so a new request is noticed wherever the administrator is.
  const pending = useFetch(() => adminApi<PlatformRoleRequest[]>('/role-requests?status=PENDING'), []);

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

  const changed = () => {
    overview.reload();
    pending.reload();
    setRefreshed((count) => count + 1);
  };
  const pendingCount = pending.data?.length ?? 0;

  return (
    <AdminShell onSignOut={onSignOut}>
      <nav className="admin-tabs" aria-label="Yönetim bölümleri">
        {ADMIN_TABS.map((item) => (
          <button key={item.key} type="button" className="admin-tab" aria-current={tab === item.key ? 'page' : undefined}
            onClick={() => setParams(item.key === 'overview' ? {} : { tab: item.key })}>
            {item.label}
            {item.key === 'requests' && pendingCount > 0 && <span className="tab-badge" aria-label={`${pendingCount} bekleyen`}>{pendingCount}</span>}
          </button>
        ))}
      </nav>
      {pendingCount > 0 && tab !== 'requests' && (
        <p className="note note-warning" role="status">
          {pendingCount} rol talebi kararınızı bekliyor. <button type="button" className="link-button" onClick={() => setParams({ tab: 'requests' })}>Taleplere git</button>
        </p>
      )}
      {tab === 'merchants' && <Merchants onChanged={changed} />}
      {tab === 'users' && <PlatformUsers onChanged={changed} />}
      {tab === 'requests' && <RoleRequests onChanged={changed} />}
      {tab === 'admins' && <AdminAccounts key={refreshed} />}
      {tab === 'overview' && (
      <>
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
      </>
      )}

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
