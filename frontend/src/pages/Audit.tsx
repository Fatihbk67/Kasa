import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../api';
import { EmptyState, ErrorNote, formatDateTime, Loading, PageHeader } from '../components';
import { ROLE_LABELS } from '../me';
import { formatMoney } from '../money';
import type { AuditEntry, AuditPage as Page, ChainReport, Role } from '../types';
import { useFetch } from '../useFetch';

const ACTION_LABELS: Record<string, string> = {
  TENANT_REGISTERED: 'İşletme kaydoldu',
  TENANT_RENAMED: 'İşletme adı değişti',
  LOGIN_SUCCEEDED: 'Giriş yapıldı',
  LOGIN_FAILED: 'Başarısız giriş',
  PASSWORD_CHANGED: 'Parola değişti',
  API_KEY_CREATED: 'API anahtarı oluşturuldu',
  API_KEY_REVOKED: 'API anahtarı iptal edildi',
  WEBHOOK_ENDPOINT_CREATED: 'Webhook adresi eklendi',
  WEBHOOK_ENDPOINT_DISABLED: 'Webhook adresi kapatıldı',
  MEMBER_INVITED: 'Üye davet edildi',
  INVITATION_REVOKED: 'Davet iptal edildi',
  MEMBER_JOINED: 'Üye katıldı',
  MEMBER_ROLE_CHANGED: 'Üye rolü değişti',
  MEMBER_REMOVED: 'Üye çıkarıldı',
  PAYMENT_CAPTURE_REQUESTED: 'Tahsilat istendi',
  PAYMENT_VOID_REQUESTED: 'Provizyon iptali istendi',
  PAYMENT_REFUND_REQUESTED: 'İade istendi',
  DISPUTE_OPENED: 'İtiraz açıldı',
  DISPUTE_EVIDENCE_SUBMITTED: 'İtiraza kanıt gönderildi',
  DISPUTE_ACCEPTED: 'İtiraz kabul edildi',
  DISPUTE_WON: 'İtiraz kazanıldı',
  DISPUTE_LOST: 'İtiraz kaybedildi',
  CUSTOMER_CREATED: 'Müşteri eklendi',
  CUSTOMER_UPDATED: 'Müşteri güncellendi',
  CUSTOMER_ANONYMIZED: 'Müşteri verisi silindi',
  PAYMENT_LINK_CREATED: 'Ödeme bağlantısı oluşturuldu',
  PAYMENT_LINK_DISABLED: 'Ödeme bağlantısı kapatıldı',
  PLAN_CREATED: 'Plan oluşturuldu',
  PLAN_ARCHIVED: 'Plan kapatıldı',
  SUBSCRIPTION_CREATED: 'Abonelik başlatıldı',
  SUBSCRIPTION_PLAN_CHANGED: 'Abonelik planı değişti',
  SUBSCRIPTION_CARD_UPDATED: 'Abonelik kartı güncellendi',
  SUBSCRIPTION_CANCEL_SCHEDULED: 'Abonelik iptali planlandı',
  SUBSCRIPTION_RESUMED: 'Abonelik iptali geri alındı',
  SUBSCRIPTION_CANCELED: 'Abonelik iptal edildi',
};

const METADATA_LABELS: Record<string, string> = {
  email: 'E-posta',
  role: 'Rol',
  from: 'Eski rol',
  to: 'Yeni rol',
  name: 'Ad',
  prefix: 'Önek',
  url: 'Adres',
  businessName: 'İşletme',
  title: 'Başlık',
  reason: 'Neden',
  planName: 'Plan',
};

function actionLabel(action: string): string {
  return ACTION_LABELS[action] ?? action;
}

function details(entry: AuditEntry): string {
  try {
    const metadata = JSON.parse(entry.metadata) as Record<string, unknown>;
    return Object.entries(metadata)
      .filter(([key]) => key !== 'operationId' && key !== 'currency')
      .map(([key, value]) => {
        if (key === 'amount') {
          return `Tutar: ${formatMoney(Number(value), String(metadata.currency ?? 'TRY'))}`;
        }
        if (entry.action === 'SUBSCRIPTION_PLAN_CHANGED') {
          return `${key === 'from' ? 'Eski plan' : 'Yeni plan'}: ${String(value)}`;
        }
        if (key === 'role' || key === 'from' || key === 'to') {
          const label = ROLE_LABELS[value as Role] ?? String(value);
          return `${METADATA_LABELS[key]}: ${label}`;
        }
        return `${METADATA_LABELS[key] ?? key}: ${String(value)}`;
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
        title="Denetim günlüğü"
        description="Önemli işlemlerin değiştirilemez kaydı. Her kayıt bir öncekinin özetini içerir, bu yüzden geçmişle oynanırsa zincir bozulur."
        actions={<button type="button" className="button" onClick={verify} disabled={verifying}>{verifying ? 'Doğrulanıyor…' : 'Zinciri doğrula'}</button>}
      />
      <ErrorNote message={verifyError} />
      {report && (report.valid
        ? <p className="note note-success" role="status">Zincir sağlam: {report.checked.toLocaleString('tr-TR')} kayıt doğrulandı, değiştirilmiş kayıt yok.</p>
        : <p className="note note-danger" role="alert">Zincir bozuk: {report.brokenAtSeq} numaralı kayıt değiştirilmiş veya silinmiş. {report.checked.toLocaleString('tr-TR')} kayıt sağlam.</p>)}
      <div className="toolbar">
        <label className="field field-inline">
          İşlem
          <select value={action} onChange={(e) => setParams(e.target.value ? { action: e.target.value } : {})}>
            <option value="">Tümü</option>
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
                  <th scope="col" className="numeric">No</th>
                  <th scope="col">İşlem</th>
                  <th scope="col">Yapan</th>
                  <th scope="col" className="hide-narrow">Tarih</th>
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
                      {entry.actorLabel ?? 'Sistem'}
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
                {loadingMore ? 'Yükleniyor…' : 'Daha fazla göster'}
              </button>
            </div>
          )}
        </>
      ) : !firstPage.error && <div className="card"><EmptyState title="Kayıt yok" /></div>}
    </>
  );
}
