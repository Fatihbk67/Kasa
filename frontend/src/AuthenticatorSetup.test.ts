import { describe, expect, it } from 'vitest';
import { groupKey } from './AuthenticatorSetup';

describe('groupKey', () => {
  it('splits the key into groups of four, as authenticator apps show it', () => {
    expect(groupKey('ABCDEFGHIJKLMNOPQRSTUVWXYZ234567')).toBe('ABCD EFGH IJKL MNOP QRST UVWX YZ23 4567');
  });

  it('leaves no trailing space when the last group is short', () => {
    expect(groupKey('ABCDEF')).toBe('ABCD EF');
    expect(groupKey('ABCD')).toBe('ABCD');
  });
});
