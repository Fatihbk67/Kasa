import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api, ApiError, newIdempotencyKey } from '../api';
import {
  CopyButton,
  DisputeStatusBadge,
  ErrorNote,
  failureMessage,
  FormDialog,
  formatDateTime,
  Loading,
  OperationStatusBadge,
  PageHeader,
  PaymentStatusBadge,
  RiskBadge,
  riskReason,
} from '../components';
import { t, tk } from '../i18n';
import { canOperate, useMe } from '../me';
import { formatMoney, parseAmount } from '../money';
import type { Dispute, DisputePage, DisputeReason, Operation, OperationResult, Payment, PaymentDetail } from '../types';
import { useToast } from '../toast';
import { useFetch, usePolling } from '../useFetch';
import { DISPUTE_REASONS } from './Disputes';

type Action = 'capture' | 'void' | 'refund';

const OPERATION_LABELS: Record<Operation['type'], string> = {
  AUTHORIZE: tk('Provizyon'),
  CAPTURE: tk('Tahsilat'),
  VOID: tk('Provizyon iptali'),
  REFUND: tk('İade'),
  DISPUTE_WITHDRAWAL: tk('İtiraz kesintisi'),
  DISPUTE_REVERSAL: tk('İtiraz iadesi'),
  DISPUTE_FEE: tk('İtiraz ücreti'),
};

/** Money leaving the merchant's balance, shown with a minus so the story reads like a statement. */
const OUTGOING: Operation['type'][] = ['REFUND', 'DISPUTE_WITHDRAWAL', 'DISPUTE_FEE'];

const STATUS_TONE: Record<Operation['status'], string> = { SUCCEEDED: 'success', FAILED: 'danger', PENDING: 'warning' };

/** Oldest first, starting from the payment itself, so each step reads as what happened next. */
export function timelineOrder(operations: Operation[]): Operation[] {
  return [...operations].sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt));
}

function installmentText(count: number): string {
  return count === 1 ? t('Tek çekim') : t('{count} taksit', { count });
}

function Timeline({ payment, operations }: { payment: Payment; operations: Operation[] }) {
  const source = payment.processor === 'IYZICO' ? `iyzico${payment.cardLabel ? ` · ${payment.cardLabel}` : ''}` : t('Simüle banka');
  return (
    <ol className="card timeline">
      <li className="timeline-item timeline-neutral">
        <span className="timeline-dot" aria-hidden="true" />
        <div className="timeline-body">
          <div className="timeline-row">
            <strong>{t('Ödeme oluşturuldu')}</strong>
            <span className="numeric">{formatMoney(payment.amount, payment.currency)}</span>
          </div>
          <span className="hint">{source} · {installmentText(payment.installments)} · {formatDateTime(payment.createdAt)}</span>
        </div>
      </li>
      {timelineOrder(operations).map((operation) => (
        <li key={operation.id} className={`timeline-item timeline-${STATUS_TONE[operation.status]}`}>
          <span className="timeline-dot" aria-hidden="true" />
          <div className="timeline-body">
            <div className="timeline-row">
              <strong>{t(OPERATION_LABELS[operation.type])} <OperationStatusBadge status={operation.status} /></strong>
              <span className="numeric">
                {OUTGOING.includes(operation.type) && operation.status === 'SUCCEEDED' ? '−' : ''}{formatMoney(operation.amount, payment.currency)}
              </span>
            </div>
            {operation.failureCode && <span className="timeline-failure">{failureMessage(operation.failureCode)}</span>}
            <span className="hint"><time dateTime={operation.createdAt}>{formatDateTime(operation.createdAt)}</time></span>
          </div>
        </li>
      ))}
    </ol>
  );
}

function SimulateDisputeDialog({ payment, onClose, onDone }: { payment: Payment; onClose: () => void; onDone: (disputeId: string) => void }) {
  const [reason, setReason] = useState<DisputeReason>('PRODUCT_NOT_RECEIVED');

  const submit = async () => {
    const dispute = await api<Dispute>('/disputes/simulate', { method: 'POST', body: { paymentId: payment.id, reason } });
    onDone(dispute.id);
  };

  return (
    <FormDialog title={t('Banka itirazı simüle et')} submitLabel={t('İtirazı başlat')} danger onClose={onClose} onSubmit={submit}>
      <p>{t('Gerçekte itirazı müşterinin bankası başlatır. Tutar bakiyenizden hemen düşer.')}</p>
      <label className="field">
        {t('Neden')}
        <select value={reason} onChange={(e) => setReason(e.target.value as DisputeReason)}>
          {Object.entries(DISPUTE_REASONS).map(([value, label]) => <option key={value} value={value}>{t(label)}</option>)}
        </select>
      </label>
    </FormDialog>
  );
}

function ActionDialog({ action, payment, onClose, onDone }: {
  action: Action;
  payment: Payment;
  onClose: () => void;
  onDone: (message: string | null) => void;
}) {
  const limit = action === 'capture' ? payment.amount : payment.capturedAmount - payment.refundedAmount - payment.disputedAmount;
  const [amount, setAmount] = useState('');
  const [amountError, setAmountError] = useState<string | null>(null);
  // Stays the same for this dialog, so a repeated click on submit can never be processed twice.
  const [idempotencyKey] = useState(newIdempotencyKey);
  const notify = useToast();
  const titles: Record<Action, string> = { capture: t('Tahsil et'), void: t('Provizyonu iptal et'), refund: t('İade et') };

  const submit = async () => {
    let body: { amount: number } | undefined;
    if (action !== 'void' && amount.trim()) {
      const minorUnits = parseAmount(amount);
      if (minorUnits === null || minorUnits > limit) {
        setAmountError(t('En fazla {amount} girebilirsiniz.', { amount: formatMoney(limit, payment.currency) }));
        return;
      }
      body = { amount: minorUnits };
    }
    setAmountError(null);
    const path = { capture: 'capture', void: 'void', refund: 'refunds' }[action];
    const result = await api<OperationResult>(`/payments/${payment.id}/${path}`, { method: 'POST', body, idempotencyKey });
    if (result.operation.status === 'SUCCEEDED') {
      notify({ capture: t('Tahsilat tamamlandı.'), void: t('Provizyon iptal edildi.'), refund: t('İade tamamlandı.') }[action]);
    }
    onDone(result.operation.status === 'FAILED' ? failureMessage(result.operation.failureCode) : null);
  };

  return (
    <FormDialog title={titles[action]} submitLabel={titles[action]} danger={action !== 'capture'} onClose={onClose} onSubmit={submit}>
      {action === 'void' ? (
        <p>{t('{amount} tutarındaki provizyon iptal edilecek. Bu işlem geri alınamaz.', { amount: formatMoney(payment.amount, payment.currency) })}</p>
      ) : (
        <>
          <label className="field">
            {t('Tutar (₺)')}
            <input inputMode="decimal" autoFocus placeholder={(limit / 100).toFixed(2).replace('.', ',')} value={amount} onChange={(e) => setAmount(e.target.value)} />
            <span className="hint">{t('Boş bırakırsanız {amount} işlenir.', { amount: formatMoney(limit, payment.currency) })}</span>
          </label>
          <ErrorNote message={amountError} />
          {action === 'refund' && <p className="muted">{t('İade edilen tutarın komisyonu geri ödenmez.')}</p>}
        </>
      )}
    </FormDialog>
  );
}

export function PaymentDetailPage() {
  const { id } = useParams();
  const { me } = useMe();
  const navigate = useNavigate();
  const detail = useFetch(() => api<PaymentDetail>(`/payments/${id}`), [id]);
  const disputes = useFetch(() => api<DisputePage>(`/disputes?paymentId=${id}&limit=1`), [id]);
  const [simulating, setSimulating] = useState(false);
  const [action, setAction] = useState<Action | null>(null);
  const [operationFailure, setOperationFailure] = useState<string | null>(null);
  const [blocking, setBlocking] = useState(false);
  const [blockReason, setBlockReason] = useState('');
  const notify = useToast();

  const hasPending = detail.data?.operations.some((operation) => operation.status === 'PENDING') ?? false;
  usePolling(hasPending, detail.reload);

  if (detail.loading && !detail.data) {
    return <Loading />;
  }
  if (!detail.data) {
    return (
      <>
        <ErrorNote message={detail.errorStatus === 404 ? t('Bu ödeme bulunamadı.') : detail.error} />
        <Link to="/payments">{t('Ödemelere dön')}</Link>
      </>
    );
  }

  const { payment, operations } = detail.data;
  const refundable = payment.capturedAmount - payment.refundedAmount - payment.disputedAmount;
  const canAct = canOperate(me) && !hasPending;
  const held = payment.reviewStatus === 'OPEN';
  const canCapture = payment.status === 'AUTHORIZED' && canAct && !held && payment.reviewStatus !== 'REJECTED';
  const canRefund = (payment.status === 'CAPTURED' || payment.status === 'PARTIALLY_REFUNDED') && refundable > 0 && canAct;
  const dispute = disputes.data?.items[0] ?? null;
  const canDispute = (payment.status === 'CAPTURED' || payment.status === 'PARTIALLY_REFUNDED') && canAct && Boolean(disputes.data) && !dispute;

  const finish = (message: string | null) => {
    setAction(null);
    setOperationFailure(message);
    detail.reload();
  };

  const verification = payment.processor === 'IYZICO' ? t('iyzico ödeme sayfasında')
    : payment.threeDsMethod === 'CHALLENGE' ? t('3D Secure, SMS ile (ECI {eci})', { eci: payment.threeDsEci ?? '' })
      : payment.threeDsMethod === 'FRICTIONLESS' ? t('3D Secure, sessiz onay (ECI {eci})', { eci: payment.threeDsEci ?? '' })
        : t('Yok (API ile alınan ödeme)');

  return (
    <>
      <p className="breadcrumb"><Link to="/payments">{t('Ödemeler')}</Link> / {t('Ödeme ayrıntısı')}</p>
      <PageHeader
        title={formatMoney(payment.amount, payment.currency)}
        description={payment.description ?? t('Açıklamasız ödeme')}
        actions={(
          <>
            {canCapture && <button type="button" className="button button-primary" onClick={() => setAction('capture')}>{t('Tahsil et')}</button>}
            {canCapture && <button type="button" className="button" onClick={() => setAction('void')}>{t('İptal et')}</button>}
            {canRefund && <button type="button" className="button" onClick={() => setAction('refund')}>{t('İade et')}</button>}
            {canDispute && <button type="button" className="button" onClick={() => setSimulating(true)}>{t('İtiraz simüle et')}</button>}
            {canOperate(me) && payment.riskLevel !== null && <button type="button" className="button" onClick={() => setBlocking(true)}>{t('Kartı engelle')}</button>}
          </>
        )}
      />
      <ErrorNote message={operationFailure} />
      {hasPending && (
        <p className="note note-warning" role="status">
          {t('Bankadan yanıt alınamadı. Para iki kez hareket etmez; sonuç bankaya sorularak kısa süre içinde netleşecek.')}
        </p>
      )}
      {payment.failureCode && <p className="note note-danger">{failureMessage(payment.failureCode)}</p>}
      {dispute && (
        <p className="note note-warning">
          {t('Bu ödemeye itiraz edildi: {amount}.', { amount: formatMoney(dispute.amount, dispute.currency) })} <DisputeStatusBadge status={dispute.status} /> <Link to={`/disputes/${dispute.id}`}>{t('İtirazı aç')}</Link>
        </p>
      )}

      {(payment.riskLevel === 'REVIEW' || payment.riskLevel === 'BLOCKED') && (
        <section className={held ? 'note note-warning' : 'card risk-card'} aria-label={t('Risk değerlendirmesi')}>
          <div className="section-header section-header-tight">
            <strong>{t('Risk değerlendirmesi')} <RiskBadge level={payment.riskLevel} reviewStatus={payment.reviewStatus} /></strong>
            {held && canOperate(me) && <Link to="/risk" className="button button-small">{t('İncelemeye git')}</Link>}
          </div>
          <ul className="risk-reasons">
            {payment.riskReasons.map((reason) => <li key={reason}>{riskReason(reason)}</li>)}
          </ul>
          {held && <p>{t('Bu ödeme onaylanana kadar tahsil edilemez.')}</p>}
          {payment.reviewedBy && payment.reviewedAt && <p className="hint">{t('Karar: {who}, {when}', { who: payment.reviewedBy, when: formatDateTime(payment.reviewedAt) })}</p>}
        </section>
      )}

      <section className="card">
        <dl className="details">
          <div><dt>{t('Durum')}</dt><dd><PaymentStatusBadge status={payment.status} /></dd></div>
          <div><dt>{t('Tahsil edilen')}</dt><dd>{formatMoney(payment.capturedAmount, payment.currency)}</dd></div>
          <div><dt>{t('Komisyon')}</dt><dd>{formatMoney(payment.feeAmount, payment.currency)}</dd></div>
          <div><dt>{t('İade edilen')}</dt><dd>{formatMoney(payment.refundedAmount, payment.currency)}</dd></div>
          {payment.disputedAmount > 0 && <div><dt>{t('İtirazda')}</dt><dd>{formatMoney(payment.disputedAmount, payment.currency)}</dd></div>}
          {payment.customerId && <div><dt>{t('Müşteri')}</dt><dd><Link to={`/customers/${payment.customerId}`}>{t('Müşteriyi aç')}</Link></dd></div>}
          <div><dt>{t('Kart doğrulaması')}</dt><dd>{verification}</dd></div>
          <div>
            <dt>{t('Ödeme altyapısı')}</dt>
            <dd>{payment.processor === 'IYZICO' ? t('iyzico (test ortamı)') : t('Simüle banka')}</dd>
          </div>
          {payment.cardLabel && <div><dt>{t('Kart')}</dt><dd>{payment.cardLabel}</dd></div>}
          <div><dt>{t('Taksit')}</dt><dd>{installmentText(payment.installments)}</dd></div>
          <div><dt>{t('Oluşturulma')}</dt><dd>{formatDateTime(payment.createdAt)}</dd></div>
          <div className="details-id"><dt>{t('Ödeme kimliği')}</dt><dd><code>{payment.id}</code> <CopyButton value={payment.id} /></dd></div>
        </dl>
      </section>

      <div className="section-header"><h2>{t('İşlem geçmişi')}</h2></div>
      <Timeline payment={payment} operations={operations} />
      {action && <ActionDialog action={action} payment={payment} onClose={() => setAction(null)} onDone={finish} />}
      {blocking && (
        <FormDialog title={t('Kartı engelle')} submitLabel={t('Engelle')} danger onClose={() => setBlocking(false)}
          onSubmit={async () => {
            try {
              await api('/risk/blocklist', { method: 'POST', body: { paymentId: payment.id, reason: blockReason.trim() } });
            } catch (e) {
              throw e instanceof ApiError && e.status === 409 ? new Error(t('Bu kart zaten engelli.')) : e;
            }
            setBlocking(false);
            setBlockReason('');
            notify(t('Kart engellendi. Bu karttan gelen ödemeler bankaya gönderilmeden reddedilecek.'));
          }}>
          <p>{t('Bu ödemede kullanılan karttan bundan sonra ödeme alınmaz. Engeli Risk sayfasından kaldırabilirsiniz.')}</p>
          <label className="field">
            {t('Neden')}
            <input required autoFocus maxLength={300} value={blockReason} onChange={(e) => setBlockReason(e.target.value)} placeholder={t('Müşteri kartının çalındığını bildirdi')} />
          </label>
        </FormDialog>
      )}
      {simulating && <SimulateDisputeDialog payment={payment} onClose={() => setSimulating(false)} onDone={(disputeId) => navigate(`/disputes/${disputeId}`)} />}
    </>
  );
}
