import { useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { adminApi, getAdminToken, setAdminToken } from '../adminApi';
import { ApiError } from '../api';
import { Brand, EmptyState, ErrorNote, FormDialog, formatDate, formatDateTime, Loading, PageHeader } from '../components';
import { LanguagePicker, locale, t, tk } from '../i18n';
import { formatMoney } from '../money';
import { formatIban } from '../identifiers';
import { DailyBars, HorizontalBars } from '../charts';
import { compareWindows, greeting } from '../dashboard';
import type { BusinessProfile, PlatformActivity, PlatformOverview, ReconciliationDetail, ReconciliationRun } from '../types';
import { Change, Kpi, TaskList, type Task } from './Overview';
import { AuthenticatorSetup } from '../AuthenticatorSetup';
import { BUSINESS_TYPES, bankName, SECTORS } from './Onboarding';
import { useFetch } from '../useFetch';

// Labels in the constants below are Turkish keys, translated where they are shown.
const KIND_LABELS: Record<string, string> = {
  MISSING_IN_BANK: tk('Bizde var, bankada yok'),
  MISSING_IN_KASA: tk('Bankada var, bizde yok'),
  AMOUNT_MISMATCH: tk('Tutar farklı'),
  DETAILS_MISMATCH: tk('Tür veya para birimi farklı'),
  DUPLICATE_IN_BANK: tk('Dosyada çift kayıt'),
  LEDGER_DRIFT: tk('Bakiye defterle uyuşmuyor'),
};

function kindLabel(kind: string): string {
  return KIND_LABELS[kind] ? t(KIND_LABELS[kind]) : kind;
}

function yesterday(): string {
  const date = new Date();
  date.setDate(date.getDate() - 1);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

function amountOrDash(value: number | null): string {
  return value === null ? '—' : formatMoney(value, 'TRY');
}

/** The shared admin key acts for no one in particular, and says so wherever a decision is shown. */
function actorName(actor: string): string {
  return actor === 'platform-admin' ? t('Ortak anahtar') : actor;
}

function AdminShell({ children, onSignOut }: { children: ReactNode; onSignOut: () => void }) {
  return (
    <div className="admin-page">
      <header className="admin-bar">
        <Link className="brand admin-brand" to="/admin">{t('Kasa · Platform yönetimi')}</Link>
        <div className="preference-row">
          <LanguagePicker compact />
          <button type="button" className="link-button" onClick={onSignOut}>{t('Çıkış yap')}</button>
        </div>
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
      setError(e instanceof ApiError && e.status === 401 ? t('Anahtar geçersiz.') : (e as Error).message);
      setBusy(false);
    }
  };

  return (
    <div className="auth-page">
      <div className="card auth-card">
        <div className="auth-card-top">
          <Brand />
          <LanguagePicker compact />
        </div>
        <h1>{t('Platform yönetimi')}</h1>
        <p className="muted">{t('Yöneticiler de işletmelerle aynı giriş sayfasını kullanır: e-posta, parola, sonra doğrulama kodu.')}</p>
        <Link className="button button-primary button-block" to="/login">{t('Giriş sayfasına git')}</Link>
        <details className="key-login">
          <summary>{t('Yönetici anahtarıyla gir')}</summary>
          <form onSubmit={submit} className="stack">
            <label className="field">
              {t('Yönetici anahtarı')}
              <input type="password" required autoComplete="off" value={token} onChange={(e) => setToken(e.target.value)} />
              <span className="hint">{t('İlk yönetici hesabını açmak ve acil durumlar içindir. İşlemler kişiye yazılmaz.')}</span>
            </label>
            <ErrorNote message={error} />
            <button type="submit" className="button button-block" disabled={busy}>{busy ? t('Doğrulanıyor…') : t('Anahtarla gir')}</button>
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

interface CreatedAdmin {
  email: string;
  totpSecret: string;
  enrollmentUri: string;
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
  ADMIN_CREATED: tk('Yönetici oluşturuldu'),
  ADMIN_DISABLED: tk('Yönetici kapatıldı'),
  ADMIN_LOGIN: tk('Giriş yapıldı'),
  ADMIN_LOGIN_FAILED: tk('Başarısız giriş'),
  ADMIN_LOGIN_LOCKED: tk('Kilitli hesaba giriş denemesi'),
  SETTLEMENT_RUN: tk('Hesap kesimi çalıştırıldı'),
  MERCHANT_APPROVED: tk('İşletme onaylandı'),
  MERCHANT_SUSPENDED: tk('İşletme askıya alındı'),
  MERCHANT_REACTIVATED: tk('İşletme yeniden açıldı'),
  MERCHANT_APPLICATION_VIEWED: tk('Başvuru görüntülendi'),
  USER_ROLE_CHANGED: tk('Kullanıcı rolü değişti'),
  USER_DISABLED: tk('Kullanıcı kapatıldı'),
  USER_ENABLED: tk('Kullanıcı açıldı'),
  ROLE_REQUEST_APPROVED: tk('Rol talebi onaylandı'),
  ROLE_REQUEST_REJECTED: tk('Rol talebi reddedildi'),
  RECONCILIATION_RUN: tk('Mutabakat çalıştırıldı'),
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
  PENDING: { label: tk('Onay bekliyor'), tone: 'warning' },
  ACTIVE: { label: tk('Etkin'), tone: 'success' },
  SUSPENDED: { label: tk('Askıda'), tone: 'danger' },
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
      <h2>{t('{name} · Başvuru', { name: merchant.name })}</h2>
      <ErrorNote message={application.error} />
      {application.loading && !profile ? <Loading /> : profile && (
        <>
          <p className="hint">{t('Gönderildi: {date} · Sahip: {owner}', { date: formatDateTime(profile.submittedAt), owner: merchant.ownerEmail ?? '—' })}</p>
          <dl className="details">
            <div><dt>{t('İşletme türü')}</dt><dd>{t(BUSINESS_TYPES[profile.businessType].label)}</dd></div>
            <div><dt>{t('Yasal unvan')}</dt><dd>{profile.legalName}</dd></div>
            <div><dt>{profile.businessType === 'COMPANY' ? t('Vergi kimlik no') : t('T.C. kimlik no')}</dt><dd>{profile.taxId}</dd></div>
            <div><dt>{t('Vergi dairesi')}</dt><dd>{profile.taxOffice}</dd></div>
            <div><dt>{t('Sektör')}</dt><dd>{t(SECTORS[profile.sector])}</dd></div>
            <div><dt>{t('Telefon')}</dt><dd>{profile.phone}</dd></div>
            <div><dt>{t('Web sitesi')}</dt><dd>{profile.website ?? '—'}</dd></div>
            <div><dt>{t('Şehir')}</dt><dd>{profile.city}</dd></div>
            <div className="details-id"><dt>{t('Adres')}</dt><dd>{profile.address}</dd></div>
            <div className="details-id"><dt>IBAN · {bankName(profile.bankCode)}</dt><dd><code>{formatIban(profile.iban)}</code></dd></div>
          </dl>
          <p className="note note-warning">{t('Onaydan önce IBAN sahibinin yasal unvanla aynı olduğunu ve vergi numarasının etkin olduğunu doğrulayın.')}</p>
        </>
      )}
      <ErrorNote message={error} />
      <div className="dialog-actions">
        <button type="button" className="button" onClick={onClose} disabled={busy}>{t('Kapat')}</button>
        {merchant.status === 'PENDING' && profile && (
          <button type="button" className="button button-primary" onClick={approve} disabled={busy}>{busy ? t('İşleniyor…') : t('Başvuruyu onayla')}</button>
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
        <h2>{t('İşletmeler')}</h2>
        <label className="field field-inline">
          {t('Durum')}
          <select value={filter} onChange={(e) => setFilter(e.target.value as typeof filter)}>
            <option value="PENDING">{t('Onay bekleyen')}</option>
            <option value="ACTIVE">{t('Etkin')}</option>
            <option value="SUSPENDED">{t('Askıda')}</option>
            <option value="">{t('Tümü')}</option>
          </select>
        </label>
      </div>
      <ErrorNote message={merchants.error ?? error} />
      {merchants.loading ? <Loading /> : merchants.data && merchants.data.length > 0 ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">{t('İşletme')}</th>
                <th scope="col">{t('Durum')}</th>
                <th scope="col" className="numeric hide-narrow">{t('Ödeme')}</th>
                <th scope="col" className="hide-narrow">{t('Kayıt')}</th>
                <th scope="col"><span className="visually-hidden">{t('İşlemler')}</span></th>
              </tr>
            </thead>
            <tbody>
              {merchants.data.map((merchant) => (
                <tr key={merchant.id}>
                  <td>
                    {merchant.name}
                    <span className="hint row-hint">{merchant.ownerEmail ?? '—'} · {t('{count} üye', { count: merchant.memberCount })}</span>
                    {merchant.statusReason && <span className="hint row-hint">{t('Gerekçe: {reason}', { reason: merchant.statusReason })}</span>}
                    <span className="hint row-hint">
                      {merchant.profileSubmittedAt ? t('Başvuru: {date}', { date: formatDateTime(merchant.profileSubmittedAt) }) : t('Başvuru formu henüz gönderilmedi')}
                    </span>
                  </td>
                  <td>
                    <span className={`badge badge-${MERCHANT_STATUS[merchant.status].tone}`}>{t(MERCHANT_STATUS[merchant.status].label)}</span>
                    {merchant.reviewedBy && <span className="hint row-hint">{actorName(merchant.reviewedBy)}</span>}
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
                          {merchant.status === 'PENDING' ? t('İncele') : t('Başvuru')}
                        </button>
                      )}
                      {merchant.status === 'SUSPENDED' && <button type="button" className="button button-small" onClick={() => act(merchant, 'reactivate')}>{t('Yeniden aç')}</button>}
                      {merchant.status !== 'SUSPENDED' && (
                        <button type="button" className="button button-small" onClick={() => setSuspending(merchant)}>
                          {merchant.status === 'PENDING' ? t('Reddet') : t('Askıya al')}
                        </button>
                      )}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : !merchants.error && <div className="card"><EmptyState title={filter === 'PENDING' ? t('Onay bekleyen işletme yok') : t('İşletme yok')} /></div>}

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
        <FormDialog title={suspending.status === 'PENDING' ? t('Başvuruyu reddet') : t('İşletmeyi askıya al')} submitLabel={t('Onayla')} danger
          onClose={() => setSuspending(null)} onSubmit={suspend}>
          <p>{t('{name} yeni ödeme alamaz ve hesap kesimi durur. İadeler ve itirazlar çalışmaya devam eder.', { name: suspending.name })}</p>
          <label className="field">
            {t('Gerekçe')}
            <textarea required autoFocus maxLength={500} rows={3} value={reason} onChange={(e) => setReason(e.target.value)} />
            <span className="hint">{t('İşletme bu metni panelinde görür.')}</span>
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
  const [created, setCreated] = useState<CreatedAdmin | null>(null);
  const [disabling, setDisabling] = useState<AdminAccount | null>(null);

  const create = async () => {
    try {
      const result = await adminApi<CreatedAdmin>('/accounts', 'POST', undefined, { email, password });
      setCreated(result);
      setCreating(false);
      setEmail('');
      setPassword('');
      accounts.reload();
      audit.reload();
    } catch (e) {
      throw e instanceof ApiError && e.status === 409 ? new Error(t('Bu e-postanın zaten yönetici hesabı var.')) : e;
    }
  };

  const disable = async () => {
    try {
      await adminApi(`/accounts/${disabling!.id}/disable`, 'POST');
    } catch (e) {
      throw e instanceof ApiError && e.status === 409 ? new Error(t('Kendi hesabınızı kapatamazsınız.')) : e;
    }
    setDisabling(null);
    accounts.reload();
    audit.reload();
  };

  return (
    <>
      <div className="section-header">
        <h2>{t('Yöneticiler')}</h2>
        <button type="button" className="button button-small" onClick={() => setCreating(true)}>{t('Yönetici ekle')}</button>
      </div>
      {created && (
        <div className="note note-warning" role="status">
          <strong>{t('{email} için doğrulama uygulamasını kurun', { email: created.email })}</strong>
          <p>{t('Yönetici girişte bu uygulamadaki kodu kullanır. QR kod ve anahtar bir daha gösterilmeyecek; şimdi ekleyin.')}</p>
          <AuthenticatorSetup secret={created.totpSecret} uri={created.enrollmentUri} />
          <div><button type="button" className="button button-small" onClick={() => setCreated(null)}>{t('Ekledim, kapat')}</button></div>
        </div>
      )}
      <ErrorNote message={accounts.error} />
      {accounts.loading ? <Loading /> : accounts.data && accounts.data.length > 0 ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">{t('E-posta')}</th>
                <th scope="col">{t('Durum')}</th>
                <th scope="col" className="hide-narrow">{t('Oluşturulma')}</th>
                <th scope="col"><span className="visually-hidden">{t('İşlemler')}</span></th>
              </tr>
            </thead>
            <tbody>
              {accounts.data.map((account) => (
                <tr key={account.id}>
                  <td>{account.email}</td>
                  <td>{account.disabled ? <span className="badge badge-neutral">{t('Kapalı')}</span> : <span className="badge badge-success">{t('Etkin')}</span>}</td>
                  <td className="hide-narrow muted">{formatDateTime(account.createdAt)}</td>
                  <td className="numeric">
                    {!account.disabled && <button type="button" className="button button-small" onClick={() => setDisabling(account)}>{t('Yöneticiyi kapat')}</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : !accounts.error && <div className="card"><EmptyState title={t('Henüz yönetici yok')}>{t('Ortak anahtar yerine kişiye özel hesap açın.')}</EmptyState></div>}

      <div className="section-header"><h2>{t('Yönetici işlem kayıtları')}</h2></div>
      <ErrorNote message={audit.error} />
      {audit.data && audit.data.length > 0 ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">{t('İşlem')}</th>
                <th scope="col">{t('Yapan')}</th>
                <th scope="col" className="hide-narrow">{t('Tarih')}</th>
              </tr>
            </thead>
            <tbody>
              {audit.data.map((entry) => (
                <tr key={entry.id}>
                  <td>
                    {ADMIN_ACTIONS[entry.action] ? t(ADMIN_ACTIONS[entry.action]) : entry.action}
                    {entry.target && <span className="hint row-hint">{entry.target}</span>}
                  </td>
                  <td>
                    {actorName(entry.actor)}
                    {entry.ip && <span className="hint row-hint">{entry.ip}</span>}
                  </td>
                  <td className="hide-narrow muted">{formatDateTime(entry.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : !audit.loading && !audit.error && <div className="card"><EmptyState title={t('Henüz kayıt yok')} /></div>}

      {creating && (
        <FormDialog title={t('Yönetici ekle')} submitLabel={t('Oluştur')} onClose={() => setCreating(false)} onSubmit={create}>
          <label className="field">
            {t('E-posta')}
            <input type="email" required autoFocus value={email} onChange={(e) => setEmail(e.target.value)} />
          </label>
          <label className="field">
            {t('Parola')}
            <input type="password" required minLength={12} maxLength={72} autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
            <span className="hint">{t('En az 12 karakter. İki adımlı doğrulama zorunludur; kurulum anahtarı oluşturunca gösterilir.')}</span>
          </label>
        </FormDialog>
      )}
      {disabling && (
        <FormDialog title={t('Yöneticiyi kapat')} submitLabel={t('Yöneticiyi kapat')} danger onClose={() => setDisabling(null)} onSubmit={disable}>
          <p>{t('{email} erişimini hemen kaybeder ve açık oturumları kapanır.', { email: disabling.email })}</p>
        </FormDialog>
      )}
    </>
  );
}

const ADMIN_TABS = [
  { key: 'overview', label: tk('Genel durum') },
  { key: 'merchants', label: tk('İşletmeler') },
  { key: 'users', label: tk('Kullanıcılar') },
  { key: 'requests', label: tk('Talepler') },
  { key: 'admins', label: tk('Yöneticiler') },
];

const ROLE_NAMES: Record<string, string> = { OWNER: tk('Sahip'), ADMIN: tk('Yönetici'), VIEWER: tk('Görüntüleyici') };

function roleName(role: string): string {
  return ROLE_NAMES[role] ? t(ROLE_NAMES[role]) : role;
}

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
    return new Error(t('Bu değişiklik yapılamaz: işletmenin son sahibi olabilir, kişi kapalı olabilir veya karar zaten verilmiş olabilir.'));
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
      <PageHeader title={t('Kullanıcılar')} description={t('Platformdaki bütün işletmelerin bütün üyeleri.')} />
      <form className="toolbar" role="search" onSubmit={(event) => {
        event.preventDefault();
        setQuery(draft.trim());
      }}>
        <label className="field field-inline">
          {t('Ara')}
          <input type="search" placeholder={t('E-posta veya işletme adı')} value={draft} onChange={(e) => setDraft(e.target.value)} />
        </label>
        <button type="submit" className="button">{t('Ara')}</button>
      </form>
      <ErrorNote message={users.error} />
      {users.loading ? <Loading /> : users.data && users.data.length > 0 ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">{t('Kullanıcı')}</th>
                <th scope="col">{t('Rol')}</th>
                <th scope="col" className="hide-narrow">{t('Kayıt')}</th>
                <th scope="col"><span className="visually-hidden">{t('İşlemler')}</span></th>
              </tr>
            </thead>
            <tbody>
              {users.data.map((user) => (
                <tr key={user.id}>
                  <td>
                    {user.email}
                    <span className="hint row-hint">{user.tenantName}{user.twoFactor ? ` · ${t('İki adımlı giriş açık')}` : ''}</span>
                  </td>
                  <td>
                    {user.disabled
                      ? <span className="badge badge-neutral">{t('Kapalı')}</span>
                      : <span className="badge badge-info">{roleName(user.role)}</span>}
                    {user.pendingRequest && <span className="hint row-hint">{t('Talep: {role}', { role: roleName(user.pendingRequest) })}</span>}
                  </td>
                  <td className="hide-narrow muted">{formatDateTime(user.createdAt)}</td>
                  <td className="numeric">
                    <span className="row-actions">
                      {!user.disabled && (
                        <button type="button" className="button button-small" onClick={() => {
                          setRole(user.role);
                          setEditing(user);
                        }}>{t('Rolü değiştir')}</button>
                      )}
                      <button type="button" className="button button-small" onClick={() => setToggling(user)}>
                        {user.disabled ? t('Kullanıcıyı aç') : t('Kullanıcıyı kapat')}
                      </button>
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : !users.error && <div className="card"><EmptyState title={query ? t('Aramanızla eşleşen kullanıcı yok') : t('Henüz kullanıcı yok')} /></div>}

      {editing && (
        <FormDialog title={t('Rolü değiştir')} submitLabel={t('Kaydet')} onClose={() => setEditing(null)} onSubmit={saveRole}>
          <p className="muted">{editing.email} · {editing.tenantName}</p>
          <label className="field">
            {t('Rol')}
            <select value={role} onChange={(e) => setRole(e.target.value)}>
              {Object.keys(ROLE_NAMES).map((value) => <option key={value} value={value}>{roleName(value)}</option>)}
            </select>
            <span className="hint">{t('Hemen geçerli olur. İşletmenin son sahibi başka bir role alınamaz.')}</span>
          </label>
        </FormDialog>
      )}
      {toggling && (
        <FormDialog title={toggling.disabled ? t('Kullanıcıyı aç') : t('Kullanıcıyı kapat')} submitLabel={toggling.disabled ? t('Kullanıcıyı aç') : t('Kullanıcıyı kapat')}
          danger={!toggling.disabled} onClose={() => setToggling(null)} onSubmit={toggle}>
          <p>
            {toggling.disabled
              ? t('{email} yeniden giriş yapabilecek.', { email: toggling.email })
              : t('{email} erişimini hemen kaybeder ve açık oturumları kapanır.', { email: toggling.email })}
          </p>
        </FormDialog>
      )}
    </>
  );
}

const REQUEST_STATUS: Record<PlatformRoleRequest['status'], { label: string; tone: string }> = {
  PENDING: { label: tk('Bekliyor'), tone: 'warning' },
  APPROVED: { label: tk('Onaylandı'), tone: 'success' },
  REJECTED: { label: tk('Reddedildi'), tone: 'danger' },
  CANCELED: { label: tk('Geri çekildi'), tone: 'neutral' },
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
      <PageHeader title={t('Rol talepleri')} description={t('Üyeler başka bir rol istediğinde burada görünür. Onaylayınca rol hemen değişir.')} />
      <div className="toolbar">
        <label className="field field-inline">
          {t('Durum')}
          <select value={filter} onChange={(e) => setFilter(e.target.value as typeof filter)}>
            <option value="PENDING">{t('Bekleyen')}</option>
            <option value="APPROVED">{t('Onaylanan')}</option>
            <option value="REJECTED">{t('Reddedilen')}</option>
            <option value="">{t('Tümü')}</option>
          </select>
        </label>
      </div>
      <ErrorNote message={requests.error} />
      {requests.loading ? <Loading /> : requests.data && requests.data.length > 0 ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">{t('Talep')}</th>
                <th scope="col">{t('Durum')}</th>
                <th scope="col" className="hide-narrow">{t('Tarih')}</th>
                <th scope="col"><span className="visually-hidden">{t('İşlemler')}</span></th>
              </tr>
            </thead>
            <tbody>
              {requests.data.map((request) => (
                <tr key={request.id}>
                  <td>
                    {request.email}: {roleName(request.currentRole)} → <strong>{roleName(request.requestedRole)}</strong>
                    <span className="hint row-hint">{request.tenantName} · “{request.reason}”</span>
                    {request.decisionNote && <span className="hint row-hint">{t('Not: {note}', { note: request.decisionNote })}</span>}
                  </td>
                  <td>
                    <span className={`badge badge-${REQUEST_STATUS[request.status].tone}`}>{t(REQUEST_STATUS[request.status].label)}</span>
                    {request.decidedBy && <span className="hint row-hint">{actorName(request.decidedBy)}</span>}
                  </td>
                  <td className="hide-narrow muted">{formatDateTime(request.createdAt)}</td>
                  <td className="numeric">
                    {request.status === 'PENDING' && (
                      <span className="row-actions">
                        <button type="button" className="button button-small button-primary" onClick={() => setDeciding({ request, approve: true })}>{t('Talebi onayla')}</button>
                        <button type="button" className="button button-small" onClick={() => setDeciding({ request, approve: false })}>{t('Reddet')}</button>
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : !requests.error && <div className="card"><EmptyState title={filter === 'PENDING' ? t('Bekleyen talep yok') : t('Talep yok')} /></div>}

      {deciding && (
        <FormDialog title={deciding.approve ? t('Talebi onayla') : t('Talebi reddet')} submitLabel={deciding.approve ? t('Talebi onayla') : t('Reddet')}
          danger={!deciding.approve} onClose={() => setDeciding(null)} onSubmit={decide}>
          <p>
            <strong>{deciding.request.email}</strong> · {roleName(deciding.request.currentRole)} → {roleName(deciding.request.requestedRole)}
          </p>
          <label className="field">
            {deciding.approve ? t('Not (isteğe bağlı)') : t('Gerekçe')}
            <textarea required={!deciding.approve} autoFocus maxLength={500} rows={3} value={note} onChange={(e) => setNote(e.target.value)} />
            <span className="hint">{t('Kişi bu notu ayarlar sayfasında görür.')}</span>
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

const MERCHANT_COUNT_LIMIT = 100;

/** One line of the system health card; a number that needs attention is marked in words as well as colour. */
function HealthRow({ label, value, alert }: { label: string; value: number; alert?: boolean }) {
  const bad = alert && value > 0;
  return (
    <li className={bad ? 'health-row health-row-bad' : 'health-row'}>
      <span>{label}</span>
      <span className="health-value">
        {bad && <span className="visually-hidden">{t('Dikkat')}: </span>}
        {value.toLocaleString(locale())}
      </span>
    </li>
  );
}

/** The operator's start page: how much money moves through the platform, and what is waiting for a decision. */
function PlatformHome({ overview, pendingRequests, onGoTo }: {
  overview: PlatformOverview | null;
  pendingRequests: number;
  onGoTo: (tab: string) => void;
}) {
  const activity = useFetch(() => adminApi<PlatformActivity>('/activity?days=30'), []);
  const pending = useFetch(() => adminApi<MerchantRow[]>('/merchants?status=PENDING'), []);
  const data = activity.data;
  const today = data ? compareWindows(data.daily, 1) : null;
  const week = data ? compareWindows(data.daily, 7) : null;
  const attempts = data?.daily.reduce((sum, day) => sum + day.authorizationAttempts, 0) ?? 0;
  const approved = data?.daily.reduce((sum, day) => sum + day.authorizationsApproved, 0) ?? 0;
  const newMerchants = data?.daily.reduce((sum, day) => sum + day.newMerchants, 0) ?? 0;
  const captured = data?.daily.reduce((sum, day) => sum + day.captured, 0) ?? 0;
  const captureCount = data?.daily.reduce((sum, day) => sum + day.captureCount, 0) ?? 0;
  const percent = (value: number) => value.toLocaleString(locale(), { style: 'percent', maximumFractionDigits: 1 });

  const applications = pending.data?.filter((merchant) => merchant.profileSubmittedAt) ?? [];
  const tasks: Task[] = [
    {
      key: 'applications', count: applications.length, capped: (pending.data?.length ?? 0) >= MERCHANT_COUNT_LIMIT,
      text: t('işletme başvurusu incelemenizi bekliyor', { count: applications.length }), to: '/admin?tab=merchants', tone: 'warning',
    },
    { key: 'requests', count: pendingRequests, capped: false, text: t('rol talebi kararınızı bekliyor', { count: pendingRequests }), to: '/admin?tab=requests', tone: 'info' },
    ...(overview ? [
      { key: 'operations', count: overview.pendingOperations, capped: false, text: t('işlemin sonucu bankadan bekleniyor', { count: overview.pendingOperations }), tone: 'danger' as const },
      { key: 'payouts', count: overview.failedPayouts, capped: false, text: t('hesap kesimi bankada başarısız oldu', { count: overview.failedPayouts }), tone: 'danger' as const },
      { key: 'webhooks', count: overview.failedWebhooks, capped: false, text: t('webhook teslim edilemedi', { count: overview.failedWebhooks }), tone: 'warning' as const },
    ] : []),
  ];

  return (
    <>
      <ErrorNote message={activity.error} />
      <section className="stats kpis" aria-label={t('Platform özeti')}>
        {!data ? <div className="card stat kpi"><Loading rows={2} /></div> : (
          <>
            <Kpi label={t('Bugünkü işlem hacmi')} value={formatMoney(today!.current, data.currency)}>
              <Change change={today!.change} against={t('Düne')} />
            </Kpi>
            <Kpi label={t('Son 7 gün')} value={formatMoney(week!.current, data.currency)}>
              <Change change={week!.change} against={t('Önceki 7 güne')} />
            </Kpi>
            <Kpi label={t('Onay oranı')} value={attempts > 0 ? percent(approved / attempts) : '—'}>
              <span className="hint">{t('Son 30 günde {approved} / {attempts} deneme', { approved, attempts })}</span>
            </Kpi>
          </>
        )}
        <Kpi label={t('İşletme')} value={overview ? overview.tenantCount.toLocaleString(locale()) : '—'}>
          <span className="hint">
            {data ? t('Son 30 günde {count} yeni kayıt', { count: newMerchants }) : ' '}
          </span>
        </Kpi>
      </section>

      <div className="dashboard-grid">
        <div className="dashboard-main">
          <section className="card chart-card" aria-labelledby="platform-chart-title">
            <div className="section-header section-header-tight">
              <div>
                <h2 id="platform-chart-title">{t('Platform hacmi · son 30 gün')}</h2>
                {data && (
                  <span className="hint">
                    {t('Bütün işletmelerin {currency} tahsilatı: {captured} · {count} işlem', {
                      currency: data.currency,
                      captured: formatMoney(captured, data.currency),
                      count: captureCount,
                    })}
                  </span>
                )}
              </div>
            </div>
            {activity.loading && !data ? <Loading rows={4} />
              : data && captured > 0 ? <DailyBars points={data.daily} currency={data.currency} />
                : <EmptyState title={t('Bu dönemde tahsilat yok')}>{t('İşletmeler ödeme aldıkça günlük toplamlar burada görünür.')}</EmptyState>}
          </section>
        </div>

        <aside className="dashboard-side" aria-label={t('Özet bilgiler')}>
          <TaskList tasks={tasks} loading={pending.loading || !overview} />

          <section className="card" aria-labelledby="top-merchants-title">
            <div className="section-header section-header-tight">
              <h2 className="panel-title" id="top-merchants-title">{t('En çok tahsilat yapan işletmeler')}</h2>
              <button type="button" className="link-button" onClick={() => onGoTo('merchants')}>{t('Tümü')}</button>
            </div>
            {!data ? <Loading rows={3} /> : data.topMerchants.length > 0 ? (
              <HorizontalBars rows={data.topMerchants.map((merchant) => ({
                key: merchant.id,
                label: merchant.status === 'ACTIVE' ? merchant.name : `${merchant.name} · ${t(MERCHANT_STATUS[merchant.status].label)}`,
                value: merchant.captured,
                valueText: formatMoney(merchant.captured, data.currency),
                detail: t('{count} işlem', { count: merchant.captureCount }),
              }))} />
            ) : <p className="muted">{t('Son 30 günde tahsilat yapan işletme yok.')}</p>}
          </section>

          <section className="card" aria-labelledby="health-title">
            <h2 className="panel-title" id="health-title">{t('Sistem durumu')}</h2>
            {!overview ? <Loading rows={3} /> : (
              <ul className="health">
                <HealthRow label={t('Toplam ödeme')} value={overview.paymentCount} />
                <HealthRow label={t('Son 24 saat tahsilat')} value={overview.capturesLastDay} />
                <HealthRow label={t('Yoldaki hesap kesimi')} value={overview.pendingPayouts} />
                <HealthRow label={t('Sıradaki webhook')} value={overview.queuedWebhooks} />
                <HealthRow label={t('Sonucu bilinmeyen işlem')} value={overview.pendingOperations} alert />
                <HealthRow label={t('Başarısız hesap kesimi')} value={overview.failedPayouts} alert />
                <HealthRow label={t('Başarısız webhook')} value={overview.failedWebhooks} alert />
              </ul>
            )}
          </section>
        </aside>
      </div>
    </>
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
    setNotice(result.payoutsCreated > 0
      ? t('{count} hesap kesimi oluşturuldu.', { count: result.payoutsCreated })
      : t('Yeni hesap kesimi oluşmadı: bugünkü hesap kesimi yapılmış veya ödenecek tutar yok.'));
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
      <nav className="admin-tabs" aria-label={t('Yönetim bölümleri')}>
        {ADMIN_TABS.map((item) => (
          <button key={item.key} type="button" className="admin-tab" aria-current={tab === item.key ? 'page' : undefined}
            onClick={() => setParams(item.key === 'overview' ? {} : { tab: item.key })}>
            {t(item.label)}
            {item.key === 'requests' && pendingCount > 0 && <span className="tab-badge" aria-label={t('{count} bekleyen', { count: pendingCount })}>{pendingCount}</span>}
          </button>
        ))}
      </nav>
      {pendingCount > 0 && tab !== 'requests' && (
        <p className="note note-warning" role="status">
          {t('{count} rol talebi kararınızı bekliyor.', { count: pendingCount })} <button type="button" className="link-button" onClick={() => setParams({ tab: 'requests' })}>{t('Taleplere git')}</button>
        </p>
      )}
      {tab === 'merchants' && <Merchants onChanged={changed} />}
      {tab === 'users' && <PlatformUsers onChanged={changed} />}
      {tab === 'requests' && <RoleRequests onChanged={changed} />}
      {tab === 'admins' && <AdminAccounts key={refreshed} />}
      {tab === 'overview' && (
      <>
      <PageHeader
        title={t('Genel durum')}
        description={t('{greeting}. Bugün {date}.', {
          greeting: greeting(new Date().getHours()),
          date: new Intl.DateTimeFormat(locale(), { weekday: 'long', day: 'numeric', month: 'long' }).format(new Date()),
        })}
        actions={(
          <>
            <button type="button" className="button" onClick={() => setSettling(true)}>{t('Hesap kesimini çalıştır')}</button>
            <button type="button" className="button button-primary" onClick={() => setReconciling(true)}>{t('Mutabakat çalıştır')}</button>
          </>
        )}
      />
      {notice && <p className="note note-success" role="status">{notice}</p>}
      <ErrorNote message={overview.error} />
      <PlatformHome overview={overview.data ?? null}pendingRequests={pendingCount} onGoTo={(key) => setParams({ tab: key })} />

      <div className="section-header"><h2>{t('Mutabakat geçmişi')}</h2></div>
      <ErrorNote message={runs.error} />
      {runs.loading ? <Loading /> : runs.data && runs.data.length > 0 ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">{t('Banka dosyası')}</th>
                <th scope="col">{t('Sonuç')}</th>
                <th scope="col" className="numeric">{t('Satır')}</th>
                <th scope="col" className="numeric">{t('Fark')}</th>
                <th scope="col" className="hide-narrow">{t('Çalıştırma')}</th>
              </tr>
            </thead>
            <tbody>
              {runs.data.map((run) => (
                <tr key={run.id} className="row-link" onClick={() => navigate(`/admin/reconciliation/${run.id}`)}>
                  <td><a href={`/admin/reconciliation/${run.id}`} onClick={(e) => e.preventDefault()}>{formatDate(run.statementDate)}</a></td>
                  <td>
                    {run.status === 'FAILED'
                      ? <span className="badge badge-danger">{t('Dosya reddedildi')}</span>
                      : run.discrepancyCount > 0
                        ? <span className="badge badge-warning">{t('Fark var')}</span>
                        : <span className="badge badge-success">{t('Uyumlu')}</span>}
                  </td>
                  <td className="numeric">{run.bankLineCount}</td>
                  <td className="numeric">{run.discrepancyCount}</td>
                  <td className="hide-narrow muted">{formatDateTime(run.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : !runs.error && <div className="card"><EmptyState title={t('Henüz mutabakat çalıştırılmadı')} /></div>}
      </>
      )}

      {reconciling && (
        <FormDialog title={t('Mutabakat çalıştır')} submitLabel={t('Çalıştır')} onClose={() => setReconciling(false)} onSubmit={runReconciliation}>
          <label className="field">
            {t('Banka dosyası tarihi')}
            <input type="date" required value={date} onChange={(e) => setDate(e.target.value)} />
          </label>
          <p className="muted">{t('Sonuç geçmişe eklenir. Mutabakat farkları raporlar, kayıtları değiştirmez.')}</p>
        </FormDialog>
      )}
      {settling && (
        <FormDialog title={t('Hesap kesimini çalıştır')} submitLabel={t('Çalıştır')} onClose={() => setSettling(false)} onSubmit={runSettlement}>
          <p>{t('Şu ana kadar tamamlanan işlemler işletmelere ödenir. İşletme başına günde bir hesap kesimi oluşur.')}</p>
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
      <p className="breadcrumb"><Link to="/admin">{t('Genel durum')}</Link> / {t('Mutabakat')}</p>
      <ErrorNote message={detail.error} />
      {detail.loading && !detail.data ? <Loading /> : detail.data && (
        <>
          <PageHeader
            title={t('{date} mutabakatı', { date: formatDate(detail.data.run.statementDate) })}
            description={t('{lines} banka satırı, {matched} eşleşme, {differences} fark', {
              lines: detail.data.run.bankLineCount,
              matched: detail.data.run.matchedCount,
              differences: detail.data.run.discrepancyCount,
            })}
          />
          {detail.data.run.status === 'FAILED' && (
            <p className="note note-danger">{t('Banka dosyası bozuk olduğu için tamamen reddedildi: {error}', { error: detail.data.run.error ?? '' })}</p>
          )}
          {detail.data.discrepancies.length > 0 ? (
            <>
              <div className="toolbar">
                <label className="field field-inline">
                  {t('Fark türü')}
                  <select value={kind} onChange={(e) => setKind(e.target.value)}>
                    <option value="">{t('Tümü')}</option>
                    {kinds.map((value) => <option key={value} value={value}>{kindLabel(value)}</option>)}
                  </select>
                </label>
              </div>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th scope="col">{t('Fark')}</th>
                      <th scope="col">{t('Referans')}</th>
                      <th scope="col" className="numeric">{t('Bizde')}</th>
                      <th scope="col" className="numeric">{t('Bankada')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((row, index) => (
                      <tr key={`${row.kind}-${row.bankReference ?? row.sourceId}-${index}`}>
                        <td>
                          {kindLabel(row.kind)}
                          {row.tenantId && <span className="hint row-hint">{t('İşletme')}: <code>{row.tenantId.slice(0, 8)}</code></span>}
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
            <div className="card"><EmptyState title={t('Fark yok')}>{t('Banka dosyası kayıtlarımızla tamamen uyuşuyor.')}</EmptyState></div>
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
