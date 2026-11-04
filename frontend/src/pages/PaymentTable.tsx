import { useNavigate } from 'react-router-dom';
import { formatDateTime, PaymentStatusBadge, RiskBadge } from '../components';
import { formatMoney } from '../money';
import type { Payment } from '../types';

export function PaymentTable({ payments }: { payments: Payment[] }) {
  const navigate = useNavigate();
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th scope="col">Açıklama</th>
            <th scope="col">Durum</th>
            <th scope="col" className="numeric">Tutar</th>
            <th scope="col" className="hide-narrow">Tarih</th>
          </tr>
        </thead>
        <tbody>
          {payments.map((payment) => (
            <tr key={payment.id} className="row-link" onClick={() => navigate(`/payments/${payment.id}`)}>
              <td>
                <a href={`/payments/${payment.id}`} onClick={(e) => e.preventDefault()}>
                  {payment.description || 'Açıklamasız ödeme'}
                </a>
              </td>
              <td>
                <PaymentStatusBadge status={payment.status} />
                {(payment.reviewStatus === 'OPEN' || payment.riskLevel === 'BLOCKED') && (
                  <span className="row-hint"><RiskBadge level={payment.riskLevel} reviewStatus={payment.reviewStatus} /></span>
                )}
                {payment.disputedAmount > 0 && <span className="hint row-hint">İtiraz: {formatMoney(payment.disputedAmount, payment.currency)}</span>}
              </td>
              <td className="numeric">{formatMoney(payment.amount, payment.currency)}</td>
              <td className="hide-narrow muted">{formatDateTime(payment.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
