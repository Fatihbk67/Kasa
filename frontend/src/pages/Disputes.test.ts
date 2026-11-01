import { describe, expect, it } from 'vitest';
import { daysLeft } from './Disputes';

const NOW = Date.parse('2026-11-01T12:00:00Z');

describe('daysLeft', () => {
  it('rounds up so the last hours still read as a day left', () => {
    expect(daysLeft('2026-11-01T13:00:00Z', NOW)).toBe(1);
    expect(daysLeft('2026-11-08T12:00:00Z', NOW)).toBe(7);
  });

  it('never goes negative after the deadline', () => {
    expect(daysLeft('2026-10-30T12:00:00Z', NOW)).toBe(0);
  });
});
