import { describe, expect, it } from 'vitest';
import { compareWindows, formatChange, greeting } from './dashboard';
import type { DayPoint } from './types';

const days = (...captured: number[]): DayPoint[] =>
  captured.map((amount, index) => ({ day: `2026-11-${String(index + 1).padStart(2, '0')}`, captured: amount, refunded: 0, captureCount: amount > 0 ? 1 : 0 }));

describe('dashboard', () => {
  it('compares the last days with the same number of days before them', () => {
    const result = compareWindows(days(100, 100, 300, 500), 2);
    expect(result).toEqual({ current: 800, previous: 200, count: 2, change: 3 });
  });

  it('compares today with yesterday', () => {
    expect(compareWindows(days(0, 400, 300), 1).change).toBeCloseTo(-0.25);
  });

  it('has no change when the earlier window is empty or not fully covered', () => {
    expect(compareWindows(days(0, 0, 500, 500), 2).change).toBeNull();
    expect(compareWindows(days(100, 500, 500), 2).change).toBeNull();
    expect(compareWindows([], 7)).toEqual({ current: 0, previous: 0, count: 0, change: null });
  });

  it('shows the change as a signed percentage', () => {
    expect(formatChange(0.125)).toMatch(/^\+%13$|^\+13\s?%$/);
    expect(formatChange(-0.25)).toMatch(/^-%25$|^-25\s?%$/);
    expect(formatChange(0)).toMatch(/^%0$|^0\s?%$/);
  });

  it('greets by the time of day', () => {
    expect(greeting(8)).toBe('Günaydın');
    expect(greeting(14)).toBe('İyi günler');
    expect(greeting(20)).toBe('İyi akşamlar');
    expect(greeting(2)).toBe('İyi geceler');
  });
});
