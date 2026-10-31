import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, newIdempotencyKey } from '../api';
import {
  CopyButton,
  ErrorNote,
  failureMessage,
  FormDialog,
  formatDateTime,
  Loading,
  OperationStatusBadge,
  PageHeader,
  PaymentStatusBadge,
} from '../components';
import { formatMoney, parseAmount } from '../money';
import type { Operation, OperationResult, Payment, PaymentDetail } from '../types';
import { useFetch, usePolling } from '../useFetch';

type Action = 'capture' | 'void' | 'refund';

const OPERATION_LABELS: Record<Operation['type'], string> = {
  AUTHORIZE: 'Provizyon',
  CAPTURE: 'Tahsilat',
  VOID: 'Provizyon iptali',
  REFUND: 'İade',
};

function ActionDialog({ action, payment, onClose, onDone }: {
  action: Action;
  payment: Payment;
  onClose: () => void;
  onDone: (message: string | null) => void;
}) {
  const limit = action === 'capture' ? payment.amount : payment.capturedAmount - payment.refundedAmount;
  const [amount, setAmount] = useState('');
  const [amountError, setAmountError] = useState<string | null>(null);
  // Stays the same for this dialog, so a repeated click on submit can never be processed twice.
  const [idempotencyKey] = useState(newIdempotencyKey);
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
  const detail = useFetch(() => api<PaymentDetail>(`/payments/${id}`), [id]);
  const [action, setAction] = useState<Action | null>(null);
  const [operationFailure, setOperationFailure] = useState<string | null>(null);

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
  const refundable = payment.capturedAmount - payment.refundedAmount;
  const canCapture = payment.status === 'AUTHORIZED' && !hasPending;
  const canRefund = (payment.status === 'CAPTURED' || payment.status === 'PARTIALLY_REFUNDED') && refundable > 0 && !hasPending;

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

      <section className="card">
        <dl className="details">
          <div><dt>Durum</dt><dd><PaymentStatusBadge status={payment.status} /></dd></div>
          <div><dt>Tahsil edilen</dt><dd>{formatMoney(payment.capturedAmount, payment.currency)}</dd></div>
          <div><dt>Komisyon</dt><dd>{formatMoney(payment.feeAmount, payment.currency)}</dd></div>
          <div><dt>İade edilen</dt><dd>{formatMoney(payment.refundedAmount, payment.currency)}</dd></div>
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
    </>
  );
}
