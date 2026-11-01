import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../api';
import { DailyBars, HorizontalBars } from '../charts';
import { EmptyState, ErrorNote, failureMessage, Loading, PageHeader, paymentStatusLabel } from '../components';
import { formatMoney } from '../money';
import type { AnalyticsSummary } from '../types';
import { useFetch } from '../useFetch';

const RANGES = [7, 30, 90];

function Stat({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="card stat">
      <span className="stat-label">{label}</span>
      <span className="stat-value stat-value-small">{value}</span>
      {hint && <span className="hint">{hint}</span>}
    </div>
  );
}

function shortDate(day: string): string {
  return new Intl.DateTimeFormat('tr-TR', { dateStyle: 'medium' }).format(new Date(`${day}T00:00:00`));
}

export function Analytics() {
  const [params, setParams] = useSearchParams();
  const requested = Number(params.get('days'));
  const days = RANGES.includes(requested) ? requested : 30;
  const summary = useFetch(() => api<AnalyticsSummary>(`/analytics/summary?days=${days}`), [days]);
  const [showTable, setShowTable] = useState(false);

  const data = summary.data;
  const rate = data && data.totals.authorizationAttempts > 0
    ? (data.totals.authorizationsApproved / data.totals.authorizationAttempts).toLocaleString('tr-TR', { style: 'percent', maximumFractionDigits: 1 })
    : '—';
  const hasMoney = data?.daily.some((point) => point.captured > 0 || point.refunded > 0) ?? false;

  return (
    <>
      <PageHeader
        title="Analiz"
        description="Tutarlar paranın gerçekten hareket ettiği güne yazılır."
        actions={(
          <div className="segmented" role="group" aria-label="Zaman aralığı">
            {RANGES.map((range) => (
              <button key={range} type="button" aria-pressed={range === days} onClick={() => setParams({ days: String(range) })}>
                {range} gün
              </button>
            ))}
          </div>
        )}
      />
      <ErrorNote message={summary.error} />
      {summary.loading && !data ? <Loading /> : data && (
        <>
          <section className="stats stats-wide" aria-label="Özet">
            <Stat label="Tahsilat hacmi" value={formatMoney(data.totals.captured, data.currency)} hint={`${data.totals.captureCount} tahsilat`} />
            <Stat label="Net kazanç" value={formatMoney(data.totals.net, data.currency)} hint="Komisyon ve iadeler düşüldü" />
            <Stat label="Komisyon" value={formatMoney(data.totals.fees, data.currency)} />
            <Stat label="İadeler" value={formatMoney(data.totals.refunded, data.currency)} />
            <Stat label="Onay oranı" value={rate} hint={`${data.totals.authorizationsApproved} / ${data.totals.authorizationAttempts} deneme`} />
          </section>

          <section className="card chart-card">
            <div className="section-header section-header-tight">
              <h2>Günlük tahsilat</h2>
              <button type="button" className="button button-small" aria-expanded={showTable} onClick={() => setShowTable(!showTable)}>
                {showTable ? 'Tabloyu gizle' : 'Tablo olarak göster'}
              </button>
            </div>
            {hasMoney
              ? <DailyBars points={data.daily} currency={data.currency} />
              : <EmptyState title="Bu dönemde para hareketi yok">Bir ödeme tahsil edildiğinde burada görünür.</EmptyState>}
            {showTable && (
              <div className="table-wrap chart-table">
                <table>
                  <thead>
                    <tr>
                      <th scope="col">Gün</th>
                      <th scope="col" className="numeric">Tahsilat</th>
                      <th scope="col" className="numeric">İade</th>
                      <th scope="col" className="numeric">İşlem</th>
                    </tr>
                  </thead>
                  <tbody>
                    {[...data.daily].reverse().map((point) => (
                      <tr key={point.day}>
                        <td>{shortDate(point.day)}</td>
                        <td className="numeric">{formatMoney(point.captured, data.currency)}</td>
                        <td className="numeric">{formatMoney(point.refunded, data.currency)}</td>
                        <td className="numeric">{point.captureCount}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <div className="panels">
            <section className="card">
              <h2 className="panel-title">Ödeme durumları</h2>
              {data.statuses.length > 0 ? (
                <HorizontalBars rows={data.statuses.map((row) => ({
                  key: row.status,
                  label: paymentStatusLabel(row.status),
                  value: row.count,
                  valueText: `${row.count} ödeme`,
                  detail: formatMoney(row.amount, data.currency),
                }))} />
              ) : <p className="muted">Bu dönemde ödeme yok.</p>}
            </section>
            <section className="card">
              <h2 className="panel-title">Reddedilme nedenleri</h2>
              {data.failures.length > 0 ? (
                <HorizontalBars rows={data.failures.map((row) => ({
                  key: row.code,
                  label: failureMessage(row.code),
                  value: row.count,
                  valueText: `${row.count} kez`,
                }))} />
              ) : <p className="muted">Bu dönemde reddedilen ödeme yok.</p>}
            </section>
          </div>
        </>
      )}
    </>
  );
}
