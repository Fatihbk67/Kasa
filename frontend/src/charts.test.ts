import { describe, expect, it } from 'vitest';
import { niceMax } from './charts';

describe('niceMax', () => {
  it.each([
    [0, 1],
    [1, 1],
    [7, 8],
    [101, 150],
    [125_000, 150_000],
    [1_050_000, 1_500_000],
    [480_000, 500_000],
    [500_000, 500_000],
    [500_001, 600_000],
    [800_001, 1_000_000],
  ])('rounds %i up to %i', (value, expected) => {
    expect(niceMax(value)).toBeCloseTo(expected);
  });

  it('never returns less than the value, so no bar can overflow the chart', () => {
    for (let value = 1; value < 5_000; value += 7) {
      expect(niceMax(value)).toBeGreaterThanOrEqual(value);
    }
  });
});
