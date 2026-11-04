import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { ErrorNote, failureMessage, Loading } from '../components';
import { formatMoney } from '../money';
import type { CheckoutSession } from '../types';
import { useFetch, usePolling } from '../useFetch';

// Test scenarios stand in for a card form on purpose: this project never collects real card details.
const TEST_CARDS = [
  { token: 'tok_success', label: 'Başarılı ödeme', hint: 'Banka onaylar' },
  { token: 'tok_insufficient_funds', label: 'Yetersiz bakiye', hint: 'Banka reddeder, başka kart denenebilir' },
  { token: 'tok_declined', label: 'Reddedilen kart', hint: 'Banka reddeder, başka kart denenebilir' },
  { token: 'tok_timeout_processed', label: 'Banka yanıt vermiyor', hint: 'İşlem bankada gerçekleşir, sonuç bir dakikayı aşkın sürede netleşir' },
  { token: 'tok_timeout_lost', label: 'İstek bankaya ulaşmıyor', hint: 'Para hareketi olmaz, sonuç bir dakikayı aşkın sürede netleşir' },
];

/**
 * A merchant who opened a test payment from the dashboard needs a way back. A customer never sees this,
 * because their browser holds no merchant session.
 */
export function BackToDashboard() {
  const { isSignedIn } = useAuth();
  return isSignedIn ? <Link className="checkout-back" to="/payments">← Panele dön</Link> : null;
}

function Frame({ session, children }: { session?: CheckoutSession; children: React.ReactNode }) {
  return (
    <div className="checkout-page">
      <BackToDashboard />
      <div className="card checkout-card">
        {session && (
          <header className="checkout-header">
            <span className="muted">{session.merchantName}</span>
            <strong className="checkout-amount">{formatMoney(session.amount, session.currency)}</strong>
            {session.description && <span className="muted">{session.description}</span>}
          </header>
        )}
        {children}
      </div>
      <p className="checkout-footer muted">Kasa ile güvenli ödeme · Test modu</p>
    </div>
  );
}

function Outcome({ tone, title, children }: { tone: 'success' | 'danger' | 'warning'; title: string; children: React.ReactNode }) {
  return (
    <div className={`outcome outcome-${tone}`} role="status">
      <h1>{title}</h1>
      <p>{children}</p>
    </div>
  );
}

export function Checkout() {
  const { id } = useParams();
  const fetched = useFetch(() => api<CheckoutSession>(`/public/checkout/${id}`), [id]);
  const [paid, setPaid] = useState<CheckoutSession | null>(null);
  const [token, setToken] = useState(TEST_CARDS[0].token);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const session = fetched.data ?? paid;
  const awaitingBank = session?.status === 'COMPLETED' && session.paymentStatus === 'PENDING';
  usePolling(awaitingBank, fetched.reload);

  if (fetched.loading && !session) {
    return <div className="checkout-page"><Loading /></div>;
  }
  if (!session) {
    return (
      <Frame>
        <Outcome tone="danger" title="Ödeme sayfası bulunamadı">
          {fetched.errorStatus === 404 ? 'Bağlantıyı işletmeden yeniden isteyin.' : fetched.error}
        </Outcome>
      </Frame>
    );
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const updated = await api<CheckoutSession>(`/public/checkout/${id}/pay`, { method: 'POST', body: { cardToken: token } });
      setPaid(updated);
      fetched.reload();
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        setError('Ödemeniz zaten işleniyor. Lütfen bekleyin.');
      } else if (e instanceof ApiError && e.status === 403) {
        setError('Bu işletme henüz ödeme almaya açık değil. Kartınızdan para çekilmedi.');
      } else {
        setError((e as Error).message);
      }
      fetched.reload();
    } finally {
      setBusy(false);
    }
  };

  if (session.status === 'EXPIRED') {
    return (
      <Frame session={session}>
        <Outcome tone="danger" title="Bağlantının süresi doldu">Yeni bir ödeme bağlantısı için işletmeyle iletişime geçin. Kartınızdan para çekilmedi.</Outcome>
      </Frame>
    );
  }
  if (session.status === 'COMPLETED') {
    if (session.paymentStatus === 'PENDING') {
      return (
        <Frame session={session}>
          <Outcome tone="warning" title="Ödemeniz kontrol ediliyor">
            Bankadan yanıt bekleniyor. Bu sayfayı açık tutabilirsiniz; tekrar ödeme yapmayın, kartınızdan iki kez çekim olmaz.
          </Outcome>
        </Frame>
      );
    }
    if (session.paymentStatus === 'FAILED' || session.paymentStatus === 'DECLINED') {
      return (
        <Frame session={session}>
          <Outcome tone="danger" title="Ödeme tamamlanamadı">Kartınızdan para çekilmedi. Yeni bir ödeme bağlantısı için işletmeyle iletişime geçin.</Outcome>
        </Frame>
      );
    }
    return (
      <Frame session={session}>
        <Outcome tone="success" title="Ödemeniz alındı">{session.merchantName} siparişinizi hazırlayacak. Bu sayfayı kapatabilirsiniz.</Outcome>
      </Frame>
    );
  }

  return (
    <Frame session={session}>
      {/* A fraud block reads like any other refusal, so whoever is testing stolen cards learns nothing about the rules. */}
      {session.failureCode && (
        <p className="note note-danger" role="alert">
          {session.failureCode === 'risk_blocked' ? 'Ödeme onaylanmadı.' : failureMessage(session.failureCode)} Başka bir kart deneyebilirsiniz.
        </p>
      )}
      <ErrorNote message={error} />
      <form onSubmit={submit} className="stack">
        <fieldset className="card-options">
          <legend>Test kartı seçin</legend>
          <p className="hint">Bu bir test sayfasıdır. Gerçek kart bilgisi istenmez ve para hareketi olmaz.</p>
          {TEST_CARDS.map((card) => (
            <label key={card.token} className={token === card.token ? 'card-option selected' : 'card-option'}>
              <input type="radio" name="card" value={card.token} checked={token === card.token} onChange={() => setToken(card.token)} />
              <span>
                <strong>{card.label}</strong>
                <span className="hint">{card.hint}</span>
              </span>
            </label>
          ))}
        </fieldset>
        <button type="submit" className="button button-primary button-block" disabled={busy}>
          {busy ? 'İşleniyor…' : `${formatMoney(session.amount, session.currency)} öde`}
        </button>
      </form>
    </Frame>
  );
}
