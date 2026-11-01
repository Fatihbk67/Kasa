import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../api';
import { EmptyState, ErrorNote, FormDialog, Loading, PageHeader } from '../components';
import { canOperate, useMe } from '../me';
import { formatMoney, parseAmount } from '../money';
import type { Balance, DisputePage, PaymentPage } from '../types';
import { useFetch } from '../useFetch';
import { PaymentTable } from './PaymentTable';

function NewCheckoutDialog({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate();
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [amountError, setAmountError] = useState<string | null>(null);

  const submit = async () => {
    const minorUnits = parseAmount(amount);
    if (minorUnits === null) {
      setAmountError('Geçerli bir tutar girin, örneğin 149,90.');
      return;
    }
    setAmountError(null);
    const session = await api<{ id: string }>('/checkout-sessions', {
      method: 'POST',
      body: { amount: minorUnits, currency: 'TRY', description: description.trim() || null },
    });
    navigate(`/pay/${session.id}`);
  };

  return (
    <FormDialog title="Test ödemesi oluştur" submitLabel="Ödeme sayfasına git" onClose={onClose} onSubmit={submit}>
      <label className="field">
        Tutar (₺)
        <input inputMode="decimal" required autoFocus placeholder="149,90" value={amount} onChange={(e) => setAmount(e.target.value)} />
      </label>
      <ErrorNote message={amountError} />
      <label className="field">
        Açıklama
        <input maxLength={500} placeholder="Sipariş #1042" value={description} onChange={(e) => setDescription(e.target.value)} />
      </label>
    </FormDialog>
  );
}

export function Overview() {
  const { me } = useMe();
  const balances = useFetch(() => api<Balance[]>('/balances'), []);
  const payments = useFetch(() => api<PaymentPage>('/payments?limit=5'), []);
  const openDisputes = useFetch(() => api<DisputePage>('/disputes?status=NEEDS_RESPONSE&limit=100'), []);
  const disputeCount = openDisputes.data?.items.length ?? 0;
  const [creating, setCreating] = useState(false);
  const balanceList = balances.data && balances.data.length > 0 ? balances.data : [{ currency: 'TRY', available: 0, inTransit: 0 }];

  return (
    <>
      <PageHeader
        title="Genel bakış"
        actions={canOperate(me) && <button type="button" className="button button-primary" onClick={() => setCreating(true)}>Test ödemesi oluştur</button>}
      />
      {disputeCount > 0 && (
        <p className="note note-warning" role="status">
          {disputeCount} itiraz yanıtınızı bekliyor. Süresinde kanıt göndermezseniz kaybedilir. <Link to="/disputes?status=NEEDS_RESPONSE">İtirazlara git</Link>
        </p>
      )}
      <ErrorNote message={balances.error} />
      {balances.loading && !balances.data ? <Loading /> : balanceList.map((balance) => (
        <section className="stats" key={balance.currency} aria-label={`${balance.currency} bakiyesi`}>
          <div className="card stat">
            <span className="stat-label">Ödenecek bakiye</span>
            <span className={balance.available < 0 ? 'stat-value negative' : 'stat-value'}>{formatMoney(balance.available, balance.currency)}</span>
            <span className="hint">Komisyon düşülmüş, sonraki hesap kesiminde ödenir</span>
          </div>
          <div className="card stat">
            <span className="stat-label">Yoldaki tutar</span>
            <span className="stat-value">{formatMoney(balance.inTransit, balance.currency)}</span>
            <span className="hint">Bankaya gönderildi, sonuç bekleniyor</span>
          </div>
        </section>
      ))}

      <div className="section-header">
        <h2>Son ödemeler</h2>
        <Link to="/payments">Tümünü gör</Link>
      </div>
      <ErrorNote message={payments.error} />
      {payments.loading && !payments.data ? <Loading /> : payments.data && payments.data.items.length > 0
        ? <PaymentTable payments={payments.data.items} />
        : !payments.error && (
          <div className="card">
            <EmptyState title="Henüz ödeme yok">İlk ödemenizi görmek için bir test ödemesi oluşturun.</EmptyState>
          </div>
        )}
      {creating && <NewCheckoutDialog onClose={() => setCreating(false)} />}
    </>
  );
}
