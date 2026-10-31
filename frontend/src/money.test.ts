import { describe, expect, it } from 'vitest';
import { formatMoney, parseAmount } from './money';

describe('parseAmount', () => {
  it.each([
    ['100', 10_000],
    ['19,99', 1_999],
    ['19.99', 1_999],
    ['0,01', 1],
    ['12,5', 1_250],
    ['1.250', 125_000],
    ['1.250,75', 125_075],
    ['1.000.000', 100_000_000],
    ['  42 ', 4_200],
  ])('reads %s as %i minor units', (text, expected) => {
    expect(parseAmount(text)).toBe(expected);
  });

  it('keeps every two-decimal amount exact', () => {
    for (let minor = 1; minor < 2_000; minor++) {
      const text = `${Math.floor(minor / 100)},${String(minor % 100).padStart(2, '0')}`;
      expect(parseAmount(text)).toBe(minor);
    }
  });

  it.each(['', 'abc', '0', '0,00', '-5', '1,234', '1.25.0', '12,', '1e3', '10000000001'])('rejects %s', (text) => {
    expect(parseAmount(text)).toBeNull();
  });
});

describe('formatMoney', () => {
  it('formats minor units in Turkish style', () => {
    expect(formatMoney(125_075, 'TRY').replace(/\s/g, ' ')).toBe('₺1.250,75');
  });

  it('shows a negative balance', () => {
    expect(formatMoney(-250, 'TRY')).toContain('2,50');
    expect(formatMoney(-250, 'TRY')).toMatch(/^-/);
  });
});
