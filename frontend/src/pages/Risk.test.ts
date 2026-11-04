import { describe, expect, it } from 'vitest';
import { describeRule } from './Risk';

describe('describeRule', () => {
  it('reads a card rule as a sentence with a natural window', () => {
    expect(describeRule({ kind: 'CARD_VELOCITY', threshold: 5, windowMinutes: 10, currency: null, action: 'BLOCK' }))
      .toBe('Aynı kart 10 dakika içinde 5 kereden fazla denenirse engelle');
    expect(describeRule({ kind: 'CARD_DECLINES', threshold: 3, windowMinutes: 60, currency: null, action: 'REVIEW' }))
      .toBe('Kart 1 saat içinde bankadan 3 kez reddedildiyse incelemeye al');
    expect(describeRule({ kind: 'CARD_VELOCITY', threshold: 2, windowMinutes: 2880, currency: null, action: 'BLOCK' }))
      .toContain('2 gün');
  });

  it('shows an amount rule in lira', () => {
    expect(describeRule({ kind: 'AMOUNT_ABOVE', threshold: 1_000_000, windowMinutes: null, currency: 'TRY', action: 'REVIEW' }))
      .toMatch(/^Tutar ₺10\.000,00 üstündeyse incelemeye al$/);
  });
});
