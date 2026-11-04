import { describe, expect, it } from 'vitest';
import { formatIban, isValidTckn, isValidTrIban, isValidVkn } from './identifiers';

const IBAN = 'TR330006100519786457841326';

describe('identifiers', () => {
  it('accepts a valid identity number and rejects a wrong check digit or a leading zero', () => {
    expect(isValidTckn('10000000146')).toBe(true);
    expect(isValidTckn('10000000145')).toBe(false);
    expect(isValidTckn('01000000146')).toBe(false);
    expect(isValidTckn('1000000014')).toBe(false);
  });

  it('accepts exactly one last digit for a tax number, matching the server', () => {
    const valid = [...'0123456789'].filter((last) => isValidVkn(`123456789${last}`));
    expect(valid).toEqual(['0']);
    expect(isValidVkn('12345678')).toBe(false);
  });

  it('accepts a Turkish IBAN typed with spaces in lower case', () => {
    expect(isValidTrIban('tr33 0006 1005 1978 6457 8413 26')).toBe(true);
  });

  it('catches every single digit typo in an IBAN', () => {
    for (let i = 2; i < IBAN.length; i++) {
      if (i === 9) {
        continue;
      }
      const wrong = IBAN.slice(0, i) + String((Number(IBAN[i]) + 1) % 10) + IBAN.slice(i + 1);
      expect(isValidTrIban(wrong), wrong).toBe(false);
    }
  });

  it('rejects foreign IBANs and a nonzero reserved digit', () => {
    expect(isValidTrIban('DE89370400440532013000')).toBe(false);
    expect(isValidTrIban(IBAN.slice(0, 9) + '1' + IBAN.slice(10))).toBe(false);
  });

  it('groups an IBAN in fours', () => {
    expect(formatIban(IBAN)).toBe('TR33 0006 1005 1978 6457 8413 26');
  });
});
