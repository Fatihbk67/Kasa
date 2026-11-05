import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../api';
import { EmptyState, ErrorNote, formatDateTime, Loading, PageHeader } from '../components';
import { locale, t, tk } from '../i18n';
import { ROLE_LABELS } from '../me';
import { formatMoney } from '../money';
import type { AuditEntry, AuditPage as Page, ChainReport, Role } from '../types';
import { useFetch } from '../useFetch';

const ACTION_LABELS: Record<string, string> = {
  TENANT_REGISTERED: tk('İşletme kaydoldu'),
  TENANT_RENAMED: tk('İşletme adı değişti'),
  LOGIN_SUCCEEDED: tk('Giriş yapıldı'),
  LOGIN_FAILED: tk('Başarısız giriş'),
  LOGIN_PASSWORD_ACCEPTED: tk('Parola doğru, kod bekleniyor'),
  TWO_FACTOR_ENABLED: tk('İki adımlı giriş açıldı'),
  TWO_FACTOR_DISABLED: tk('İki adımlı giriş kapatıldı'),
  TWO_FACTOR_LOCKED: tk('Hatalı kodlar nedeniyle hesap kilitlendi'),
  PASSWORD_RESET_REQUESTED: tk('Parola sıfırlama istendi'),
  PASSWORD_RESET: tk('Parola sıfırlandı'),
  ROLE_REQUESTED: tk('Rol talebinde bulunuldu'),
  ROLE_REQUEST_CANCELED: tk('Rol talebi geri çekildi'),
  ROLE_REQUEST_APPROVED: tk('Rol talebi onaylandı'),
  ROLE_REQUEST_REJECTED: tk('Rol talebi reddedildi'),
  MEMBER_RESTORED: tk('Üye yeniden açıldı'),
  MERCHANT_APPROVED: tk('İşletme onaylandı'),
  MERCHANT_SUSPENDED: tk('İşletme askıya alındı'),
  MERCHANT_REACTIVATED: tk('İşletme yeniden açıldı'),
  PASSWORD_CHANGED: tk('Parola değişti'),
  API_KEY_CREATED: tk('API anahtarı oluşturuldu'),
  API_KEY_REVOKED: tk('API anahtarı iptal edildi'),
  WEBHOOK_ENDPOINT_CREATED: tk('Webhook adresi eklendi'),
  WEBHOOK_ENDPOINT_DISABLED: tk('Webhook adresi kapatıldı'),
  MEMBER_INVITED: tk('Üye davet edildi'),
  INVITATION_REVOKED: tk('Davet iptal edildi'),
  MEMBER_JOINED: tk('Üye katıldı'),
  MEMBER_ROLE_CHANGED: tk('Üye rolü değişti'),
  MEMBER_REMOVED: tk('Üye çıkarıldı'),
  PAYMENT_CAPTURE_REQUESTED: tk('Tahsilat istendi'),
  PAYMENT_VOID_REQUESTED: tk('Provizyon iptali istendi'),
  PAYMENT_REFUND_REQUESTED: tk('İade istendi'),
  DISPUTE_OPENED: tk('İtiraz açıldı'),
  DISPUTE_EVIDENCE_SUBMITTED: tk('İtiraza kanıt gönderildi'),
  DISPUTE_ACCEPTED: tk('İtiraz kabul edildi'),
  DISPUTE_WON: tk('İtiraz kazanıldı'),
  DISPUTE_LOST: tk('İtiraz kaybedildi'),
  CUSTOMER_CREATED: tk('Müşteri eklendi'),
  CUSTOMER_UPDATED: tk('Müşteri güncellendi'),
  CUSTOMER_ANONYMIZED: tk('Müşteri verisi silindi'),
  PAYMENT_LINK_CREATED: tk('Ödeme bağlantısı oluşturuldu'),
  PAYMENT_LINK_DISABLED: tk('Ödeme bağlantısı kapatıldı'),
  PLAN_CREATED: tk('Plan oluşturuldu'),
  PLAN_ARCHIVED: tk('Plan kapatıldı'),
  SUBSCRIPTION_CREATED: tk('Abonelik başlatıldı'),
  SUBSCRIPTION_PLAN_CHANGED: tk('Abonelik planı değişti'),
  SUBSCRIPTION_CARD_UPDATED: tk('Abonelik kartı güncellendi'),
  SUBSCRIPTION_CANCEL_SCHEDULED: tk('Abonelik iptali planlandı'),
  SUBSCRIPTION_RESUMED: tk('Abonelik iptali geri alındı'),
  SUBSCRIPTION_CANCELED: tk('Abonelik iptal edildi'),
};

const METADATA_LABELS: Record<string, string> = {
  email: tk('E-posta'),
  role: tk('Rol'),
  from: tk('Eski rol'),
  to: tk('Yeni rol'),
  name: tk('Ad'),
  prefix: tk('Önek'),
  url: tk('Adres'),
  businessName: tk('İşletme'),
  title: tk('Başlık'),
  reason: tk('Neden'),
  planName: tk('Plan'),
  secondFactor: tk('İkinci adım'),
  method: tk('Yöntem'),
};

function actionLabel(action: string): string {
  return ACTION_LABELS[action] ? t(ACTION_LABELS[action]) : action;
}

function details(entry: AuditEntry): string {
  try {
    const metadata = JSON.parse(entry.metadata) as Record<string, unknown>;
    return Object.entries(metadata)
      .filter(([key]) => key !== 'operationId' && key !== 'currency')
      .map(([key, value]) => {
        if (key === 'amount') {
          return `${t('Tutar')}: ${formatMoney(Number(value), String(metadata.currency ?? 'TRY'))}`;
        }
        if (entry.action === 'SUBSCRIPTION_PLAN_CHANGED') {
          return `${key === 'from' ? t('Eski plan') : t('Yeni plan')}: ${String(value)}`;
        }
        if (key === 'role' || key === 'from' || key === 'to') {
          const label = ROLE_LABELS[value as Role] ? t(ROLE_LABELS[value as Role]) : String(value);
          return `${t(METADATA_LABELS[key])}: ${label}`;
        }
        if (key === 'method' && value === 'google') {
          return `${t(METADATA_LABELS[key])}: Google`;
        }
        return `${METADATA_LABELS[key] ? t(METADATA_LABELS[key]) : key}: ${String(value)}`;
      })
      .join(', ');
  } catch {
    return '';
  }
}

export function AuditPage() {
  const [params, setParams] = useSearchParams();
  const action = params.get('action') ?? '';
  const query = `/audit-log?limit=30${action ? `&action=${encodeURIComponent(action)}` : ''}`;
  const firstPage = useFetch(() => api<Page>(query), [query]);
  const actions = useFetch(() => api<string[]>('/audit-log/actions'), []);
  const [items, setItems] = useState<AuditEntry[]>([]);
  const [cursor, setCursor] = useState<number | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreError, setMoreError] = useState<string | null>(null);
  const [report, setReport] = useState<ChainReport | null>(null);
  const [verifying, setVerifying] = useState(false);
  const [verifyError, setVerifyError] = useState<string | null>(null);

  useEffect(() => {
    if (firstPage.data) {
      setItems(firstPage.data.items);
      setCursor(firstPage.data.nextCursor);
    }
  }, [firstPage.data]);

  const loadMore = async () => {
    setLoadingMore(true);
    setMoreError(null);
    try {
      const page = await api<Page>(`${query}&before=${cursor}`);
      setItems((current) => [...current, ...page.items]);
      setCursor(page.nextCursor);
    } catch (e) {
      setMoreError((e as Error).message);
    } finally {
      setLoadingMore(false);
    }
  };

  const verify = async () => {
    setVerifying(true);
    setVerifyError(null);
    setReport(null);
    try {
      setReport(await api<ChainReport>('/audit-log/verify'));
    } catch (e) {
      setVerifyError((e as Error).message);
    } finally {
      setVerifying(false);
    }
  };

  return (
    <>
      <PageHeader
        title={t('Denetim günlüğü')}
        description={t('Önemli işlemlerin değiştirilemez kaydı. Her kayıt bir öncekinin özetini içerir, bu yüzden geçmişle oynanırsa zincir bozulur.')}
        actions={<button type="button" className="button" onClick={verify} disabled={verifying}>{verifying ? t('Doğrulanıyor…') : t('Zinciri doğrula')}</button>}
      />
      <ErrorNote message={verifyError} />
      {report && (report.valid
        ? <p className="note note-success" role="status">{t('Zincir sağlam: {count} kayıt doğrulandı, değiştirilmiş kayıt yok.', { count: report.checked.toLocaleString(locale()) })}</p>
        : <p className="note note-danger" role="alert">{t('Zincir bozuk: {seq} numaralı kayıt değiştirilmiş veya silinmiş. {count} kayıt sağlam.', { seq: report.brokenAtSeq ?? '', count: report.checked.toLocaleString(locale()) })}</p>)}
      <div className="toolbar">
        <label className="field field-inline">
          {t('İşlem')}
          <select value={action} onChange={(e) => setParams(e.target.value ? { action: e.target.value } : {})}>
            <option value="">{t('Tümü')}</option>
            {(actions.data ?? []).map((value) => <option key={value} value={value}>{actionLabel(value)}</option>)}
          </select>
        </label>
      </div>
      <ErrorNote message={firstPage.error} />
      {firstPage.loading ? <Loading /> : items.length > 0 ? (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col" className="numeric">{t('No')}</th>
                  <th scope="col">{t('İşlem')}</th>
                  <th scope="col">{t('Yapan')}</th>
                  <th scope="col" className="hide-narrow">{t('Tarih')}</th>
                </tr>
              </thead>
              <tbody>
                {items.map((entry) => (
                  <tr key={entry.seq}>
                    <td className="numeric muted">{entry.seq}</td>
                    <td>
                      {actionLabel(entry.action)}
                      {details(entry) && <span className="hint row-hint">{details(entry)}</span>}
                    </td>
                    <td>
                      {entry.actorLabel ?? t('Sistem')}
                      {entry.ip && <span className="hint row-hint">{entry.ip}</span>}
                    </td>
                    <td className="hide-narrow muted">{formatDateTime(entry.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ErrorNote message={moreError} />
          {cursor !== null && (
            <div className="load-more">
              <button type="button" className="button" onClick={loadMore} disabled={loadingMore}>
                {loadingMore ? t('Yükleniyor…') : t('Daha fazla göster')}
              </button>
            </div>
          )}
        </>
      ) : !firstPage.error && <div className="card"><EmptyState title={t('Kayıt yok')} /></div>}
    </>
  );
}
