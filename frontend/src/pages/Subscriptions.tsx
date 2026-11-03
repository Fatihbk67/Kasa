import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import { EmptyState, ErrorNote, failureMessage, FormDialog, formatDateTime, InvoiceStatusBadge, Loading, PageHeader, SubscriptionStatusBadge } from '../components';
import { canOperate, canTakePayments, useMe } from '../me';
import { formatMoney, parseAmount } from '../money';
import type { BillingSummary, Customer, CustomerPage, Plan, Subscription, SubscriptionDetail, SubscriptionPage, SubscriptionStatus } from '../types';
import { useToast } from '../toast';
import { useFetch, usePolling } from '../useFetch';

const STATUS_OPTIONS: { value: SubscriptionStatus; label: string }[] = [
  { value: 'ACTIVE', label: 'Aktif' },
  { value: 'PAST_DUE', label: 'Ödeme gecikti' },
  { value: 'INCOMPLETE', label: 'İlk ödeme bekleniyor' },
  { value: 'CANCELED', label: 'İptal edildi' },
];

// Test scenarios stand in for a card form on purpose: this project never collects real card details.
const TEST_CARDS = [
  { token: 'tok_success', label: 'Başarılı kart' },
  { token: 'tok_declined', label: 'Reddedilen kart' },
  { token: 'tok_insufficient_funds', label: 'Bakiyesi yetersiz kart' },
  { token: 'tok_timeout_processed', label: 'Banka yanıt vermiyor' },
];

const INTERVAL_LABELS: Record<Plan['billingInterval'], string> = { MONTH: 'aylık', YEAR: 'yıllık' };

const INVOICE_KINDS: Record<string, string> = { FIRST: 'İlk ödeme', RENEWAL: 'Yenileme', PLAN_CHANGE: 'Plan değişikliği' };

const LINE_KINDS: Record<string, string> = {
  PLAN: 'Dönem ücreti',
  UNUSED_TIME: 'Kullanılmayan süre',
  REMAINING_TIME: 'Kalan süre',
};

const CANCEL_REASONS: Record<string, string> = {
  REQUESTED: 'İşletmenin isteğiyle iptal edildi.',
  FIRST_PAYMENT_FAILED: 'İlk ödeme alınamadığı için başlamadı.',
  PAYMENT_FAILED: 'Tekrarlanan denemelere rağmen ödeme alınamadı.',
};

function CardSelect({ value, onChange }: { value: string; onChange: (token: string) => void }) {
  return (
    <label className="field">
      Test kartı
      <select value={value} onChange={(e) => onChange(e.target.value)}>
        {TEST_CARDS.map((card) => <option key={card.token} value={card.token}>{card.label}</option>)}
      </select>
      <span className="hint">Gerçek kart bilgisi istenmez.</span>
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
      setAmountError('Geçerli bir tutar girin, örneğin 149,90.');
      return;
    }
    setAmountError(null);
    await api('/plans', { method: 'POST', body: { name, amount: minorUnits, currency: 'TRY', billingInterval: interval } });
    onDone();
  };

  return (
    <FormDialog title="Yeni plan" submitLabel="Oluştur" onClose={onClose} onSubmit={submit}>
      <label className="field">
        Ad
        <input required autoFocus maxLength={200} placeholder="Aylık kahve kutusu" value={name} onChange={(e) => setName(e.target.value)} />
      </label>
      <label className="field">
        Tutar (₺)
        <input inputMode="decimal" required placeholder="249,90" value={amount} onChange={(e) => setAmount(e.target.value)} />
        <span className="hint">Fiyat sonradan değişmez. Yeni fiyat için yeni plan oluşturulur.</span>
      </label>
      <label className="field">
        Dönem
        <select value={interval} onChange={(e) => setInterval(e.target.value as Plan['billingInterval'])}>
          <option value="MONTH">Aylık</option>
          <option value="YEAR">Yıllık</option>
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
      throw e instanceof ApiError && e.status === 409 ? new Error('Bu müşteri bu plana zaten abone.') : e;
    }
  };

  return (
    <FormDialog title="Yeni abonelik" submitLabel="Aboneliği başlat" onClose={onClose} onSubmit={submit}>
      <label className="field">
        Müşteri
        <select required value={customerId || usable[0]?.id || ''} onChange={(e) => setCustomerId(e.target.value)}>
          {usable.map((customer) => <option key={customer.id} value={customer.id}>{customer.name ?? customer.email}</option>)}
        </select>
        {customers.data && usable.length === 0 && <span className="hint">Önce <Link to="/customers">bir müşteri ekleyin</Link>.</span>}
      </label>
      <label className="field">
        Plan
        <select required value={planId} onChange={(e) => setPlanId(e.target.value)}>
          {plans.map((plan) => <option key={plan.id} value={plan.id}>{plan.name} · {formatMoney(plan.amount, plan.currency)} {INTERVAL_LABELS[plan.billingInterval]}</option>)}
        </select>
      </label>
      <CardSelect value={token} onChange={setToken} />
      <p className="muted">İlk dönem hemen tahsil edilir. Kart reddedilirse abonelik başlamaz.</p>
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
        title="Abonelikler"
        description="Tekrarlayan ödemeler. Başarısız tahsilatlar 1, 3 ve 5 gün sonra yeniden denenir."
        actions={operator && (
          <>
            {canTakePayments(me) && <button type="button" className="button button-primary" disabled={activePlans.length === 0} onClick={() => setDialog('subscription')}>Yeni abonelik</button>}
            <button type="button" className="button" onClick={() => setDialog('plan')}>Yeni plan</button>
          </>
        )}
      />
      <ErrorNote message={summary.error} />
      {summary.data && (
        <section className="stats stats-wide" aria-label="Abonelik özeti">
          {(summary.data.revenue.length > 0 ? summary.data.revenue : [{ currency: 'TRY', monthlyRecurring: 0 }]).map((row) => (
            <div className="card stat" key={row.currency}>
              <span className="stat-label">Aylık tekrarlayan gelir</span>
              <span className="stat-value stat-value-small">{formatMoney(row.monthlyRecurring, row.currency)}</span>
              <span className="hint">Yıllık planların on ikide biri dahil</span>
            </div>
          ))}
          <div className="card stat">
            <span className="stat-label">Aktif abonelik</span>
            <span className="stat-value stat-value-small">{summary.data.active}</span>
          </div>
          <div className="card stat">
            <span className="stat-label">Ödemesi geciken</span>
            <span className="stat-value stat-value-small">{summary.data.pastDue}</span>
          </div>
          <div className="card stat">
            <span className="stat-label">Son 30 günde biten</span>
            <span className="stat-value stat-value-small">{summary.data.canceledLast30Days}</span>
          </div>
        </section>
      )}

      <div className="toolbar">
        <label className="field field-inline">
          Durum
          <select value={status} onChange={(e) => setParams(e.target.value ? { status: e.target.value } : {})}>
            <option value="">Tümü</option>
            {STATUS_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
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
                  <th scope="col">Müşteri</th>
                  <th scope="col">Durum</th>
                  <th scope="col" className="numeric">Tutar</th>
                  <th scope="col" className="hide-narrow">Sonraki yenileme</th>
                </tr>
              </thead>
              <tbody>
                {items.map((subscription) => (
                  <tr key={subscription.id} className="row-link" onClick={() => navigate(`/subscriptions/${subscription.id}`)}>
                    <td>
                      <a href={`/subscriptions/${subscription.id}`} onClick={(e) => e.preventDefault()}>{subscription.customerLabel ?? 'Silinmiş müşteri'}</a>
                      <span className="hint row-hint">{subscription.planName}</span>
                    </td>
                    <td><SubscriptionStatusBadge status={subscription.status} /></td>
                    <td className="numeric">{formatMoney(subscription.amount, subscription.currency)}</td>
                    <td className="hide-narrow muted">
                      {subscription.status === 'CANCELED' ? '—' : subscription.cancelAtPeriodEnd ? 'Dönem sonunda bitecek' : formatDateTime(subscription.currentPeriodEnd)}
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
                {loadingMore ? 'Yükleniyor…' : 'Daha fazla göster'}
              </button>
            </div>
          )}
        </>
      ) : !firstPage.error && (
        <div className="card">
          <EmptyState title={status ? 'Bu durumda abonelik yok' : 'Henüz abonelik yok'}>
            {!status && 'Önce bir plan oluşturun, sonra bir müşteriyi plana abone edin.'}
          </EmptyState>
        </div>
      )}

      <div className="section-header"><h2>Planlar</h2></div>
      <ErrorNote message={plans.error} />
      {plans.loading ? <Loading /> : plans.data && plans.data.length > 0 ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Plan</th>
                <th scope="col" className="numeric">Tutar</th>
                <th scope="col" className="numeric">Abone</th>
                <th scope="col"><span className="visually-hidden">İşlemler</span></th>
              </tr>
            </thead>
            <tbody>
              {plans.data.map((plan) => (
                <tr key={plan.id}>
                  <td>
                    {plan.name}
                    {plan.archived && <span className="hint row-hint">Yeni aboneliğe kapalı</span>}
                  </td>
                  <td className="numeric">{formatMoney(plan.amount, plan.currency)} {INTERVAL_LABELS[plan.billingInterval]}</td>
                  <td className="numeric">{plan.activeSubscriptions}</td>
                  <td className="numeric">
                    {operator && !plan.archived && <button type="button" className="button button-small" onClick={() => setArchiving(plan)}>Kapat</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : !plans.error && <div className="card"><EmptyState title="Henüz plan yok" /></div>}

      {dialog === 'plan' && <NewPlanDialog onClose={() => setDialog(null)} onDone={() => {
        setDialog(null);
        plans.reload();
        notify('Plan oluşturuldu.');
      }} />}
      {dialog === 'subscription' && (
        <NewSubscriptionDialog plans={activePlans} onClose={() => setDialog(null)} onDone={(detail) => navigate(`/subscriptions/${detail.subscription.id}`)} />
      )}
      {archiving && (
        <FormDialog title="Planı kapat" submitLabel="Kapat" danger onClose={() => setArchiving(null)} onSubmit={archive}>
          <p><strong>{archiving.name}</strong> yeni aboneliğe kapanır. Mevcut aboneler aynı fiyatla devam eder. Bu işlem geri alınamaz.</p>
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
    <FormDialog title="Planı değiştir" submitLabel="Değiştir" onClose={onClose} onSubmit={submit}>
      {options.length === 0 ? <p className="muted">Aynı dönem ve para biriminde başka plan yok.</p> : (
        <label className="field">
          Yeni plan
          <select value={planId} onChange={(e) => setPlanId(e.target.value)}>
            {options.map((plan) => <option key={plan.id} value={plan.id}>{plan.name} · {formatMoney(plan.amount, plan.currency)}</option>)}
          </select>
        </label>
      )}
      <p className="muted">Fark, dönemde kalan süreye göre hesaplanır. Daha pahalı plana geçişte hemen tahsil edilir, daha ucuz plana geçişte sonraki faturadan düşülür.</p>
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
    <FormDialog title="Kartı güncelle" submitLabel="Kaydet" onClose={onClose} onSubmit={submit}>
      <CardSelect value={token} onChange={setToken} />
      {subscription.status === 'PAST_DUE' && <p className="muted">Ödenmemiş fatura yeni kartla hemen yeniden denenir.</p>}
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
      throw e instanceof ApiError && e.status === 409 ? new Error('Sonucu bilinmeyen bir tahsilat var. Banka yanıtlayınca yeniden deneyin.') : e;
    }
    onDone();
  };

  return (
    <FormDialog title="Aboneliği iptal et" submitLabel="İptal et" danger onClose={onClose} onSubmit={submit}>
      <label className="field">
        Ne zaman?
        <select value={atPeriodEnd ? 'end' : 'now'} onChange={(e) => setAtPeriodEnd(e.target.value === 'end')}>
          {canWait && <option value="end">Dönem sonunda ({formatDateTime(subscription.currentPeriodEnd)})</option>}
          <option value="now">Hemen</option>
        </select>
      </label>
      <p className="muted">{atPeriodEnd ? 'Müşteri ödediği dönemin sonuna kadar devam eder.' : 'Hemen biter, ödenmemiş fatura iptal olur, ödenmiş tutar iade edilmez.'}</p>
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
        <ErrorNote message={detail.errorStatus === 404 ? 'Bu abonelik bulunamadı.' : detail.error} />
        <Link to="/subscriptions">Aboneliklere dön</Link>
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
      <p className="breadcrumb"><Link to="/subscriptions">Abonelikler</Link> / Ayrıntı</p>
      <PageHeader
        title={subscription.planName}
        description={`${formatMoney(subscription.amount, subscription.currency)} ${INTERVAL_LABELS[subscription.billingInterval]}`}
        actions={operator && (
          <>
            {active && <button type="button" className="button" onClick={() => setDialog('plan')}>Planı değiştir</button>}
            <button type="button" className="button" onClick={() => setDialog('card')}>Kartı güncelle</button>
            {active && !subscription.cancelAtPeriodEnd && <button type="button" className="button" disabled={busy} onClick={() => run('simulate-renewal', 'Sonraki dönem faturalandı.')}>Yenilemeyi simüle et</button>}
            {subscription.cancelAtPeriodEnd
              ? <button type="button" className="button" disabled={busy} onClick={() => run('resume', 'İptal geri alındı.')}>İptali geri al</button>
              : <button type="button" className="button" onClick={() => setDialog('cancel')}>İptal et</button>}
          </>
        )}
      />
      <ErrorNote message={actionError} />
      {subscription.status === 'PAST_DUE' && (
        <p className="note note-warning" role="status">
          Son tahsilat başarısız oldu{unpaid?.failureCode ? `: ${failureMessage(unpaid.failureCode)}` : '.'}
          {unpaid?.nextAttemptAt && ` Sonraki deneme ${formatDateTime(unpaid.nextAttemptAt)}.`} Kartı güncellerseniz hemen yeniden denenir.
        </p>
      )}
      {subscription.status === 'INCOMPLETE' && <p className="note note-warning" role="status">İlk ödemenin sonucu bankadan bekleniyor. Kart iki kez çekilmez.</p>}
      {subscription.cancelAtPeriodEnd && live && <p className="note note-warning" role="status">{formatDateTime(subscription.currentPeriodEnd)} tarihinde bitecek ve yeni fatura kesilmeyecek.</p>}
      {subscription.status === 'CANCELED' && subscription.cancelReason && <p className="note note-danger" role="status">{CANCEL_REASONS[subscription.cancelReason]}</p>}

      <section className="card">
        <dl className="details">
          <div><dt>Durum</dt><dd><SubscriptionStatusBadge status={subscription.status} /></dd></div>
          <div><dt>Müşteri</dt><dd><Link to={`/customers/${subscription.customerId}`}>{subscription.customerLabel ?? 'Silinmiş müşteri'}</Link></dd></div>
          <div><dt>Dönem başı</dt><dd>{formatDateTime(subscription.currentPeriodStart)}</dd></div>
          <div><dt>Dönem sonu</dt><dd>{formatDateTime(subscription.currentPeriodEnd)}</dd></div>
          <div><dt>Kullanılabilir kredi</dt><dd>{formatMoney(subscription.creditBalance, subscription.currency)}</dd></div>
          {subscription.canceledAt && <div><dt>İptal tarihi</dt><dd>{formatDateTime(subscription.canceledAt)}</dd></div>}
        </dl>
      </section>

      <div className="section-header"><h2>Faturalar</h2></div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col">Fatura</th>
              <th scope="col">Durum</th>
              <th scope="col" className="numeric">Ödenecek</th>
              <th scope="col" className="numeric hide-narrow">Deneme</th>
              <th scope="col" className="hide-narrow">Tarih</th>
            </tr>
          </thead>
          <tbody>
            {invoices.map((invoice) => (
              <tr key={invoice.id}>
                <td>
                  {INVOICE_KINDS[invoice.kind]}
                  {invoice.lines.map((line, index) => (
                    <span className="hint row-hint" key={index}>{LINE_KINDS[line.kind]} ({line.planName}): {formatMoney(line.amount, invoice.currency)}</span>
                  ))}
                  {invoice.creditApplied > 0 && <span className="hint row-hint">Kullanılan kredi: {formatMoney(invoice.creditApplied, invoice.currency)}</span>}
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

      {dialog === 'plan' && <ChangePlanDialog subscription={subscription} plans={plans.data ?? []} onClose={() => setDialog(null)} onDone={() => finish('Plan değişti.')} />}
      {dialog === 'card' && <CardDialog subscription={subscription} onClose={() => setDialog(null)} onDone={() => finish('Kart güncellendi.')} />}
      {dialog === 'cancel' && <CancelDialog subscription={subscription} onClose={() => setDialog(null)} onDone={() => finish('İptal kaydedildi.')} />}
    </>
  );
}
