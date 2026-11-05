import { useNavigate } from 'react-router-dom';
import { formatDateTime, PaymentStatusBadge, RiskBadge } from '../components';
import { t } from '../i18n';
import { formatMoney } from '../money';
import type { Payment } from '../types';

export function PaymentTable({ payments }: { payments: Payment[] }) {
  const navigate = useNavigate();
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th scope="col">{t('Açıklama')}</th>
            <th scope="col">{t('Durum')}</th>
            <th scope="col" className="numeric">{t('Tutar')}</th>
            <th scope="col" className="hide-narrow">{t('Tarih')}</th>
          </tr>
        </thead>
        <tbody>
          {payments.map((payment) => (
            <tr key={payment.id} className="row-link" onClick={() => navigate(`/payments/${payment.id}`)}>
              <td>
                <a href={`/payments/${payment.id}`} onClick={(e) => e.preventDefault()}>
                  {payment.description || t('Açıklamasız ödeme')}
                </a>
                {payment.processor === 'IYZICO' && (
                  <span className="hint row-hint">iyzico{payment.cardLabel ? ` · ${payment.cardLabel}` : ''}</span>
                )}
              </td>
              <td>
                <PaymentStatusBadge status={payment.status} />
                {(payment.reviewStatus === 'OPEN' || payment.riskLevel === 'BLOCKED') && (
                  <span className="row-hint"><RiskBadge level={payment.riskLevel} reviewStatus={payment.reviewStatus} /></span>
                )}
                {payment.disputedAmount > 0 && <span className="hint row-hint">{t('İtiraz: {amount}', { amount: formatMoney(payment.disputedAmount, payment.currency) })}</span>}
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
