import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import { ErrorNote, Loading } from '../components';
import { t } from '../i18n';
import { formatMoney } from '../money';
import type { BankAnswer, BankChallenge as Challenge, SignedBankResult } from '../types';
import { useFetch } from '../useFetch';

/** "/api/v1/public/checkout/{id}/3ds-callback" is where the bank posts back; the customer lands on that session's page. */
export function sessionOf(returnUrl: string): string | null {
  return /^\/api\/v1\/public\/checkout\/([0-9a-f-]{36})\/3ds-callback$/.exec(returnUrl)?.[1] ?? null;
}

export function secondsLeft(expiresAt: string, now: number = Date.now()): number {
  return Math.max(0, Math.floor((new Date(expiresAt).getTime() - now) / 1000));
}

/**
 * The card bank's own verification page, simulated. It deliberately looks unlike Kasa: in real life the customer
 * is on their bank's site here, and Kasa sees nothing they type.
 */
export function BankChallenge() {
  const { id } = useParams();
  const navigate = useNavigate();
  const challenge = useFetch(() => api<Challenge>(`/public/bank/3ds/${id}`), [id]);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [attemptsLeft, setAttemptsLeft] = useState<number | null>(null);
  const [now, setNow] = useState(Date.now());

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  /** Hands the signed verdict to the shop, as a bank's page posts its form back to the merchant. */
  const returnToShop = async (result: SignedBankResult, returnUrl: string) => {
    const session = sessionOf(returnUrl);
    if (!session) {
      setError(t('Mağazaya dönüş adresi tanınmadı.'));
      setBusy(false);
      return;
    }
    try {
      await fetch(returnUrl, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(result) });
    } finally {
      navigate(`/pay/${session}`, { replace: true });
    }
  };

  const send = async (cancel: boolean) => {
    setBusy(true);
    setError(null);
    try {
      const answer = await api<BankAnswer>(`/public/bank/3ds/${id}`, { method: 'POST', body: cancel ? { cancel: true } : { code } });
      if (answer.status === 'PENDING') {
        setAttemptsLeft(answer.attemptsLeft);
        setError(t('Kod hatalı. {count} deneme hakkınız kaldı.', { count: answer.attemptsLeft ?? 0 }));
        setCode('');
        setBusy(false);
        return;
      }
      await returnToShop(answer.result!, answer.returnUrl!);
    } catch (e) {
      setError(e instanceof ApiError && e.status === 409 ? t('Bu doğrulama zaten tamamlandı.') : (e as Error).message);
      setBusy(false);
    }
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    void send(false);
  };

  if (challenge.loading && !challenge.data) {
    return <div className="bank-page"><Loading /></div>;
  }
  const data = challenge.data;
  if (!data) {
    return <div className="bank-page"><div className="bank-card"><ErrorNote message={t('Doğrulama bulunamadı.')} /></div></div>;
  }
  const remaining = secondsLeft(data.expiresAt, now);
  const finished = data.status !== 'PENDING' || remaining === 0;
  const time = `${Math.floor(remaining / 60)}:${String(remaining % 60).padStart(2, '0')}`;

  return (
    <div className="bank-page">
      <div className="bank-card">
        <header className="bank-header">
          <span className="bank-logo" aria-hidden="true">◆</span>
          <div>
            <strong>{t('Kasa Test Bankası')}</strong>
            <span className="bank-sub">{t('3D Secure güvenli ödeme doğrulaması')}</span>
          </div>
        </header>
        <dl className="bank-details">
          <div><dt>{t('İşyeri')}</dt><dd>{data.merchantName}</dd></div>
          <div><dt>{t('Tutar')}</dt><dd>{formatMoney(data.amount, data.currency)}</dd></div>
          <div><dt>{t('Kart')}</dt><dd>{data.cardHint}</dd></div>
        </dl>
        {finished ? (
          <p className="note note-warning">{t('Bu doğrulamanın süresi doldu veya tamamlandı. Mağazaya dönüp yeniden deneyebilirsiniz.')}</p>
        ) : (
          <form className="stack" onSubmit={submit}>
            <p>{t('Telefonunuza gönderilen 6 haneli doğrulama kodunu girin.')}</p>
            <div className="bank-sms" role="note">
              <span className="hint">{t('Test modu · SMS')}</span>
              <span>
                {t('Kasa Test Bankası: {amount} tutarlı işleminiz için doğrulama kodunuz', { amount: formatMoney(data.amount, data.currency) })} <strong>{data.testCode}</strong>
              </span>
            </div>
            <label className="field">
              {t('Doğrulama kodu')}
              <input autoFocus required inputMode="numeric" autoComplete="one-time-code" pattern="\d{6}" maxLength={6}
                value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} />
              <span className="hint">
                {t('Kalan süre {time} · {count} deneme hakkı', { time, count: attemptsLeft ?? data.attemptsLeft })}
              </span>
            </label>
            <ErrorNote message={error} />
            <div className="form-actions">
              <button type="button" className="button" disabled={busy} onClick={() => void send(true)}>{t('İşlemi iptal et')}</button>
              <button type="submit" className="button button-primary" disabled={busy || code.length !== 6}>{busy ? t('Doğrulanıyor…') : t('Onayla')}</button>
            </div>
          </form>
        )}
      </div>
      <p className="checkout-footer muted">{t('Bu sayfa kart bankanızı temsil eder · Test modu')}</p>
    </div>
  );
}
