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
  RISK_REASONS,
  RiskBadge,
} from '../components';
import { canOperate, useMe } from '../me';
import { formatMoney, parseAmount } from '../money';
import type { Dispute, DisputePage, DisputeReason, Operation, OperationResult, Payment, PaymentDetail } from '../types';
import { useToast } from '../toast';
import { useFetch, usePolling } from '../useFetch';
import { DISPUTE_REASONS } from './Disputes';

type Action = 'capture' | 'void' | 'refund';

const OPERATION_LABELS: Record<Operation['type'], string> = {
  AUTHORIZE: 'Provizyon',
  CAPTURE: 'Tahsilat',
  VOID: 'Provizyon iptali',
  REFUND: 'İade',
  DISPUTE_WITHDRAWAL: 'İtiraz kesintisi',
  DISPUTE_REVERSAL: 'İtiraz iadesi',
  DISPUTE_FEE: 'İtiraz ücreti',
};

function SimulateDisputeDialog({ payment, onClose, onDone }: { payment: Payment; onClose: () => void; onDone: (disputeId: string) => void }) {
  const [reason, setReason] = useState<DisputeReason>('PRODUCT_NOT_RECEIVED');

  const submit = async () => {
    const dispute = await api<Dispute>('/disputes/simulate', { method: 'POST', body: { paymentId: payment.id, reason } });
    onDone(dispute.id);
  };

  return (
    <FormDialog title="Banka itirazı simüle et" submitLabel="İtirazı başlat" danger onClose={onClose} onSubmit={submit}>
      <p>Gerçekte itirazı müşterinin bankası başlatır. Tutar bakiyenizden hemen düşer.</p>
      <label className="field">
        Neden
        <select value={reason} onChange={(e) => setReason(e.target.value as DisputeReason)}>
          {Object.entries(DISPUTE_REASONS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
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
  const titles: Record<Action, string> = { capture: 'Tahsil et', void: 'Provizyonu iptal et', refund: 'İade et' };

  const submit = async () => {
    let body: { amount: number } | undefined;
    if (action !== 'void' && amount.trim()) {
      const minorUnits = parseAmount(amount);
      if (minorUnits === null || minorUnits > limit) {
        setAmountError(`En fazla ${formatMoney(limit, payment.currency)} girebilirsiniz.`);
        return;
      }
      body = { amount: minorUnits };
    }
    setAmountError(null);
    const path = { capture: 'capture', void: 'void', refund: 'refunds' }[action];
    const result = await api<OperationResult>(`/payments/${payment.id}/${path}`, { method: 'POST', body, idempotencyKey });
    if (result.operation.status === 'SUCCEEDED') {
      notify({ capture: 'Tahsilat tamamlandı.', void: 'Provizyon iptal edildi.', refund: 'İade tamamlandı.' }[action]);
    }
    onDone(result.operation.status === 'FAILED' ? failureMessage(result.operation.failureCode) : null);
  };

  return (
    <FormDialog title={titles[action]} submitLabel={titles[action]} danger={action !== 'capture'} onClose={onClose} onSubmit={submit}>
      {action === 'void' ? (
        <p>{formatMoney(payment.amount, payment.currency)} tutarındaki provizyon iptal edilecek. Bu işlem geri alınamaz.</p>
      ) : (
        <>
          <label className="field">
            Tutar (₺)
            <input inputMode="decimal" autoFocus placeholder={(limit / 100).toFixed(2).replace('.', ',')} value={amount} onChange={(e) => setAmount(e.target.value)} />
            <span className="hint">Boş bırakırsanız {formatMoney(limit, payment.currency)} işlenir.</span>
          </label>
          <ErrorNote message={amountError} />
          {action === 'refund' && <p className="muted">İade edilen tutarın komisyonu geri ödenmez.</p>}
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
        <ErrorNote message={detail.errorStatus === 404 ? 'Bu ödeme bulunamadı.' : detail.error} />
        <Link to="/payments">Ödemelere dön</Link>
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

  return (
    <>
      <p className="breadcrumb"><Link to="/payments">Ödemeler</Link> / Ödeme ayrıntısı</p>
      <PageHeader
        title={formatMoney(payment.amount, payment.currency)}
        description={payment.description ?? 'Açıklamasız ödeme'}
        actions={(
          <>
            {canCapture && <button type="button" className="button button-primary" onClick={() => setAction('capture')}>Tahsil et</button>}
            {canCapture && <button type="button" className="button" onClick={() => setAction('void')}>İptal et</button>}
            {canRefund && <button type="button" className="button" onClick={() => setAction('refund')}>İade et</button>}
            {canDispute && <button type="button" className="button" onClick={() => setSimulating(true)}>İtiraz simüle et</button>}
            {canOperate(me) && payment.riskLevel !== null && <button type="button" className="button" onClick={() => setBlocking(true)}>Kartı engelle</button>}
          </>
        )}
      />
      <ErrorNote message={operationFailure} />
      {hasPending && (
        <p className="note note-warning" role="status">
          Bankadan yanıt alınamadı. Para iki kez hareket etmez; sonuç bankaya sorularak kısa süre içinde netleşecek.
        </p>
      )}
      {payment.failureCode && <p className="note note-danger">{failureMessage(payment.failureCode)}</p>}
      {dispute && (
        <p className="note note-warning">
          Bu ödemeye itiraz edildi: {formatMoney(dispute.amount, dispute.currency)}. <DisputeStatusBadge status={dispute.status} /> <Link to={`/disputes/${dispute.id}`}>İtirazı aç</Link>
        </p>
      )}

      {(payment.riskLevel === 'REVIEW' || payment.riskLevel === 'BLOCKED') && (
        <section className={held ? 'note note-warning' : 'card risk-card'} aria-label="Risk değerlendirmesi">
          <div className="section-header section-header-tight">
            <strong>Risk değerlendirmesi <RiskBadge level={payment.riskLevel} reviewStatus={payment.reviewStatus} /></strong>
            {held && canOperate(me) && <Link to="/risk" className="button button-small">İncelemeye git</Link>}
          </div>
          <ul className="risk-reasons">
            {payment.riskReasons.map((reason) => <li key={reason}>{RISK_REASONS[reason] ?? reason}</li>)}
          </ul>
          {held && <p>Bu ödeme onaylanana kadar tahsil edilemez.</p>}
          {payment.reviewedBy && payment.reviewedAt && <p className="hint">Karar: {payment.reviewedBy}, {formatDateTime(payment.reviewedAt)}</p>}
        </section>
      )}

      <section className="card">
        <dl className="details">
          <div><dt>Durum</dt><dd><PaymentStatusBadge status={payment.status} /></dd></div>
          <div><dt>Tahsil edilen</dt><dd>{formatMoney(payment.capturedAmount, payment.currency)}</dd></div>
          <div><dt>Komisyon</dt><dd>{formatMoney(payment.feeAmount, payment.currency)}</dd></div>
          <div><dt>İade edilen</dt><dd>{formatMoney(payment.refundedAmount, payment.currency)}</dd></div>
          {payment.disputedAmount > 0 && <div><dt>İtirazda</dt><dd>{formatMoney(payment.disputedAmount, payment.currency)}</dd></div>}
          {payment.customerId && <div><dt>Müşteri</dt><dd><Link to={`/customers/${payment.customerId}`}>Müşteriyi aç</Link></dd></div>}
          <div>
            <dt>Kart doğrulaması</dt>
            <dd>
              {payment.processor === 'IYZICO' ? 'iyzico ödeme sayfasında'
                : payment.threeDsMethod === 'CHALLENGE' ? `3D Secure, SMS ile (ECI ${payment.threeDsEci})`
                  : payment.threeDsMethod === 'FRICTIONLESS' ? `3D Secure, sessiz onay (ECI ${payment.threeDsEci})`
                    : 'Yok (API ile alınan ödeme)'}
            </dd>
          </div>
          <div>
            <dt>Ödeme altyapısı</dt>
            <dd>{payment.processor === 'IYZICO' ? 'iyzico (test ortamı)' : 'Simüle banka'}</dd>
          </div>
          {payment.cardLabel && <div><dt>Kart</dt><dd>{payment.cardLabel}</dd></div>}
          <div><dt>Taksit</dt><dd>{payment.installments === 1 ? 'Tek çekim' : `${payment.installments} taksit`}</dd></div>
          <div><dt>Oluşturulma</dt><dd>{formatDateTime(payment.createdAt)}</dd></div>
          <div className="details-id"><dt>Ödeme kimliği</dt><dd><code>{payment.id}</code> <CopyButton value={payment.id} /></dd></div>
        </dl>
      </section>

      <div className="section-header"><h2>İşlem geçmişi</h2></div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col">İşlem</th>
              <th scope="col">Sonuç</th>
              <th scope="col" className="numeric">Tutar</th>
              <th scope="col" className="hide-narrow">Tarih</th>
            </tr>
          </thead>
          <tbody>
            {operations.map((operation) => (
              <tr key={operation.id}>
                <td>
                  {OPERATION_LABELS[operation.type]}
                  {operation.failureCode && <span className="hint row-hint">{failureMessage(operation.failureCode)}</span>}
                </td>
                <td><OperationStatusBadge status={operation.status} /></td>
                <td className="numeric">{formatMoney(operation.amount, payment.currency)}</td>
                <td className="hide-narrow muted">{formatDateTime(operation.createdAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {action && <ActionDialog action={action} payment={payment} onClose={() => setAction(null)} onDone={finish} />}
      {blocking && (
        <FormDialog title="Kartı engelle" submitLabel="Engelle" danger onClose={() => setBlocking(false)}
          onSubmit={async () => {
            try {
              await api('/risk/blocklist', { method: 'POST', body: { paymentId: payment.id, reason: blockReason.trim() } });
            } catch (e) {
              throw e instanceof ApiError && e.status === 409 ? new Error('Bu kart zaten engelli.') : e;
            }
            setBlocking(false);
            setBlockReason('');
            notify('Kart engellendi. Bu karttan gelen ödemeler bankaya gönderilmeden reddedilecek.');
          }}>
          <p>Bu ödemede kullanılan karttan bundan sonra ödeme alınmaz. Engeli Risk sayfasından kaldırabilirsiniz.</p>
          <label className="field">
            Neden
            <input required autoFocus maxLength={300} value={blockReason} onChange={(e) => setBlockReason(e.target.value)} placeholder="Müşteri kartının çalındığını bildirdi" />
          </label>
        </FormDialog>
      )}
      {simulating && <SimulateDisputeDialog payment={payment} onClose={() => setSimulating(false)} onDone={(disputeId) => navigate(`/disputes/${disputeId}`)} />}
    </>
  );
}
