import { useEffect, useRef, useState } from 'react';
import { locale, t } from './i18n';
import { formatMoney } from './money';
import type { DayPoint } from './types';

const HEIGHT = 240;
const MARGIN = { top: 12, right: 8, bottom: 26, left: 72 };

/** Rounds up to a round multiple of a power of ten, so axis labels stay easy to read and bars fill the chart. */
export function niceMax(value: number): number {
  if (value <= 0) {
    return 1;
  }
  const power = 10 ** Math.floor(Math.log10(value));
  const step = [1, 1.5, 2, 3, 4, 5, 6, 8, 10].find((candidate) => candidate * power >= value) ?? 10;
  return step * power;
}

function shortDay(day: string): string {
  return new Intl.DateTimeFormat(locale(), { day: 'numeric', month: 'short' }).format(new Date(`${day}T00:00:00`));
}

function compactMoney(minorUnits: number, currency: string): string {
  return new Intl.NumberFormat(locale(), { style: 'currency', currency, currencyDisplay: 'narrowSymbol', notation: 'compact', maximumFractionDigits: 1 })
    .format(minorUnits / 100);
}

function roundedTopBar(x: number, y: number, width: number, height: number): string {
  const radius = Math.min(4, width / 2, height);
  return `M${x},${y + height}V${y + radius}Q${x},${y} ${x + radius},${y}H${x + width - radius}Q${x + width},${y} ${x + width},${y + radius}V${y + height}Z`;
}

export function DailyBars({ points, currency }: { points: DayPoint[]; currency: string }) {
  const container = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(0);
  const [active, setActive] = useState<number | null>(null);

  useEffect(() => {
    const element = container.current;
    if (!element) {
      return;
    }
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  const top = niceMax(Math.max(...points.map((point) => point.captured), 0));
  const plotWidth = Math.max(0, width - MARGIN.left - MARGIN.right);
  const plotHeight = HEIGHT - MARGIN.top - MARGIN.bottom;
  const band = points.length > 0 ? plotWidth / points.length : 0;
  const barWidth = Math.max(2, Math.min(band - 2, 28));
  const labelEvery = Math.max(1, Math.ceil(points.length / Math.max(2, Math.floor(plotWidth / 70))));
  const yFor = (value: number) => MARGIN.top + plotHeight * (1 - value / top);
  const activePoint = active === null ? null : points[active];

  return (
    <div className="chart" ref={container}>
      {width > 0 && (
        <svg width={width} height={HEIGHT} role="img" aria-label={t('Günlere göre tahsilat grafiği')} onMouseLeave={() => setActive(null)}>
          {[0, 0.5, 1].map((fraction) => (
            <g key={fraction}>
              <line className="chart-grid" x1={MARGIN.left} x2={width - MARGIN.right} y1={yFor(top * fraction)} y2={yFor(top * fraction)} />
              <text className="chart-tick" x={MARGIN.left - 8} y={yFor(top * fraction) + 4} textAnchor="end">
                {compactMoney(top * fraction, currency)}
              </text>
            </g>
          ))}
          {points.map((point, index) => {
            const center = MARGIN.left + band * index + band / 2;
            const barHeight = (point.captured / top) * plotHeight;
            return (
              <g key={point.day}>
                {point.captured > 0 && (
                  <path
                    className={active === index ? 'chart-bar chart-bar-active' : 'chart-bar'}
                    d={roundedTopBar(center - barWidth / 2, MARGIN.top + plotHeight - barHeight, barWidth, barHeight)}
                  />
                )}
                {index % labelEvery === 0 && (
                  <text className="chart-tick" x={center} y={HEIGHT - 8} textAnchor="middle">{shortDay(point.day)}</text>
                )}
                {/* A full-height target, so even a day with a tiny bar is easy to hover. */}
                <rect className="chart-hit" x={center - band / 2} y={MARGIN.top} width={band} height={plotHeight} onMouseEnter={() => setActive(index)} />
              </g>
            );
          })}
        </svg>
      )}
      {activePoint && active !== null && (
        <div
          className="chart-tooltip"
          style={{
            left: Math.min(MARGIN.left + band * active + band / 2 + 10, Math.max(0, width - 190)),
            top: MARGIN.top,
          }}
        >
          <strong>{shortDay(activePoint.day)}</strong>
          <span>{t('Tahsilat: {amount}', { amount: formatMoney(activePoint.captured, currency) })}</span>
          <span>{t('İade: {amount}', { amount: formatMoney(activePoint.refunded, currency) })}</span>
          <span>{t('{count} işlem', { count: activePoint.captureCount })}</span>
        </div>
      )}
    </div>
  );
}

export interface BarRow {
  key: string;
  label: string;
  value: number;
  valueText: string;
  detail?: string;
}

/** Every value is printed beside its bar, so the bar only adds a quick sense of proportion. */
export function HorizontalBars({ rows }: { rows: BarRow[] }) {
  const max = Math.max(...rows.map((row) => row.value), 1);
  return (
    <ul className="hbars">
      {rows.map((row) => (
        <li key={row.key}>
          <div className="hbar-header">
            <span>{row.label}</span>
            <span className="hbar-value">{row.valueText}</span>
          </div>
          <div className="hbar-track">
            <div className="hbar-fill" style={{ width: `${(row.value / max) * 100}%` }} />
          </div>
          {row.detail && <span className="hint">{row.detail}</span>}
        </li>
      ))}
    </ul>
  );
}
