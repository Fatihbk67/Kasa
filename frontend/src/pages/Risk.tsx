import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import { EmptyState, ErrorNote, FormDialog, formatDateTime, Loading, PageHeader, riskReason } from '../components';
import { t, tk } from '../i18n';
import { canOperate, useMe } from '../me';
import { formatMoney, parseAmount } from '../money';
import { useToast } from '../toast';
import type { BlockedCard, Payment, RiskRule, RiskRuleKind } from '../types';
import { useFetch } from '../useFetch';

const KIND_LABELS: Record<RiskRuleKind, string> = {
  CARD_VELOCITY: tk('Kart deneme sıklığı'),
  CARD_DECLINES: tk('Art arda reddedilen kart'),
  AMOUNT_ABOVE: tk('Yüksek tutar'),
};

function windowLabel(minutes: number): string {
  if (minutes % 1440 === 0) {
    return t('{count} gün', { count: minutes / 1440 });
  }
  if (minutes % 60 === 0) {
    return t('{count} saat', { count: minutes / 60 });
  }
  return t('{count} dakika', { count: minutes });
}

/** The rule as a sentence, because "CARD_VELOCITY 5/10" means nothing to the person who has to trust it. */
export function describeRule(rule: Pick<RiskRule, 'kind' | 'threshold' | 'windowMinutes' | 'currency' | 'action'>): string {
  const outcome = rule.action === 'BLOCK' ? t('engelle') : t('incelemeye al');
  switch (rule.kind) {
    case 'CARD_VELOCITY':
      return t('Aynı kart {window} içinde {count} kereden fazla denenirse {outcome}', { window: windowLabel(rule.windowMinutes ?? 0), count: rule.threshold, outcome });
    case 'CARD_DECLINES':
      return t('Kart {window} içinde bankadan {count} kez reddedildiyse {outcome}', { window: windowLabel(rule.windowMinutes ?? 0), count: rule.threshold, outcome });
    case 'AMOUNT_ABOVE':
      return t('Tutar {amount} üstündeyse {outcome}', { amount: formatMoney(rule.threshold, rule.currency ?? 'TRY'), outcome });
  }
}

function ReviewQueue({ onChanged }: { onChanged: () => void }) {
  const queue = useFetch(() => api<Payment[]>('/risk/reviews'), []);
  const [rejecting, setRejecting] = useState<Payment | null>(null);
  const [blockCard, setBlockCard] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const notify = useToast();

  const approve = async (payment: Payment) => {
    setBusy(payment.id);
    setError(null);
    try {
      const result = await api<Payment>(`/risk/reviews/${payment.id}/approve`, { method: 'POST', body: { capture: true } });
      notify(result.status === 'CAPTURED' ? t('Ödeme onaylandı ve tahsil edildi.') : t('Ödeme onaylandı.'));
      queue.reload();
      onChanged();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  const reject = async () => {
    await api(`/risk/reviews/${rejecting!.id}/reject`, { method: 'POST', body: { blockCard } });
    notify(blockCard ? t('Ödeme reddedildi ve kart engellendi.') : t('Ödeme reddedildi, provizyon iptal edildi.'));
    setRejecting(null);
    queue.reload();
    onChanged();
  };

  return (
    <section>
      <div className="section-header">
        <h2>{t('İnceleme bekleyen ödemeler')}</h2>
      </div>
      <ErrorNote message={queue.error ?? error} />
      {queue.loading && !queue.data ? <Loading /> : queue.data && queue.data.length > 0 ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">{t('Ödeme')}</th>
                <th scope="col">{t('Neden')}</th>
                <th scope="col" className="numeric">{t('Tutar')}</th>
                <th scope="col"><span className="visually-hidden">{t('Karar')}</span></th>
              </tr>
            </thead>
            <tbody>
              {queue.data.map((payment) => (
                <tr key={payment.id}>
                  <td>
                    <Link to={`/payments/${payment.id}`}>{payment.description || t('Açıklamasız ödeme')}</Link>
                    <span className="hint row-hint">{formatDateTime(payment.createdAt)}</span>
                  </td>
                  <td>{payment.riskReasons.map(riskReason).join(', ')}</td>
                  <td className="numeric">{formatMoney(payment.amount, payment.currency)}</td>
                  <td className="numeric">
                    <span className="row-actions">
                      <button type="button" className="button button-small button-primary" disabled={busy === payment.id} onClick={() => approve(payment)}>
                        {busy === payment.id ? t('İşleniyor…') : t('Onayla ve tahsil et')}
                      </button>
                      <button type="button" className="button button-small" onClick={() => { setBlockCard(true); setRejecting(payment); }}>{t('Reddet')}</button>
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : !queue.error && (
        <div className="card"><EmptyState title={t('İnceleme bekleyen ödeme yok')}>{t('Bir kural “incelemeye al” dediğinde ödeme burada bekler; onaylanana kadar tahsil edilmez.')}</EmptyState></div>
      )}
      {rejecting && (
        <FormDialog title={t('Ödemeyi reddet')} submitLabel={t('Reddet')} danger onClose={() => setRejecting(null)} onSubmit={reject}>
          <p>{t('{amount} tutarındaki provizyon iptal edilir ve müşterinin kart limiti hemen serbest kalır.', { amount: formatMoney(rejecting.amount, rejecting.currency) })}</p>
          <label className="field field-inline">
            <input type="checkbox" checked={blockCard} onChange={(e) => setBlockCard(e.target.checked)} />
            {t('Bu kartı engelle; bir daha ödeme alınmasın')}
          </label>
        </FormDialog>
      )}
    </section>
  );
}

interface RuleForm {
  kind: RiskRuleKind;
  threshold: string;
  windowMinutes: string;
  action: 'REVIEW' | 'BLOCK';
}

function RuleDialog({ rule, onClose, onSaved }: { rule: RiskRule | null; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState<RuleForm>(rule
    ? { kind: rule.kind, threshold: rule.kind === 'AMOUNT_ABOVE' ? String(rule.threshold / 100).replace('.', ',') : String(rule.threshold),
      windowMinutes: String(rule.windowMinutes ?? 10), action: rule.action }
    : { kind: 'CARD_VELOCITY', threshold: '5', windowMinutes: '10', action: 'BLOCK' });
  const [formError, setFormError] = useState<string | null>(null);
  const amountRule = form.kind === 'AMOUNT_ABOVE';

  const threshold = amountRule ? parseAmount(form.threshold) : Number.parseInt(form.threshold, 10);
  const windowMinutes = Number.parseInt(form.windowMinutes, 10);
  const valid = threshold !== null && Number.isFinite(threshold) && threshold > 0 && (amountRule || (windowMinutes >= 1 && windowMinutes <= 10_080));

  const submit = async () => {
    if (!valid) {
      setFormError(amountRule ? t('Geçerli bir tutar girin.') : t('Sayı ve süre pozitif olmalı; süre en fazla 7 gün olabilir.'));
      return;
    }
    const body = {
      kind: form.kind, threshold, action: form.action, enabled: rule?.enabled ?? true,
      windowMinutes: amountRule ? null : windowMinutes, currency: amountRule ? 'TRY' : null,
    };
    await api(rule ? `/risk/rules/${rule.id}` : '/risk/rules', { method: rule ? 'PUT' : 'POST', body });
    onSaved();
  };

  return (
    <FormDialog title={rule ? t('Kuralı düzenle') : t('Yeni kural')} submitLabel={t('Kaydet')} onClose={onClose} onSubmit={submit}>
      <label className="field">
        {t('Kural türü')}
        <select value={form.kind} disabled={rule !== null} onChange={(e) => {
          const kind = e.target.value as RiskRuleKind;
          setForm({ ...form, kind, threshold: kind === 'AMOUNT_ABOVE' ? '10000' : kind === 'CARD_DECLINES' ? '3' : '5',
            action: kind === 'AMOUNT_ABOVE' ? 'REVIEW' : 'BLOCK' });
        }}>
          {(Object.keys(KIND_LABELS) as RiskRuleKind[]).map((kind) => <option key={kind} value={kind}>{t(KIND_LABELS[kind])}</option>)}
        </select>
      </label>
      <div className="form-row">
        <label className="field">
          {amountRule ? t('Tutar sınırı (₺)') : form.kind === 'CARD_VELOCITY' ? t('İzin verilen deneme') : t('İzin verilen ret')}
          <input required inputMode={amountRule ? 'decimal' : 'numeric'} value={form.threshold} onChange={(e) => setForm({ ...form, threshold: e.target.value })} />
        </label>
        {!amountRule && (
          <label className="field">
            {t('Süre (dakika)')}
            <input required inputMode="numeric" value={form.windowMinutes} onChange={(e) => setForm({ ...form, windowMinutes: e.target.value })} />
          </label>
        )}
      </div>
      <fieldset className="card-options">
        <legend>{t('Kural tutarsa')}</legend>
        <label className="card-option">
          <input type="radio" name="action" checked={form.action === 'BLOCK'} onChange={() => setForm({ ...form, action: 'BLOCK' })} />
          <span><strong>{t('Engelle')}</strong><br /><span className="hint">{t('Ödeme bankaya gönderilmez, müşteri reddedilir.')}</span></span>
        </label>
        <label className="card-option">
          <input type="radio" name="action" checked={form.action === 'REVIEW'} onChange={() => setForm({ ...form, action: 'REVIEW' })} />
          <span><strong>{t('İncelemeye al')}</strong><br /><span className="hint">{t('Provizyon alınır, siz onaylayana kadar tahsil edilmez.')}</span></span>
        </label>
      </fieldset>
      {valid && <p className="note note-success">{describeRule({ kind: form.kind, threshold: threshold!, windowMinutes, currency: 'TRY', action: form.action })}.</p>}
      <ErrorNote message={formError} />
    </FormDialog>
  );
}

function Rules() {
  const rules = useFetch(() => api<RiskRule[]>('/risk/rules'), []);
  const [editing, setEditing] = useState<RiskRule | 'new' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const notify = useToast();

  const run = async (work: () => Promise<unknown>, message: string) => {
    setError(null);
    try {
      await work();
      notify(message);
      rules.reload();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const toggle = (rule: RiskRule) => run(() => api(`/risk/rules/${rule.id}`, { method: 'PUT', body: { ...rule, enabled: !rule.enabled } }),
    rule.enabled ? t('Kural kapatıldı.') : t('Kural açıldı.'));

  return (
    <section>
      <div className="section-header">
        <h2>{t('Kurallar')}</h2>
        <button type="button" className="button button-small" onClick={() => setEditing('new')}>{t('Kural ekle')}</button>
      </div>
      <ErrorNote message={rules.error ?? error} />
      {rules.loading && !rules.data ? <Loading /> : rules.data && rules.data.length > 0 ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">{t('Kural')}</th>
                <th scope="col">{t('Sonuç')}</th>
                <th scope="col"><span className="visually-hidden">{t('İşlemler')}</span></th>
              </tr>
            </thead>
            <tbody>
              {rules.data.map((rule) => (
                <tr key={rule.id} className={rule.enabled ? undefined : 'muted'}>
                  <td>
                    <strong>{t(KIND_LABELS[rule.kind])}</strong>
                    <span className="hint row-hint">{describeRule(rule)}</span>
                  </td>
                  <td>
                    <span className={`badge badge-${!rule.enabled ? 'neutral' : rule.action === 'BLOCK' ? 'danger' : 'warning'}`}>
                      {!rule.enabled ? t('Kapalı') : rule.action === 'BLOCK' ? t('Engeller') : t('İncelemeye alır')}
                    </span>
                  </td>
                  <td className="numeric">
                    <span className="row-actions">
                      <button type="button" className="button button-small" onClick={() => toggle(rule)}>{rule.enabled ? t('Kuralı kapat') : t('Kuralı aç')}</button>
                      <button type="button" className="button button-small" onClick={() => setEditing(rule)}>{t('Düzenle')}</button>
                      <button type="button" className="button button-small" onClick={() => run(() => api(`/risk/rules/${rule.id}`, { method: 'DELETE' }), t('Kural silindi.'))}>{t('Sil')}</button>
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : !rules.error && (
        <div className="card">
          <EmptyState title={t('Henüz kural yok')}>{t('Kural olmadan her ödeme doğrudan bankaya gider. Önerilen kurallarla başlayıp işletmenize göre ayarlayabilirsiniz.')}</EmptyState>
          <div className="form-actions">
            <button type="button" className="button button-primary" onClick={() => run(() => api('/risk/rules/recommended', { method: 'POST' }), t('Önerilen kurallar eklendi.'))}>
              {t('Önerilen kuralları ekle')}
            </button>
          </div>
        </div>
      )}
      {editing && <RuleDialog rule={editing === 'new' ? null : editing} onClose={() => setEditing(null)}
        onSaved={() => { setEditing(null); notify(t('Kural kaydedildi.')); rules.reload(); }} />}
    </section>
  );
}

function Blocklist({ version }: { version: number }) {
  const cards = useFetch(() => api<BlockedCard[]>('/risk/blocklist'), [version]);
  const [error, setError] = useState<string | null>(null);
  const notify = useToast();

  const unblock = async (card: BlockedCard) => {
    setError(null);
    try {
      await api(`/risk/blocklist/${card.fingerprint}`, { method: 'DELETE' });
      notify(t('Kartın engeli kaldırıldı.'));
      cards.reload();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <section>
      <div className="section-header">
        <h2>{t('Engelli kartlar')}</h2>
      </div>
      <ErrorNote message={cards.error ?? error} />
      {cards.loading && !cards.data ? <Loading /> : cards.data && cards.data.length > 0 ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">{t('Kart')}</th>
                <th scope="col">{t('Neden')}</th>
                <th scope="col" className="hide-narrow">{t('Tarih')}</th>
                <th scope="col"><span className="visually-hidden">{t('İşlemler')}</span></th>
              </tr>
            </thead>
            <tbody>
              {cards.data.map((card) => (
                <tr key={card.fingerprint}>
                  <td>
                    <code>…{card.fingerprint.slice(-8)}</code>
                    {card.sourcePayment && <span className="hint row-hint"><Link to={`/payments/${card.sourcePayment}`}>{t('Engellendiği ödeme')}</Link></span>}
                  </td>
                  <td>{card.reason}</td>
                  <td className="hide-narrow muted">{formatDateTime(card.createdAt)}</td>
                  <td className="numeric"><button type="button" className="button button-small" onClick={() => unblock(card)}>{t('Engeli kaldır')}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : !cards.error && (
        <div className="card"><EmptyState title={t('Engelli kart yok')}>{t('Bir ödemenin sayfasından veya incelemede reddederken kartı engelleyebilirsiniz.')}</EmptyState></div>
      )}
    </section>
  );
}

export function RiskPage() {
  const { me } = useMe();
  const [version, setVersion] = useState(0);

  if (!canOperate(me)) {
    return (
      <>
        <PageHeader title={t('Risk ve dolandırıcılık')} />
        <p className="note note-warning">{t('Bu sayfayı yalnızca sahip ve yöneticiler görebilir.')}</p>
      </>
    );
  }

  return (
    <>
      <PageHeader title={t('Risk ve dolandırıcılık')}
        description={t('Her ödeme bankaya gitmeden önce bu kurallardan geçer. Şüpheli olanlar engellenir ya da sizin onayınızı bekler.')} />
      <div className="sections">
        <ReviewQueue onChanged={() => setVersion((v) => v + 1)} />
        <Rules />
        <Blocklist version={version} />
      </div>
    </>
  );
}
