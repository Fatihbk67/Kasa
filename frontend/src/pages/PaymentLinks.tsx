import { useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import { CopyButton, EmptyState, ErrorNote, FormDialog, formatDateTime, Loading, PageHeader, PaymentLinkStatusBadge } from '../components';
import { canTakePayments, useMe } from '../me';
import { formatMoney, parseAmount } from '../money';
import type { PaymentLink, PublicPaymentLink } from '../types';
import { useFetch } from '../useFetch';
import { BackToDashboard } from './Checkout';

function linkUrl(slug: string): string {
  return `${window.location.origin}/l/${slug}`;
}

function NewLinkDialog({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [amount, setAmount] = useState('');
  const [maxPayments, setMaxPayments] = useState('');
  const [formError, setFormError] = useState<string | null>(null);

  const submit = async () => {
    const minorUnits = amount.trim() ? parseAmount(amount) : null;
    if (amount.trim() && minorUnits === null) {
      setFormError('Geçerli bir tutar girin, örneğin 149,90.');
      return;
    }
    setFormError(null);
    await api('/payment-links', {
      method: 'POST',
      body: {
        title,
        description: description.trim() || null,
        amount: minorUnits,
        currency: 'TRY',
        maxPayments: maxPayments ? Number(maxPayments) : null,
      },
    });
    onCreated();
  };

  return (
    <FormDialog title="Yeni ödeme bağlantısı" submitLabel="Oluştur" onClose={onClose} onSubmit={submit}>
      <label className="field">
        Başlık
        <input required autoFocus maxLength={200} placeholder="Kahve demleme atölyesi" value={title} onChange={(e) => setTitle(e.target.value)} />
      </label>
      <label className="field">
        Açıklama
        <input maxLength={500} value={description} onChange={(e) => setDescription(e.target.value)} />
      </label>
      <label className="field">
        Tutar (₺)
        <input inputMode="decimal" placeholder="149,90" value={amount} onChange={(e) => setAmount(e.target.value)} />
        <span className="hint">Boş bırakırsanız tutarı ödeyen kişi belirler.</span>
      </label>
      <label className="field">
        Kontenjan
        <input type="number" min={1} max={1000000} value={maxPayments} onChange={(e) => setMaxPayments(e.target.value)} />
        <span className="hint">Boş bırakırsanız sınırsız ödeme alınır.</span>
      </label>
      <ErrorNote message={formError} />
    </FormDialog>
  );
}

export function PaymentLinks() {
  const { me } = useMe();
  const links = useFetch(() => api<PaymentLink[]>('/payment-links'), []);
  const [creating, setCreating] = useState(false);
  const [disabling, setDisabling] = useState<PaymentLink | null>(null);
  const operator = canTakePayments(me);

  const disable = async () => {
    await api(`/payment-links/${disabling!.id}/disable`, { method: 'POST' });
    setDisabling(null);
    links.reload();
  };

  return (
    <>
      <PageHeader
        title="Ödeme bağlantıları"
        description="Kod yazmadan ödeme alın: bağlantıyı paylaşın, ödeyen kişi müşterileriniz arasına eklensin."
        actions={operator && <button type="button" className="button button-primary" onClick={() => setCreating(true)}>Yeni bağlantı</button>}
      />
      <ErrorNote message={links.error} />
      {links.loading ? <Loading /> : links.data && links.data.length > 0 ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Bağlantı</th>
                <th scope="col">Durum</th>
                <th scope="col" className="numeric">Tutar</th>
                <th scope="col" className="numeric">Ödeme</th>
                <th scope="col" className="numeric hide-narrow">Toplanan</th>
                <th scope="col"><span className="visually-hidden">İşlemler</span></th>
              </tr>
            </thead>
            <tbody>
              {links.data.map((link) => (
                <tr key={link.id}>
                  <td>
                    {link.title}
                    <span className="hint row-hint">Oluşturulma: {formatDateTime(link.createdAt)}</span>
                  </td>
                  <td><PaymentLinkStatusBadge status={link.status} /></td>
                  <td className="numeric">{link.amount === null ? 'Serbest' : formatMoney(link.amount, link.currency)}</td>
                  <td className="numeric">{link.paidCount}{link.maxPayments !== null && ` / ${link.maxPayments}`}</td>
                  <td className="numeric hide-narrow">{formatMoney(link.collected, link.currency)}</td>
                  <td className="numeric">
                    <span className="row-actions">
                      {link.status === 'OPEN' && <CopyButton value={linkUrl(link.slug)} label="Bağlantıyı kopyala" />}
                      {operator && link.status !== 'DISABLED' && (
                        <button type="button" className="button button-small" onClick={() => setDisabling(link)}>Kapat</button>
                      )}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : !links.error && (
        <div className="card"><EmptyState title="Henüz bağlantı yok">İlk bağlantınızı oluşturup WhatsApp'ta veya sosyal medyada paylaşabilirsiniz.</EmptyState></div>
      )}

      {creating && <NewLinkDialog onClose={() => setCreating(false)} onCreated={() => {
        setCreating(false);
        links.reload();
      }} />}
      {disabling && (
        <FormDialog title="Bağlantıyı kapat" submitLabel="Kapat" danger onClose={() => setDisabling(null)} onSubmit={disable}>
          <p><strong>{disabling.title}</strong> artık ödeme almaz. Ödeme sayfasını açmış kişiler de ödeyemez. Bu işlem geri alınamaz.</p>
        </FormDialog>
      )}
    </>
  );
}

/** The page a visitor lands on. It collects who is paying, then hands over to the ordinary checkout page. */
export function PublicLinkPage() {
  const { slug } = useParams();
  const navigate = useNavigate();
  const link = useFetch(() => api<PublicPaymentLink>(`/public/links/${slug}`), [slug]);
  const [email, setEmail] = useState('');
  const [name, setName] = useState('');
  const [amount, setAmount] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const data = link.data;
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    let minorUnits: number | null = null;
    if (data?.amount === null) {
      minorUnits = parseAmount(amount);
      if (minorUnits === null) {
        setError('Geçerli bir tutar girin, örneğin 149,90.');
        return;
      }
    }
    setBusy(true);
    setError(null);
    try {
      const session = await api<{ id: string }>(`/public/links/${slug}/sessions`, {
        method: 'POST',
        body: { email: email.trim(), name, amount: minorUnits },
      });
      navigate(`/pay/${session.id}`);
    } catch (e) {
      setError(e instanceof ApiError && e.status === 410 ? 'Bu bağlantı artık ödeme almıyor.' : (e as Error).message);
      setBusy(false);
    }
  };

  return (
    <div className="checkout-page">
      <BackToDashboard />
      <div className="card checkout-card">
        {link.loading ? <Loading /> : !data ? (
          <div className="outcome outcome-danger" role="status">
            <h1>Bağlantı bulunamadı</h1>
            <p>{link.errorStatus === 404 ? 'Bağlantıyı işletmeden yeniden isteyin.' : link.error}</p>
          </div>
        ) : (
          <>
            <header className="checkout-header">
              <span className="muted">{data.merchantName}</span>
              <strong className="checkout-amount">{data.amount === null ? data.title : formatMoney(data.amount, data.currency)}</strong>
              {data.amount !== null && <span>{data.title}</span>}
              {data.description && <span className="muted">{data.description}</span>}
            </header>
            {data.status !== 'OPEN' ? (
              <div className="outcome outcome-danger" role="status">
                <h1>{data.status === 'SOLD_OUT' ? 'Kontenjan doldu' : 'Bu bağlantı artık ödeme almıyor'}</h1>
                <p>Ayrıntı için işletmeyle iletişime geçin.</p>
              </div>
            ) : (
              <form onSubmit={submit} className="stack">
                {data.amount === null && (
                  <label className="field">
                    Tutar (₺)
                    <input inputMode="decimal" required autoFocus placeholder="149,90" value={amount} onChange={(e) => setAmount(e.target.value)} />
                  </label>
                )}
                <label className="field">
                  E-posta
                  <input type="email" required maxLength={254} autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} />
                </label>
                <label className="field">
                  Ad soyad
                  <input maxLength={200} autoComplete="name" value={name} onChange={(e) => setName(e.target.value)} />
                </label>
                <p className="hint">Bu bir test sayfasıdır. Gerçek bilgilerinizi girmeyin.</p>
                <ErrorNote message={error} />
                <button type="submit" className="button button-primary button-block" disabled={busy}>
                  {busy ? 'Hazırlanıyor…' : 'Ödemeye geç'}
                </button>
              </form>
            )}
          </>
        )}
      </div>
      <p className="checkout-footer muted">Kasa ile güvenli ödeme · Test modu</p>
    </div>
  );
}
