import { describe, expect, it } from 'vitest';
import { timeAgo } from './Notifications';

const NOW = Date.parse('2026-11-04T12:00:00Z');

describe('timeAgo', () => {
  it('speaks in minutes, hours and days, then falls back to a date', () => {
    expect(timeAgo('2026-11-04T11:59:30Z', NOW)).toBe('az önce');
    expect(timeAgo('2026-11-04T11:57:00Z', NOW)).toBe('3 dk önce');
    expect(timeAgo('2026-11-04T09:00:00Z', NOW)).toBe('3 sa önce');
    expect(timeAgo('2026-11-02T12:00:00Z', NOW)).toBe('2 gün önce');
    expect(timeAgo('2026-10-01T12:00:00Z', NOW)).toMatch(/2026/);
  });

  it('treats a clock slightly ahead of the browser as just now', () => {
    expect(timeAgo('2026-11-04T12:00:05Z', NOW)).toBe('az önce');
  });
});
