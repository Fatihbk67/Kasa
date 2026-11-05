import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import { EmptyState, ErrorNote, failureMessage, FormDialog, formatDateTime, InvoiceStatusBadge, Loading, PageHeader, SubscriptionStatusBadge } from '../components';
import { t, tk } from '../i18n';
import { canOperate, canTakePayments, useMe } from '../me';
import { formatMoney, parseAmount } from '../money';
import type { BillingSummary, Customer, CustomerPage, Plan, Subscription, SubscriptionDetail, SubscriptionPage, SubscriptionStatus } from '../types';
import { useToast } from '../toast';
import { useFetch, usePolling } from '../useFetch';

// Labels below are Turkish keys, translated where they are shown.
const STATUS_OPTIONS: { value: SubscriptionStatus; label: string }[] = [
  { value: 'ACTIVE', label: tk('Aktif') },
  { value: 'PAST_DUE', label: tk('Ödeme gecikti') },
  { value: 'INCOMPLETE', label: tk('İlk ödeme bekleniyor') },
  { value: 'CANCELED', label: tk('İptal edildi') },
];

// Test scenarios stand in for a card form on purpose: this project never collects real card details.
const TEST_CARDS = [
  { token: 'tok_success', label: tk('Başarılı kart') },
  { token: 'tok_declined', label: tk('Reddedilen kart') },
  { token: 'tok_insufficient_funds', label: tk('Bakiyesi yetersiz kart') },
  { token: 'tok_timeout_processed', label: tk('Banka yanıt vermiyor') },
];

const INTERVAL_LABELS: Record<Plan['billingInterval'], string> = { MONTH: tk('aylık'), YEAR: tk('yıllık') };

const INVOICE_KINDS: Record<string, string> = { FIRST: tk('İlk ödeme'), RENEWAL: tk('Yenileme'), PLAN_CHANGE: tk('Plan değişikliği') };

const LINE_KINDS: Record<string, string> = {
  PLAN: tk('Dönem ücreti'),
  UNUSED_TIME: tk('Kullanılmayan süre'),
  REMAINING_TIME: tk('Kalan süre'),
};

const CANCEL_REASONS: Record<string, string> = {
  REQUESTED: tk('İşletmenin isteğiyle iptal edildi.'),
  FIRST_PAYMENT_FAILED: tk('İlk ödeme alınamadığı için başlamadı.'),
  PAYMENT_FAILED: tk('Tekrarlanan denemelere rağmen ödeme alınamadı.'),
};

function price(plan: { amount: number; currency: string; billingInterval: Plan['billingInterval'] }): string {
  return `${formatMoney(plan.amount, plan.currency)} ${t(INTERVAL_LABELS[plan.billingInterval])}`;
}

function CardSelect({ value, onChange }: { value: string; onChange: (token: string) => void }) {
  return (
    <label className="field">
      {t('Test kartı')}
      <select value={value} onChange={(e) => onChange(e.target.value)}>
        {TEST_CARDS.map((card) => <option key={card.token} value={card.token}>{t(card.label)}</option>)}
      </select>
      <span className="hint">{t('Gerçek kart bilgisi istenmez.')}</span>
    </label>
  );
}

function NewPlanDialog({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [name, setName] = useState('');
  const [amount, setAmount] = useState('');
  const [interval, setInterval] = useState<Plan['billingInterval']>('MONTH');
  const [amountError, setAmountError] = useState<string | null>(null);

  const submit = async () => {
    const minorUnits = parseAmount(amount);
    if (minorUnits === null) {
      setAmountError(t('Geçerli bir tutar girin, örneğin 149,90.'));
      return;
    }
    setAmountError(null);
    await api('/plans', { method: 'POST', body: { name, amount: minorUnits, currency: 'TRY', billingInterval: interval } });
    onDone();
  };

  return (
    <FormDialog title={t('Yeni plan')} submitLabel={t('Oluştur')} onClose={onClose} onSubmit={submit}>
      <label className="field">
        {t('Ad')}
        <input required autoFocus maxLength={200} placeholder={t('Aylık kahve kutusu')} value={name} onChange={(e) => setName(e.target.value)} />
      </label>
      <label className="field">
        {t('Tutar (₺)')}
        <input inputMode="decimal" required placeholder="249,90" value={amount} onChange={(e) => setAmount(e.target.value)} />
        <span className="hint">{t('Fiyat sonradan değişmez. Yeni fiyat için yeni plan oluşturulur.')}</span>
      </label>
      <label className="field">
        {t('Dönem')}
        <select value={interval} onChange={(e) => setInterval(e.target.value as Plan['billingInterval'])}>
          <option value="MONTH">{t('Aylık')}</option>
          <option value="YEAR">{t('Yıllık')}</option>
        </select>
      </label>
      <ErrorNote message={amountError} />
    </FormDialog>
  );
}

function NewSubscriptionDialog({ plans, onClose, onDone }: { plans: Plan[]; onClose: () => void; onDone: (detail: SubscriptionDetail) => void }) {
  const customers = useFetch(() => api<CustomerPage>('/customers?limit=100'), []);
  const usable: Customer[] = (customers.data?.items ?? []).filter((customer) => !customer.anonymized);
  const [customerId, setCustomerId] = useState('');
  const [planId, setPlanId] = useState(plans[0]?.id ?? '');
  const [token, setToken] = useState(TEST_CARDS[0].token);

  const submit = async () => {
    try {
      onDone(await api<SubscriptionDetail>('/subscriptions', {
        method: 'POST',
        body: { customerId: customerId || usable[0]?.id, planId, cardToken: token },
      }));
    } catch (e) {
      throw e instanceof ApiError && e.status === 409 ? new Error(t('Bu müşteri bu plana zaten abone.')) : e;
    }
  };

  return (
    <FormDialog title={t('Yeni abonelik')} submitLabel={t('Aboneliği başlat')} onClose={onClose} onSubmit={submit}>
      <label className="field">
        {t('Müşteri')}
        <select required value={customerId || usable[0]?.id || ''} onChange={(e) => setCustomerId(e.target.value)}>
          {usable.map((customer) => <option key={customer.id} value={customer.id}>{customer.name ?? customer.email}</option>)}
        </select>
        {customers.data && usable.length === 0 && <span className="hint"><Link to="/customers">{t('Önce bir müşteri ekleyin.')}</Link></span>}
      </label>
      <label className="field">
        {t('Plan')}
        <select required value={planId} onChange={(e) => setPlanId(e.target.value)}>
          {plans.map((plan) => <option key={plan.id} value={plan.id}>{plan.name} · {price(plan)}</option>)}
        </select>
      </label>
      <CardSelect value={token} onChange={setToken} />
      <p className="muted">{t('İlk dönem hemen tahsil edilir. Kart reddedilirse abonelik başlamaz.')}</p>
    </FormDialog>
  );
}

export function Subscriptions() {
  const notify = useToast();
  const { me } = useMe();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const status = params.get('status') ?? '';
  const query = `/subscriptions?limit=30${status ? `&status=${status}` : ''}`;
  const firstPage = useFetch(() => api<SubscriptionPage>(query), [query]);
  const plans = useFetch(() => api<Plan[]>('/plans'), []);
  const summary = useFetch(() => api<BillingSummary>('/billing/summary'), []);
  const [items, setItems] = useState<Subscription[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreError, setMoreError] = useState<string | null>(null);
  const [dialog, setDialog] = useState<'plan' | 'subscription' | null>(null);
  const [archiving, setArchiving] = useState<Plan | null>(null);
  const operator = canOperate(me);
  const activePlans = (plans.data ?? []).filter((plan) => !plan.archived);

  useEffect(() => {
    if (firstPage.data) {
      setItems(firstPage.data.items);
      setCursor(firstPage.data.nextCursor);
    }
  }, [firstPage.data]);

  const loadMore = async () => {
    setLoadingMore(true);
    setMoreError(null);
    try {
      const page = await api<SubscriptionPage>(`${query}&before=${cursor}`);
      setItems((current) => [...current, ...page.items]);
      setCursor(page.nextCursor);
    } catch (e) {
      setMoreError((e as Error).message);
    } finally {
      setLoadingMore(false);
    }
  };

  const archive = async () => {
    await api(`/plans/${archiving!.id}/archive`, { method: 'POST' });
    setArchiving(null);
    plans.reload();
  };

  return (
    <>
      <PageHeader
        title={t('Abonelikler')}
        description={t('Tekrarlayan ödemeler. Başarısız tahsilatlar 1, 3 ve 5 gün sonra yeniden denenir.')}
        actions={operator && (
          <>
            {canTakePayments(me) && <button type="button" className="button button-primary" disabled={activePlans.length === 0} onClick={() => setDialog('subscription')}>{t('Yeni abonelik')}</button>}
            <button type="button" className="button" onClick={() => setDialog('plan')}>{t('Yeni plan')}</button>
          </>
        )}
      />
      <ErrorNote message={summary.error} />
      {summary.data && (
        <section className="stats stats-wide" aria-label={t('Abonelik özeti')}>
          {(summary.data.revenue.length > 0 ? summary.data.revenue : [{ currency: 'TRY', monthlyRecurring: 0 }]).map((row) => (
            <div className="card stat" key={row.currency}>
              <span className="stat-label">{t('Aylık tekrarlayan gelir')}</span>
              <span className="stat-value stat-value-small">{formatMoney(row.monthlyRecurring, row.currency)}</span>
              <span className="hint">{t('Yıllık planların on ikide biri dahil')}</span>
            </div>
          ))}
          <div className="card stat">
            <span className="stat-label">{t('Aktif abonelik')}</span>
            <span className="stat-value stat-value-small">{summary.data.active}</span>
          </div>
          <div className="card stat">
            <span className="stat-label">{t('Ödemesi geciken')}</span>
            <span className="stat-value stat-value-small">{summary.data.pastDue}</span>
          </div>
          <div className="card stat">
            <span className="stat-label">{t('Son 30 günde biten')}</span>
            <span className="stat-value stat-value-small">{summary.data.canceledLast30Days}</span>
          </div>
        </section>
      )}

      <div className="toolbar">
        <label className="field field-inline">
          {t('Durum')}
          <select value={status} onChange={(e) => setParams(e.target.value ? { status: e.target.value } : {})}>
            <option value="">{t('Tümü')}</option>
            {STATUS_OPTIONS.map((option) => <option key={option.value} value={option.value}>{t(option.label)}</option>)}
          </select>
        </label>
      </div>
      <ErrorNote message={firstPage.error} />
      {firstPage.loading ? <Loading /> : items.length > 0 ? (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">{t('Müşteri')}</th>
                  <th scope="col">{t('Durum')}</th>
                  <th scope="col" className="numeric">{t('Tutar')}</th>
                  <th scope="col" className="hide-narrow">{t('Sonraki yenileme')}</th>
                </tr>
              </thead>
              <tbody>
                {items.map((subscription) => (
                  <tr key={subscription.id} className="row-link" onClick={() => navigate(`/subscriptions/${subscription.id}`)}>
                    <td>
                      <a href={`/subscriptions/${subscription.id}`} onClick={(e) => e.preventDefault()}>{subscription.customerLabel ?? t('Silinmiş müşteri')}</a>
                      <span className="hint row-hint">{subscription.planName}</span>
                    </td>
                    <td><SubscriptionStatusBadge status={subscription.status} /></td>
                    <td className="numeric">{formatMoney(subscription.amount, subscription.currency)}</td>
                    <td className="hide-narrow muted">
                      {subscription.status === 'CANCELED' ? '—' : subscription.cancelAtPeriodEnd ? t('Dönem sonunda bitecek') : formatDateTime(subscription.currentPeriodEnd)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ErrorNote message={moreError} />
          {cursor !== null && (
            <div className="load-more">
              <button type="button" className="button" onClick={loadMore} disabled={loadingMore}>
                {loadingMore ? t('Yükleniyor…') : t('Daha fazla göster')}
              </button>
            </div>
          )}
        </>
      ) : !firstPage.error && (
        <div className="card">
          <EmptyState title={status ? t('Bu durumda abonelik yok') : t('Henüz abonelik yok')}>
            {!status && t('Önce bir plan oluşturun, sonra bir müşteriyi plana abone edin.')}
          </EmptyState>
        </div>
      )}

      <div className="section-header"><h2>{t('Planlar')}</h2></div>
      <ErrorNote message={plans.error} />
      {plans.loading ? <Loading /> : plans.data && plans.data.length > 0 ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">{t('Plan')}</th>
                <th scope="col" className="numeric">{t('Tutar')}</th>
                <th scope="col" className="numeric">{t('Abone')}</th>
                <th scope="col"><span className="visually-hidden">{t('İşlemler')}</span></th>
              </tr>
            </thead>
            <tbody>
              {plans.data.map((plan) => (
                <tr key={plan.id}>
                  <td>
                    {plan.name}
                    {plan.archived && <span className="hint row-hint">{t('Yeni aboneliğe kapalı')}</span>}
                  </td>
                  <td className="numeric">{price(plan)}</td>
                  <td className="numeric">{plan.activeSubscriptions}</td>
                  <td className="numeric">
                    {operator && !plan.archived && <button type="button" className="button button-small" onClick={() => setArchiving(plan)}>{t('Planı kapat')}</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : !plans.error && <div className="card"><EmptyState title={t('Henüz plan yok')} /></div>}

      {dialog === 'plan' && <NewPlanDialog onClose={() => setDialog(null)} onDone={() => {
        setDialog(null);
        plans.reload();
        notify(t('Plan oluşturuldu.'));
      }} />}
      {dialog === 'subscription' && (
        <NewSubscriptionDialog plans={activePlans} onClose={() => setDialog(null)} onDone={(detail) => navigate(`/subscriptions/${detail.subscription.id}`)} />
      )}
      {archiving && (
        <FormDialog title={t('Planı kapat')} submitLabel={t('Planı kapat')} danger onClose={() => setArchiving(null)} onSubmit={archive}>
          <p>{t('{plan} yeni aboneliğe kapanır. Mevcut aboneler aynı fiyatla devam eder. Bu işlem geri alınamaz.', { plan: archiving.name })}</p>
        </FormDialog>
      )}
    </>
  );
}

function ChangePlanDialog({ subscription, plans, onClose, onDone }: { subscription: Subscription; plans: Plan[]; onClose: () => void; onDone: () => void }) {
  const options = plans.filter((plan) => !plan.archived && plan.id !== subscription.planId
    && plan.currency === subscription.currency && plan.billingInterval === subscription.billingInterval);
  const [planId, setPlanId] = useState(options[0]?.id ?? '');

  const submit = async () => {
    await api(`/subscriptions/${subscription.id}/change-plan`, { method: 'POST', body: { planId } });
    onDone();
  };

  return (
    <FormDialog title={t('Planı değiştir')} submitLabel={t('Değiştir')} onClose={onClose} onSubmit={submit}>
      {options.length === 0 ? <p className="muted">{t('Aynı dönem ve para biriminde başka plan yok.')}</p> : (
        <label className="field">
          {t('Yeni plan')}
          <select value={planId} onChange={(e) => setPlanId(e.target.value)}>
            {options.map((plan) => <option key={plan.id} value={plan.id}>{plan.name} · {formatMoney(plan.amount, plan.currency)}</option>)}
          </select>
        </label>
      )}
      <p className="muted">{t('Fark, dönemde kalan süreye göre hesaplanır. Daha pahalı plana geçişte hemen tahsil edilir, daha ucuz plana geçişte sonraki faturadan düşülür.')}</p>
    </FormDialog>
  );
}

function CardDialog({ subscription, onClose, onDone }: { subscription: Subscription; onClose: () => void; onDone: () => void }) {
  const [token, setToken] = useState(TEST_CARDS[0].token);
  const submit = async () => {
    await api(`/subscriptions/${subscription.id}/card`, { method: 'PUT', body: { cardToken: token } });
    onDone();
  };
  return (
    <FormDialog title={t('Kartı güncelle')} submitLabel={t('Kaydet')} onClose={onClose} onSubmit={submit}>
      <CardSelect value={token} onChange={setToken} />
      {subscription.status === 'PAST_DUE' && <p className="muted">{t('Ödenmemiş fatura yeni kartla hemen yeniden denenir.')}</p>}
    </FormDialog>
  );
}

function CancelDialog({ subscription, onClose, onDone }: { subscription: Subscription; onClose: () => void; onDone: () => void }) {
  const canWait = subscription.status === 'ACTIVE' && !subscription.cancelAtPeriodEnd;
  const [atPeriodEnd, setAtPeriodEnd] = useState(canWait);

  const submit = async () => {
    try {
      await api(`/subscriptions/${subscription.id}/cancel`, { method: 'POST', body: { atPeriodEnd } });
    } catch (e) {
      throw e instanceof ApiError && e.status === 409 ? new Error(t('Sonucu bilinmeyen bir tahsilat var. Banka yanıtlayınca yeniden deneyin.')) : e;
    }
    onDone();
  };

  return (
    <FormDialog title={t('Aboneliği iptal et')} submitLabel={t('Aboneliği iptal et')} danger onClose={onClose} onSubmit={submit}>
      <label className="field">
        {t('Ne zaman?')}
        <select value={atPeriodEnd ? 'end' : 'now'} onChange={(e) => setAtPeriodEnd(e.target.value === 'end')}>
          {canWait && <option value="end">{t('Dönem sonunda ({date})', { date: formatDateTime(subscription.currentPeriodEnd) })}</option>}
          <option value="now">{t('Hemen')}</option>
        </select>
      </label>
      <p className="muted">{atPeriodEnd ? t('Müşteri ödediği dönemin sonuna kadar devam eder.') : t('Hemen biter, ödenmemiş fatura iptal olur, ödenmiş tutar iade edilmez.')}</p>
    </FormDialog>
  );
}

export function SubscriptionDetailPage() {
  const notify = useToast();
  const { id } = useParams();
  const { me } = useMe();
  const detail = useFetch(() => api<SubscriptionDetail>(`/subscriptions/${id}`), [id]);
  const plans = useFetch(() => api<Plan[]>('/plans'), []);
  const [dialog, setDialog] = useState<'plan' | 'card' | 'cancel' | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  usePolling(detail.data?.subscription.status === 'INCOMPLETE', detail.reload);

  if (detail.loading && !detail.data) {
    return <Loading />;
  }
  if (!detail.data) {
    return (
      <>
        <ErrorNote message={detail.errorStatus === 404 ? t('Bu abonelik bulunamadı.') : detail.error} />
        <Link to="/subscriptions">{t('Aboneliklere dön')}</Link>
      </>
    );
  }

  const { subscription, invoices } = detail.data;
  const live = subscription.status !== 'CANCELED';
  const operator = canOperate(me) && live;
  const active = operator && subscription.status === 'ACTIVE';
  const unpaid = invoices.find((invoice) => invoice.status === 'OPEN');

  const finish = (message: string) => {
    setDialog(null);
    detail.reload();
    notify(message);
  };
  const run = async (path: string, message: string) => {
    setBusy(true);
    setActionError(null);
    try {
      await api(`/subscriptions/${subscription.id}/${path}`, { method: 'POST' });
      detail.reload();
      notify(message);
    } catch (e) {
      setActionError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <p className="breadcrumb"><Link to="/subscriptions">{t('Abonelikler')}</Link> / {t('Ayrıntı')}</p>
      <PageHeader
        title={subscription.planName}
        description={price(subscription)}
        actions={operator && (
          <>
            {active && <button type="button" className="button" onClick={() => setDialog('plan')}>{t('Planı değiştir')}</button>}
            <button type="button" className="button" onClick={() => setDialog('card')}>{t('Kartı güncelle')}</button>
            {active && !subscription.cancelAtPeriodEnd && <button type="button" className="button" disabled={busy} onClick={() => run('simulate-renewal', t('Sonraki dönem faturalandı.'))}>{t('Yenilemeyi simüle et')}</button>}
            {subscription.cancelAtPeriodEnd
              ? <button type="button" className="button" disabled={busy} onClick={() => run('resume', t('İptal geri alındı.'))}>{t('İptali geri al')}</button>
              : <button type="button" className="button" onClick={() => setDialog('cancel')}>{t('Aboneliği iptal et')}</button>}
          </>
        )}
      />
      <ErrorNote message={actionError} />
      {subscription.status === 'PAST_DUE' && (
        <p className="note note-warning" role="status">
          {unpaid?.failureCode ? t('Son tahsilat başarısız oldu: {reason}', { reason: failureMessage(unpaid.failureCode) }) : t('Son tahsilat başarısız oldu.')}
          {unpaid?.nextAttemptAt && ` ${t('Sonraki deneme {date}.', { date: formatDateTime(unpaid.nextAttemptAt) })}`} {t('Kartı güncellerseniz hemen yeniden denenir.')}
        </p>
      )}
      {subscription.status === 'INCOMPLETE' && <p className="note note-warning" role="status">{t('İlk ödemenin sonucu bankadan bekleniyor. Kart iki kez çekilmez.')}</p>}
      {subscription.cancelAtPeriodEnd && live && <p className="note note-warning" role="status">{t('{date} tarihinde bitecek ve yeni fatura kesilmeyecek.', { date: formatDateTime(subscription.currentPeriodEnd) })}</p>}
      {subscription.status === 'CANCELED' && subscription.cancelReason && <p className="note note-danger" role="status">{t(CANCEL_REASONS[subscription.cancelReason])}</p>}

      <section className="card">
        <dl className="details">
          <div><dt>{t('Durum')}</dt><dd><SubscriptionStatusBadge status={subscription.status} /></dd></div>
          <div><dt>{t('Müşteri')}</dt><dd><Link to={`/customers/${subscription.customerId}`}>{subscription.customerLabel ?? t('Silinmiş müşteri')}</Link></dd></div>
          <div><dt>{t('Dönem başı')}</dt><dd>{formatDateTime(subscription.currentPeriodStart)}</dd></div>
          <div><dt>{t('Dönem sonu')}</dt><dd>{formatDateTime(subscription.currentPeriodEnd)}</dd></div>
          <div><dt>{t('Kullanılabilir kredi')}</dt><dd>{formatMoney(subscription.creditBalance, subscription.currency)}</dd></div>
          {subscription.canceledAt && <div><dt>{t('İptal tarihi')}</dt><dd>{formatDateTime(subscription.canceledAt)}</dd></div>}
        </dl>
      </section>

      <div className="section-header"><h2>{t('Faturalar')}</h2></div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col">{t('Fatura')}</th>
              <th scope="col">{t('Durum')}</th>
              <th scope="col" className="numeric">{t('Ödenecek')}</th>
              <th scope="col" className="numeric hide-narrow">{t('Deneme')}</th>
              <th scope="col" className="hide-narrow">{t('Tarih')}</th>
            </tr>
          </thead>
          <tbody>
            {invoices.map((invoice) => (
              <tr key={invoice.id}>
                <td>
                  {t(INVOICE_KINDS[invoice.kind])}
                  {invoice.lines.map((line, index) => (
                    <span className="hint row-hint" key={index}>{t(LINE_KINDS[line.kind])} ({line.planName}): {formatMoney(line.amount, invoice.currency)}</span>
                  ))}
                  {invoice.creditApplied > 0 && <span className="hint row-hint">{t('Kullanılan kredi: {amount}', { amount: formatMoney(invoice.creditApplied, invoice.currency) })}</span>}
                  {invoice.status !== 'PAID' && invoice.failureCode && <span className="hint row-hint">{failureMessage(invoice.failureCode)}</span>}
                </td>
                <td><InvoiceStatusBadge status={invoice.status} /></td>
                <td className="numeric">
                  {invoice.paymentId ? <Link to={`/payments/${invoice.paymentId}`}>{formatMoney(invoice.amount, invoice.currency)}</Link> : formatMoney(invoice.amount, invoice.currency)}
                </td>
                <td className="numeric hide-narrow">{invoice.attemptCount}</td>
                <td className="hide-narrow muted">{formatDateTime(invoice.createdAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {dialog === 'plan' && <ChangePlanDialog subscription={subscription} plans={plans.data ?? []} onClose={() => setDialog(null)} onDone={() => finish(t('Plan değişti.'))} />}
      {dialog === 'card' && <CardDialog subscription={subscription} onClose={() => setDialog(null)} onDone={() => finish(t('Kart güncellendi.'))} />}
      {dialog === 'cancel' && <CancelDialog subscription={subscription} onClose={() => setDialog(null)} onDone={() => finish(t('İptal kaydedildi.'))} />}
    </>
  );
}
