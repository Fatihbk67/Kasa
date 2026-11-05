import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../api';
import { DailyBars, HorizontalBars } from '../charts';
import { EmptyState, ErrorNote, failureMessage, Loading, PageHeader, paymentStatusLabel } from '../components';
import { locale, t } from '../i18n';
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
  return new Intl.DateTimeFormat(locale(), { dateStyle: 'medium' }).format(new Date(`${day}T00:00:00`));
}

export function Analytics() {
  const [params, setParams] = useSearchParams();
  const requested = Number(params.get('days'));
  const days = RANGES.includes(requested) ? requested : 30;
  const summary = useFetch(() => api<AnalyticsSummary>(`/analytics/summary?days=${days}`), [days]);
  const [showTable, setShowTable] = useState(false);

  const data = summary.data;
  const rate = data && data.totals.authorizationAttempts > 0
    ? (data.totals.authorizationsApproved / data.totals.authorizationAttempts).toLocaleString(locale(), { style: 'percent', maximumFractionDigits: 1 })
    : '—';
  const hasMoney = data?.daily.some((point) => point.captured > 0 || point.refunded > 0) ?? false;

  return (
    <>
      <PageHeader
        title={t('Analiz')}
        description={t('Tutarlar paranın gerçekten hareket ettiği güne yazılır.')}
        actions={(
          <div className="segmented" role="group" aria-label={t('Zaman aralığı')}>
            {RANGES.map((range) => (
              <button key={range} type="button" aria-pressed={range === days} onClick={() => setParams({ days: String(range) })}>
                {t('{count} gün', { count: range })}
              </button>
            ))}
          </div>
        )}
      />
      <ErrorNote message={summary.error} />
      {summary.loading && !data ? <Loading /> : data && (
        <>
          <section className="stats stats-wide" aria-label={t('Özet')}>
            <Stat label={t('Tahsilat hacmi')} value={formatMoney(data.totals.captured, data.currency)} hint={t('{count} tahsilat', { count: data.totals.captureCount })} />
            <Stat label={t('Net kazanç')} value={formatMoney(data.totals.net, data.currency)} hint={t('Komisyon, iade ve itirazlar düşüldü')} />
            <Stat label={t('Komisyon')} value={formatMoney(data.totals.fees, data.currency)} />
            <Stat label={t('İadeler')} value={formatMoney(data.totals.refunded, data.currency)} />
            <Stat label={t('İtirazlar')} value={formatMoney(data.totals.disputed + data.totals.disputeFees, data.currency)} hint={t('Kesinti ve ücretler, geri kazanılanlar düşüldü')} />
            <Stat label={t('Onay oranı')} value={rate} hint={t('{approved} / {attempts} deneme', { approved: data.totals.authorizationsApproved, attempts: data.totals.authorizationAttempts })} />
          </section>

          <section className="card chart-card">
            <div className="section-header section-header-tight">
              <h2>{t('Günlük tahsilat')}</h2>
              <button type="button" className="button button-small" aria-expanded={showTable} onClick={() => setShowTable(!showTable)}>
                {showTable ? t('Tabloyu gizle') : t('Tablo olarak göster')}
              </button>
            </div>
            {hasMoney
              ? <DailyBars points={data.daily} currency={data.currency} />
              : <EmptyState title={t('Bu dönemde para hareketi yok')}>{t('Bir ödeme tahsil edildiğinde burada görünür.')}</EmptyState>}
            {showTable && (
              <div className="table-wrap chart-table">
                <table>
                  <thead>
                    <tr>
                      <th scope="col">{t('Gün')}</th>
                      <th scope="col" className="numeric">{t('Tahsilat')}</th>
                      <th scope="col" className="numeric">{t('İade')}</th>
                      <th scope="col" className="numeric">{t('İşlem')}</th>
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
              <h2 className="panel-title">{t('Ödeme durumları')}</h2>
              {data.statuses.length > 0 ? (
                <HorizontalBars rows={data.statuses.map((row) => ({
                  key: row.status,
                  label: paymentStatusLabel(row.status),
                  value: row.count,
                  valueText: t('{count} ödeme', { count: row.count }),
                  detail: formatMoney(row.amount, data.currency),
                }))} />
              ) : <p className="muted">{t('Bu dönemde ödeme yok.')}</p>}
            </section>
            <section className="card">
              <h2 className="panel-title">{t('Reddedilme nedenleri')}</h2>
              {data.failures.length > 0 ? (
                <HorizontalBars rows={data.failures.map((row) => ({
                  key: row.code,
                  label: failureMessage(row.code),
                  value: row.count,
                  valueText: t('{count} kez', { count: row.count }),
                }))} />
              ) : <p className="muted">{t('Bu dönemde reddedilen ödeme yok.')}</p>}
            </section>
          </div>
        </>
      )}
    </>
  );
}
