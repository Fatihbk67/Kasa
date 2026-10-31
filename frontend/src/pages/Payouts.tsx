import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../api';
import { EmptyState, ErrorNote, failureMessage, formatDate, formatDateTime, Loading, PageHeader, PayoutStatusBadge } from '../components';
import { formatMoney } from '../money';
import type { Payout, PayoutDetail } from '../types';
import { useFetch } from '../useFetch';

export function Payouts() {
  const navigate = useNavigate();
  const payouts = useFetch(() => api<Payout[]>('/payouts'), []);

  return (
    <>
      <PageHeader title="Hesap kesimleri" description="Tahsil edilen ödemeler komisyon düşülerek her gün hesabınıza aktarılır." />
      <ErrorNote message={payouts.error} />
      {payouts.loading ? <Loading /> : payouts.data && payouts.data.length > 0 ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Hesap kesim tarihi</th>
                <th scope="col">Durum</th>
                <th scope="col" className="numeric">Net tutar</th>
                <th scope="col" className="hide-narrow">Ödeme zamanı</th>
              </tr>
            </thead>
            <tbody>
              {payouts.data.map((payout) => (
                <tr key={payout.id} className="row-link" onClick={() => navigate(`/payouts/${payout.id}`)}>
                  <td>
                    <a href={`/payouts/${payout.id}`} onClick={(e) => e.preventDefault()}>{formatDate(payout.settlementDate)}</a>
                  </td>
                  <td><PayoutStatusBadge status={payout.status} /></td>
                  <td className="numeric">{formatMoney(payout.amount, payout.currency)}</td>
                  <td className="hide-narrow muted">{payout.paidAt ? formatDateTime(payout.paidAt) : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : !payouts.error && (
        <div className="card">
          <EmptyState title="Henüz hesap kesimi yok">Tahsil ettiğiniz ödemeler günlük hesap kesiminde burada görünür.</EmptyState>
        </div>
      )}
    </>
  );
}

export function PayoutDetailPage() {
  const { id } = useParams();
  const detail = useFetch(() => api<PayoutDetail>(`/payouts/${id}`), [id]);

  if (detail.loading && !detail.data) {
    return <Loading />;
  }
  if (!detail.data) {
    return (
      <>
        <ErrorNote message={detail.errorStatus === 404 ? 'Bu hesap kesimi bulunamadı.' : detail.error} />
        <Link to="/payouts">Hesap kesimlerine dön</Link>
      </>
    );
  }

  const { payout, items } = detail.data;
  return (
    <>
      <p className="breadcrumb"><Link to="/payouts">Hesap kesimleri</Link> / Ayrıntı</p>
      <PageHeader title={formatMoney(payout.amount, payout.currency)} description={`${formatDate(payout.settlementDate)} hesap kesimi`} actions={<PayoutStatusBadge status={payout.status} />} />
      {payout.status === 'FAILED' && (
        <p className="note note-danger">{failureMessage(payout.failureCode)} Tutar bakiyenize geri döndü, sonraki hesap kesiminde yeniden denenecek.</p>
      )}
      <div className="section-header"><h2>Kalemler</h2></div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col">Ödeme</th>
              <th scope="col">Tür</th>
              <th scope="col" className="numeric">Brüt</th>
              <th scope="col" className="numeric">Komisyon</th>
              <th scope="col" className="numeric">Net</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <tr key={item.operationId}>
                <td><Link to={`/payments/${item.paymentId}`}><code>{item.paymentId.slice(0, 8)}</code></Link></td>
                <td>{item.type === 'CAPTURE' ? 'Tahsilat' : 'İade'}</td>
                <td className="numeric">{formatMoney(item.grossAmount, payout.currency)}</td>
                <td className="numeric">{formatMoney(item.feeAmount, payout.currency)}</td>
                <td className={item.netAmount < 0 ? 'numeric negative' : 'numeric'}>{formatMoney(item.netAmount, payout.currency)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
