import { describe, expect, it } from 'vitest';
import { secondsLeft, sessionOf } from './BankChallenge';
import { installmentOptions } from './Checkout';

describe('bank challenge', () => {
  it('finds the shop session the bank returns to', () => {
    expect(sessionOf('/api/v1/public/checkout/0b6f8a7e-1c2d-4e5f-8a9b-0c1d2e3f4a5b/3ds-callback'))
      .toBe('0b6f8a7e-1c2d-4e5f-8a9b-0c1d2e3f4a5b');
    expect(sessionOf('https://evil.example/steal')).toBeNull();
    expect(sessionOf('https://evil.example/api/v1/public/checkout/0b6f8a7e-1c2d-4e5f-8a9b-0c1d2e3f4a5b/3ds-callback')).toBeNull();
  });

  it('never counts below zero', () => {
    const now = Date.parse('2026-11-04T12:00:00Z');
    expect(secondsLeft('2026-11-04T12:01:30Z', now)).toBe(90);
    expect(secondsLeft('2026-11-04T11:59:00Z', now)).toBe(0);
  });
});

describe('installment options', () => {
  it('offers only a single payment below the minimum', () => {
    expect(installmentOptions(19_999).map((option) => option.count)).toEqual([1]);
  });

  it('splits the same total into monthly parts, rounding up so the parts never fall short', () => {
    const options = installmentOptions(100_000);
    expect(options.map((option) => option.count)).toEqual([1, 2, 3, 6, 9, 12]);
    expect(options.find((option) => option.count === 3)!.monthly).toBe(33_334);
  });
});
