import { describe, expect, it } from 'vitest';
import type { Operation } from '../types';
import { timelineOrder } from './PaymentDetail';

const op = (id: string, createdAt: string): Operation => ({ id, type: 'REFUND', amount: 100, status: 'SUCCEEDED', failureCode: null, createdAt });

describe('payment timeline', () => {
  it('lists operations oldest first by time, not by text, without changing the input', () => {
    const operations = [
      op('refund', '2026-11-05T11:26:40.5Z'),
      op('authorize', '2026-11-05T11:26:39.123456Z'),
      op('capture', '2026-11-05T11:26:40.02Z'),
    ];
    expect(timelineOrder(operations).map((operation) => operation.id)).toEqual(['authorize', 'capture', 'refund']);
    expect(operations[0].id).toBe('refund');
  });
});
