import { useState, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../api';
import { DailyBars, HorizontalBars } from '../charts';
import { EmptyState, ErrorNote, FormDialog, Loading, PageHeader, paymentStatusLabel } from '../components';
import { compareWindows, formatChange, greeting } from '../dashboard';
import { canOperate, canTakePayments, useMe } from '../me';
import { formatMoney, parseAmount } from '../money';
import type { AnalyticsSummary, Balance, DisputePage, Payment, PaymentPage } from '../types';
import { useFetch } from '../useFetch';
import { PaymentTable } from './PaymentTable';

/** How many records a to-do count looks at; more than this reads as "100+". */
const COUNT_LIMIT = 100;

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

/** A change is spelled out with an arrow and words, so it never depends on colour alone. */
function Change({ change, against }: { change: number | null; against: string }) {
  if (change === null) {
    return <span className="hint">{against} ile karşılaştırılacak veri yok</span>;
  }
  const direction = change > 0 ? 'up' : change < 0 ? 'down' : 'flat';
  const arrow = { up: '↑', down: '↓', flat: '→' }[direction];
  return (
    <span className={`kpi-change kpi-change-${direction}`}>
      <span aria-hidden="true">{arrow}</span> {formatChange(change)} <span className="hint">{against} göre</span>
    </span>
  );
}

function Kpi({ label, value, negative, children }: { label: string; value: string; negative?: boolean; children?: ReactNode }) {
  return (
    <div className="card stat kpi">
      <span className="stat-label">{label}</span>
      <span className={negative ? 'stat-value negative' : 'stat-value'}>{value}</span>
      {children}
    </div>
  );
}

interface Task {
  key: string;
  count: number;
  capped: boolean;
  text: string;
  to: string;
  tone: 'danger' | 'warning' | 'info';
}

function TaskList({ tasks, loading }: { tasks: Task[]; loading: boolean }) {
  const open = tasks.filter((task) => task.count > 0);
  return (
    <section className="card" aria-labelledby="tasks-title">
      <h2 className="panel-title" id="tasks-title">Yapılacaklar</h2>
      {loading && open.length === 0 ? <Loading rows={2} /> : open.length === 0 ? (
        <p className="tasks-done"><span aria-hidden="true">✓</span> Bekleyen iş yok. Her şey yolunda.</p>
      ) : (
        <ul className="tasks">
          {open.map((task) => (
            <li key={task.key}>
              <Link to={task.to} className={`task task-${task.tone}`}>
                <span className="task-count">{task.capped ? `${task.count}+` : task.count}</span>
                <span>{task.text}</span>
                <span className="task-arrow" aria-hidden="true">→</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

export function Overview() {
  const { me } = useMe();
  const operator = canOperate(me);
  const balances = useFetch(() => api<Balance[]>('/balances'), []);
  const payments = useFetch(() => api<PaymentPage>('/payments?limit=6'), []);
  const summary = useFetch(() => api<AnalyticsSummary>('/analytics/summary?days=30'), []);
  const disputes = useFetch(() => api<DisputePage>(`/disputes?status=NEEDS_RESPONSE&limit=${COUNT_LIMIT}`), []);
  const authorized = useFetch(() => api<PaymentPage>(`/payments?status=AUTHORIZED&limit=${COUNT_LIMIT}`), []);
  // The review queue belongs to people who can act on it; for anyone else it would only be refused.
  const reviews = useFetch(() => (operator ? api<Payment[]>('/risk/reviews') : Promise.resolve([] as Payment[])), [operator]);
  const [creating, setCreating] = useState(false);

  const balanceList = balances.data && balances.data.length > 0 ? balances.data : [{ currency: 'TRY', available: 0, inTransit: 0 }];
  const data = summary.data;
  const today = data ? compareWindows(data.daily, 1) : null;
  const week = data ? compareWindows(data.daily, 7) : null;
  const approval = data && data.totals.authorizationAttempts > 0
    ? data.totals.authorizationsApproved / data.totals.authorizationAttempts
    : null;
  const hasMoney = data?.daily.some((point) => point.captured > 0) ?? false;

  const counted = (items: unknown[] | undefined) => ({ count: items?.length ?? 0, capped: (items?.length ?? 0) >= COUNT_LIMIT });
  const tasks: Task[] = [
    { key: 'disputes', ...counted(disputes.data?.items), text: 'itiraz yanıtınızı bekliyor', to: '/disputes?status=NEEDS_RESPONSE', tone: 'danger' },
    ...(operator ? [{ key: 'reviews', ...counted(reviews.data ?? undefined), text: 'ödeme risk incelemesinde', to: '/risk', tone: 'warning' as const }] : []),
    { key: 'authorized', ...counted(authorized.data?.items), text: 'provizyon tahsil edilmeyi bekliyor', to: '/payments?status=AUTHORIZED', tone: 'info' },
  ];
  const tasksLoading = disputes.loading || authorized.loading || reviews.loading;

  const now = new Date();
  const dateText = new Intl.DateTimeFormat('tr-TR', { weekday: 'long', day: 'numeric', month: 'long' }).format(now);

  return (
    <>
      <PageHeader
        title="Genel bakış"
        description={me ? `${greeting(now.getHours())}, ${me.tenantName}. Bugün ${dateText}.` : undefined}
        actions={canTakePayments(me) && <button type="button" className="button button-primary" onClick={() => setCreating(true)}>Test ödemesi oluştur</button>}
      />
      <ErrorNote message={balances.error ?? summary.error} />

      <section className="stats kpis" aria-label="Özet">
        {balances.loading && !balances.data ? <div className="card stat kpi"><Loading rows={2} /></div> : balanceList.map((balance) => (
          <Kpi key={balance.currency} label={balanceList.length > 1 ? `Ödenecek bakiye (${balance.currency})` : 'Ödenecek bakiye'}
            value={formatMoney(balance.available, balance.currency)} negative={balance.available < 0}>
            <span className="hint">
              {balance.inTransit > 0 ? `${formatMoney(balance.inTransit, balance.currency)} bankaya yolda` : 'Sonraki hesap kesiminde ödenir'}
            </span>
          </Kpi>
        ))}
        {!data ? <div className="card stat kpi"><Loading rows={2} /></div> : (
          <>
            <Kpi label="Bugünkü tahsilat" value={formatMoney(today!.current, data.currency)}>
              <Change change={today!.change} against="Düne" />
            </Kpi>
            <Kpi label="Son 7 gün" value={formatMoney(week!.current, data.currency)}>
              <Change change={week!.change} against="Önceki 7 güne" />
            </Kpi>
            <Kpi label="Onay oranı" value={approval === null ? '—' : approval.toLocaleString('tr-TR', { style: 'percent', maximumFractionDigits: 1 })}>
              <span className="hint">Son 30 günde {data.totals.authorizationsApproved} / {data.totals.authorizationAttempts} deneme</span>
            </Kpi>
          </>
        )}
      </section>

      <div className="dashboard-grid">
        <div className="dashboard-main">
          <section className="card chart-card" aria-labelledby="chart-title">
            <div className="section-header section-header-tight">
              <div>
                <h2 id="chart-title">Son 30 gün</h2>
                {data && <span className="hint">{formatMoney(data.totals.captured, data.currency)} tahsilat · {data.totals.captureCount} işlem · net {formatMoney(data.totals.net, data.currency)}</span>}
              </div>
              <Link to="/analytics" className="button button-small">Analize git</Link>
            </div>
            {summary.loading && !data ? <Loading rows={4} />
              : hasMoney && data ? <DailyBars points={data.daily} currency={data.currency} />
                : <EmptyState title="Bu dönemde tahsilat yok">Bir ödeme tahsil edildiğinde günlük tutarlar burada görünür.</EmptyState>}
          </section>

          <div className="section-header">
            <h2>Son ödemeler</h2>
            <Link to="/payments">Tümünü gör</Link>
          </div>
          <ErrorNote message={payments.error} />
          {payments.loading && !payments.data ? <Loading /> : payments.data && payments.data.items.length > 0
            ? <PaymentTable payments={payments.data.items} />
            : !payments.error && (
              <div className="card">
                <EmptyState title="Henüz ödeme yok">
                  {canTakePayments(me) ? 'İlk ödemenizi görmek için bir test ödemesi oluşturun.' : 'Hesabınız onaylanınca ödemeleriniz burada görünür.'}
                </EmptyState>
              </div>
            )}
        </div>

        <aside className="dashboard-side" aria-label="Özet bilgiler">
          <TaskList tasks={tasks} loading={tasksLoading} />
          <section className="card" aria-labelledby="statuses-title">
            <h2 className="panel-title" id="statuses-title">Ödeme durumları</h2>
            {!data ? <Loading rows={3} /> : data.statuses.length > 0 ? (
              <HorizontalBars rows={data.statuses.map((row) => ({
                key: row.status,
                label: paymentStatusLabel(row.status),
                value: row.count,
                valueText: `${row.count} ödeme`,
                detail: formatMoney(row.amount, data.currency),
              }))} />
            ) : <p className="muted">Son 30 günde ödeme yok.</p>}
          </section>
        </aside>
      </div>
      {creating && <NewCheckoutDialog onClose={() => setCreating(false)} />}
    </>
  );
}
