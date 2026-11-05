import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api } from '../api';
import { DisputeStatusBadge, EmptyState, ErrorNote, FormDialog, formatDateTime, Loading, PageHeader } from '../components';
import { t, tk } from '../i18n';
import { canOperate, useMe } from '../me';
import { formatMoney } from '../money';
import type { Dispute, DisputePage, DisputeReason, DisputeStatus } from '../types';
import { useToast } from '../toast';
import { useFetch, usePolling } from '../useFetch';

/** Turkish keys, translated where shown. */
export const DISPUTE_REASONS: Record<DisputeReason, string> = {
  FRAUDULENT: tk('Kart sahibi işlemi tanımıyor'),
  PRODUCT_NOT_RECEIVED: tk('Ürün ulaşmadı'),
  PRODUCT_UNACCEPTABLE: tk('Ürün beklendiği gibi değil'),
  DUPLICATE: tk('Mükerrer çekim'),
  OTHER: tk('Diğer'),
};

const STATUS_OPTIONS: { value: DisputeStatus; label: string }[] = [
  { value: 'NEEDS_RESPONSE', label: tk('Yanıt bekliyor') },
  { value: 'UNDER_REVIEW', label: tk('İnceleniyor') },
  { value: 'WON', label: tk('Kazanıldı') },
  { value: 'LOST', label: tk('Kaybedildi') },
];

/** Whole days, rounded up, because "0 days left" would read as already too late. */
export function daysLeft(respondBy: string, now = Date.now()): number {
  return Math.max(0, Math.ceil((new Date(respondBy).getTime() - now) / 86_400_000));
}

export function Disputes() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const status = params.get('status') ?? '';
  const query = `/disputes?limit=30${status ? `&status=${status}` : ''}`;
  const firstPage = useFetch(() => api<DisputePage>(query), [query]);
  const [items, setItems] = useState<Dispute[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreError, setMoreError] = useState<string | null>(null);

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
      const page = await api<DisputePage>(`${query}&before=${cursor}`);
      setItems((current) => [...current, ...page.items]);
      setCursor(page.nextCursor);
    } catch (e) {
      setMoreError((e as Error).message);
    } finally {
      setLoadingMore(false);
    }
  };

  return (
    <>
      <PageHeader title={t('İtirazlar')} description={t('Müşteri bankasına başvurduğunda banka tutarı hemen geri alır. Süresinde kanıt sunarsanız geri kazanabilirsiniz.')} />
      <div className="toolbar">
        <label className="field field-inline">
          {t('Durum')}
          <select value={status} onChange={(e) => setParams(e.target.value ? { status: e.target.value } : {})}>
            <option value="">{t('Tümü')}</option>
            {STATUS_OPTIONS.map((option) => <option key={option.value} value={option.value}>{t(option.label)}</option>)}
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
                  <th scope="col">{t('Neden')}</th>
                  <th scope="col">{t('Durum')}</th>
                  <th scope="col" className="numeric">{t('Tutar')}</th>
                  <th scope="col" className="hide-narrow">{t('Son yanıt')}</th>
                </tr>
              </thead>
              <tbody>
                {items.map((dispute) => (
                  <tr key={dispute.id} className="row-link" onClick={() => navigate(`/disputes/${dispute.id}`)}>
                    <td>
                      <a href={`/disputes/${dispute.id}`} onClick={(e) => e.preventDefault()}>{t(DISPUTE_REASONS[dispute.reason])}</a>
                    </td>
                    <td><DisputeStatusBadge status={dispute.status} /></td>
                    <td className="numeric">{formatMoney(dispute.amount, dispute.currency)}</td>
                    <td className="hide-narrow muted">
                      {dispute.status === 'NEEDS_RESPONSE' ? t('{count} gün kaldı', { count: daysLeft(dispute.respondBy) }) : '—'}
                    </td>
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
      ) : !firstPage.error && (
        <div className="card">
          <EmptyState title={status ? t('Bu durumda itiraz yok') : t('Henüz itiraz yok')}>
            {t('Denemek için tahsil edilmiş bir ödemenin sayfasından banka itirazı simüle edebilirsiniz.')}
          </EmptyState>
        </div>
      )}
    </>
  );
}

function EvidenceDialog({ dispute, onClose, onDone }: { dispute: Dispute; onClose: () => void; onDone: () => void }) {
  const [explanation, setExplanation] = useState('');
  const [tracking, setTracking] = useState('');
  const [communication, setCommunication] = useState('');

  const submit = async () => {
    await api(`/disputes/${dispute.id}/evidence`, { method: 'POST', body: { explanation, tracking, communication } });
    onDone();
  };

  return (
    <FormDialog title={t('Kanıt gönder')} submitLabel={t('Bankaya gönder')} onClose={onClose} onSubmit={submit}>
      <p className="muted">{t('Bir kez gönderilir, sonradan değiştirilemez.')}</p>
      <label className="field">
        {t('Açıklama')}
        <textarea required autoFocus maxLength={4000} rows={4} value={explanation} onChange={(e) => setExplanation(e.target.value)} />
      </label>
      <label className="field">
        {t('Kargo takip numarası')}
        <input maxLength={200} value={tracking} onChange={(e) => setTracking(e.target.value)} />
        <span className="hint">{t('Simüle banka, teslimat kanıtı olan itirazları işletme lehine sonuçlandırır.')}</span>
      </label>
      <label className="field">
        {t('Müşteriyle yazışma özeti')}
        <textarea maxLength={4000} rows={3} value={communication} onChange={(e) => setCommunication(e.target.value)} />
      </label>
    </FormDialog>
  );
}

export function DisputeDetailPage() {
  const notify = useToast();
  const { id } = useParams();
  const { me } = useMe();
  const detail = useFetch(() => api<Dispute>(`/disputes/${id}`), [id]);
  const [dialog, setDialog] = useState<'evidence' | 'accept' | null>(null);
  usePolling(detail.data?.status === 'UNDER_REVIEW', detail.reload);

  if (detail.loading && !detail.data) {
    return <Loading />;
  }
  if (!detail.data) {
    return (
      <>
        <ErrorNote message={detail.errorStatus === 404 ? t('Bu itiraz bulunamadı.') : detail.error} />
        <Link to="/disputes">{t('İtirazlara dön')}</Link>
      </>
    );
  }

  const dispute = detail.data;
  const open = dispute.status === 'NEEDS_RESPONSE' && canOperate(me);
  const finish = (message: string) => {
    setDialog(null);
    detail.reload();
    notify(message);
  };
  const accept = async () => {
    await api(`/disputes/${dispute.id}/accept`, { method: 'POST' });
    finish(t('İtiraz kabul edildi.'));
  };

  return (
    <>
      <p className="breadcrumb"><Link to="/disputes">{t('İtirazlar')}</Link> / {t('Ayrıntı')}</p>
      <PageHeader
        title={formatMoney(dispute.amount, dispute.currency)}
        description={t(DISPUTE_REASONS[dispute.reason])}
        actions={open && (
          <>
            <button type="button" className="button button-primary" onClick={() => setDialog('evidence')}>{t('Kanıt gönder')}</button>
            <button type="button" className="button" onClick={() => setDialog('accept')}>{t('Kabul et')}</button>
          </>
        )}
      />
      {dispute.status === 'NEEDS_RESPONSE' && (
        <p className="note note-warning" role="status">
          {t('Tutar bakiyenizden düşüldü. {date} tarihine kadar ({count} gün) kanıt göndermezseniz itiraz kaybedilir.', { date: formatDateTime(dispute.respondBy), count: daysLeft(dispute.respondBy) })}
        </p>
      )}
      {dispute.status === 'UNDER_REVIEW' && <p className="note note-warning" role="status">{t('Kanıtınız bankada inceleniyor. Sonuç bu sayfada görünecek.')}</p>}
      {dispute.status === 'WON' && <p className="note note-success" role="status">{t('İtirazı kazandınız. Tutar bakiyenize geri döndü.')}</p>}
      {dispute.status === 'LOST' && (
        <p className="note note-danger" role="status">
          {dispute.feeAmount > 0
            ? t('İtiraz kaybedildi. Tutar müşteride kaldı, ayrıca {fee} itiraz ücreti kesildi.', { fee: formatMoney(dispute.feeAmount, dispute.currency) })
            : t('İtiraz kaybedildi. Tutar müşteride kaldı.')}
        </p>
      )}

      <section className="card">
        <dl className="details">
          <div><dt>{t('Durum')}</dt><dd><DisputeStatusBadge status={dispute.status} /></dd></div>
          <div><dt>{t('Ödeme')}</dt><dd><Link to={`/payments/${dispute.paymentId}`}><code>{dispute.paymentId.slice(0, 8)}</code></Link></dd></div>
          <div><dt>{t('Açılış')}</dt><dd>{formatDateTime(dispute.createdAt)}</dd></div>
          <div><dt>{t('Son yanıt')}</dt><dd>{formatDateTime(dispute.respondBy)}</dd></div>
          {dispute.resolvedAt && <div><dt>{t('Sonuç tarihi')}</dt><dd>{formatDateTime(dispute.resolvedAt)}</dd></div>}
        </dl>
      </section>

      {dispute.evidenceSubmittedAt && (
        <>
          <div className="section-header"><h2>{t('Gönderilen kanıt')}</h2></div>
          <section className="card">
            <dl className="details">
              <div className="details-id"><dt>{t('Açıklama')}</dt><dd className="prewrap">{dispute.evidenceExplanation}</dd></div>
              {dispute.evidenceTracking && <div><dt>{t('Kargo takip')}</dt><dd>{dispute.evidenceTracking}</dd></div>}
              {dispute.evidenceCommunication && <div className="details-id"><dt>{t('Yazışma')}</dt><dd className="prewrap">{dispute.evidenceCommunication}</dd></div>}
              <div><dt>{t('Gönderim')}</dt><dd>{formatDateTime(dispute.evidenceSubmittedAt)}</dd></div>
            </dl>
          </section>
        </>
      )}

      {dialog === 'evidence' && <EvidenceDialog dispute={dispute} onClose={() => setDialog(null)} onDone={() => finish(t('Kanıt bankaya gönderildi.'))} />}
      {dialog === 'accept' && (
        <FormDialog title={t('İtirazı kabul et')} submitLabel={t('Kabul et')} danger onClose={() => setDialog(null)} onSubmit={accept}>
          <p>{t('{amount} müşteride kalır ve itiraz ücreti kesilir. Bu işlem geri alınamaz.', { amount: formatMoney(dispute.amount, dispute.currency) })}</p>
        </FormDialog>
      )}
    </>
  );
}
