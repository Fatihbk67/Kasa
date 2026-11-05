import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { ErrorNote, failureMessage, Loading, usePageTitle } from '../components';
import { formatMoney } from '../money';
import type { CheckoutSession } from '../types';
import { useFetch, usePolling } from '../useFetch';

// Test scenarios stand in for a card form on purpose: this project never collects real card details.
const TEST_CARDS = [
  { token: 'tok_success', label: 'Başarılı ödeme', hint: 'Banka onaylar, ek doğrulama istemez' },
  { token: 'tok_3ds', label: '3D Secure doğrulamalı kart', hint: 'Bankanızın SMS doğrulama sayfası açılır' },
  { token: 'tok_insufficient_funds', label: 'Yetersiz bakiye', hint: 'Banka reddeder, başka kart denenebilir' },
  { token: 'tok_declined', label: 'Reddedilen kart', hint: 'Banka reddeder, başka kart denenebilir' },
  { token: 'tok_timeout_processed', label: 'Banka yanıt vermiyor', hint: 'İşlem bankada gerçekleşir, sonuç bir dakikayı aşkın sürede netleşir' },
  { token: 'tok_timeout_lost', label: 'İstek bankaya ulaşmıyor', hint: 'Para hareketi olmaz, sonuç bir dakikayı aşkın sürede netleşir' },
];

const INSTALLMENT_COUNTS = [1, 2, 3, 6, 9, 12];
const INSTALLMENT_MINIMUM = 20_000;

/** The same total split into months. Rounded up, so the monthly parts together never fall short of the price. */
export function installmentOptions(amount: number): { count: number; monthly: number }[] {
  const counts = amount >= INSTALLMENT_MINIMUM ? INSTALLMENT_COUNTS : [1];
  return counts.map((count) => ({ count, monthly: Math.ceil(amount / count) }));
}

/**
 * A merchant who opened a test payment from the dashboard needs a way back. A customer never sees this,
 * because their browser holds no merchant session.
 */
export function BackToDashboard() {
  const { isSignedIn } = useAuth();
  return isSignedIn ? <Link className="checkout-back" to="/payments">← Panele dön</Link> : null;
}

function Frame({ session, children }: { session?: CheckoutSession; children: React.ReactNode }) {
  usePageTitle(session ? `${session.merchantName} ödemesi` : 'Ödeme');
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
  const [installments, setInstallments] = useState(1);
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const session = fetched.data ?? paid;
  const awaitingBank = session?.status === 'COMPLETED' && session.paymentStatus === 'PENDING';
  usePolling(awaitingBank, fetched.reload);
  // Set by our callback when iyzico's answer could not be fetched or checked.
  const iyzicoTrouble = new URLSearchParams(location.search).get('iyzico') === 'error';

  const payWithIyzico = async () => {
    setBusy(true);
    setError(null);
    try {
      const started = await api<{ pageUrl: string }>(`/public/checkout/${id}/iyzico`, { method: 'POST' });
      window.location.assign(started.pageUrl);
    } catch (e) {
      setError(e instanceof ApiError && e.status === 409 ? 'Ödemeniz zaten işleniyor. Lütfen bekleyin.'
        : e instanceof ApiError && e.status === 502 ? 'iyzico\'ya şu an ulaşılamıyor. Kartınızdan para çekilmedi; biraz sonra yeniden deneyin.'
          : (e as Error).message);
      fetched.reload();
      setBusy(false);
    }
  };

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
      const updated = await api<CheckoutSession>(`/public/checkout/${id}/pay`, { method: 'POST', body: { cardToken: token, installments } });
      if (updated.status === 'AUTHENTICATING' && updated.challengeUrl) {
        navigate(updated.challengeUrl);
        return;
      }
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

  if (session.status === 'AUTHENTICATING' && session.challengeUrl?.startsWith('http')) {
    return (
      <Frame session={session}>
        <Outcome tone="warning" title="iyzico ödeme sayfasında işlem bekleniyor">
          Ödemeyi iyzico'nun sayfasında tamamlayın. Sayfayı kapattıysanız aşağıdan geri dönebilirsiniz.
        </Outcome>
        {iyzicoTrouble && (
          <p className="note note-warning" role="status">
            iyzico'dan sonuç alınamadı. Kartınızdan çekim yapıldıysa ödemeniz birkaç dakika içinde burada görünür; tekrar ödemeden önce bekleyin.
          </p>
        )}
        <a className="button button-primary button-block" href={session.challengeUrl}>iyzico sayfasına dön</a>
      </Frame>
    );
  }
  if (session.status === 'AUTHENTICATING' && session.challengeUrl) {
    return (
      <Frame session={session}>
        <Outcome tone="warning" title="Bankanızın onayı bekleniyor">
          Ödemeyi tamamlamak için kartınızın bankasındaki doğrulamayı bitirin. Kartınızdan henüz para çekilmedi.
        </Outcome>
        <Link className="button button-primary button-block" to={session.challengeUrl}>Doğrulama sayfasına git</Link>
      </Frame>
    );
  }
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
      {iyzicoTrouble && (
        <p className="note note-warning" role="status">iyzico'dan sonuç alınamadı. Kartınızdan çekim yapıldıysa iade edilir; yeniden deneyebilirsiniz.</p>
      )}
      {session.iyzicoAvailable && (
        <section className="iyzico-option" aria-labelledby="iyzico-title">
          <div>
            <strong id="iyzico-title">iyzico ile öde</strong>
            <span className="badge badge-info">Gerçek test ortamı</span>
          </div>
          <p className="hint">
            Kart bilgilerinizi iyzico'nun güvenli sayfasında girersiniz; bu siteye hiç gelmez. Test kartı: <code>5528 7900 0000 0008</code>,
            son kullanma tarihi ileri bir tarih, güvenlik kodu herhangi üç hane.
          </p>
          <button type="button" className="button button-primary button-block" onClick={payWithIyzico} disabled={busy}>
            {busy ? 'Yönlendiriliyor…' : 'iyzico ile öde'}
          </button>
          <p className="divider-label">veya simüle bankayla deneyin</p>
        </section>
      )}
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
        {installmentOptions(session.amount).length > 1 && (
          <fieldset className="card-options">
            <legend>Taksit seçenekleri</legend>
            <p className="hint">Toplam tutar değişmez; taksit farkını işletme karşılar.</p>
            <div className="installments">
              {installmentOptions(session.amount).map((option) => (
                <label key={option.count} className={installments === option.count ? 'installment selected' : 'installment'}>
                  <input type="radio" name="installments" checked={installments === option.count} onChange={() => setInstallments(option.count)} />
                  <span>
                    <strong>{option.count === 1 ? 'Tek çekim' : `${option.count} taksit`}</strong>
                    <span className="hint">{option.count === 1 ? formatMoney(session.amount, session.currency) : `${option.count} × ${formatMoney(option.monthly, session.currency)}`}</span>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>
        )}
        <button type="submit" className="button button-primary button-block" disabled={busy}>
          {busy ? 'İşleniyor…' : `${formatMoney(session.amount, session.currency)} öde`}
        </button>
      </form>
    </Frame>
  );
}
