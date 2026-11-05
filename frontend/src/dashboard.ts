import { locale, t } from './i18n';
import type { DayPoint } from './types';

export interface WindowComparison {
  current: number;
  previous: number;
  count: number;
  /** Relative change against the window before; null when there is nothing to compare with. */
  change: number | null;
}

/**
 * Sums the last {@code size} days and the {@code size} days before them. The points end with today, one per day,
 * so a window that is not fully covered by the data counts only what is there.
 */
export function compareWindows(daily: DayPoint[], size: number): WindowComparison {
  const current = daily.slice(-size);
  const previous = daily.slice(-2 * size, -size);
  const sum = (points: DayPoint[]) => points.reduce((total, point) => total + point.captured, 0);
  const now = sum(current);
  const before = sum(previous);
  return {
    current: now,
    previous: before,
    count: current.reduce((total, point) => total + point.captureCount, 0),
    change: previous.length === size && before > 0 ? (now - before) / before : null,
  };
}

export function formatChange(change: number): string {
  const percent = new Intl.NumberFormat(locale(), { style: 'percent', maximumFractionDigits: 0, signDisplay: 'exceptZero' });
  return percent.format(change);
}

export function greeting(hour: number): string {
  if (hour >= 5 && hour < 12) {
    return t('Günaydın');
  }
  if (hour >= 12 && hour < 18) {
    return t('İyi günler');
  }
  if (hour >= 18 && hour < 23) {
    return t('İyi akşamlar');
  }
  return t('İyi geceler');
}
