/**
 * The same check-digit rules the server applies, so a typo is caught while the person is still typing.
 * The server checks again on its own; these only spare a round trip.
 */

function digits(value: string, length: number): number[] | null {
  return new RegExp(`^\\d{${length}}$`).test(value) ? [...value].map(Number) : null;
}

/** T.C. kimlik numarası: 11 digits, never starting with 0, with two check digits. */
export function isValidTckn(value: string): boolean {
  const d = digits(value, 11);
  if (!d || d[0] === 0) {
    return false;
  }
  const odd = d[0] + d[2] + d[4] + d[6] + d[8];
  const even = d[1] + d[3] + d[5] + d[7];
  const tenth = (((odd * 7 - even) % 10) + 10) % 10;
  return d[9] === tenth && d[10] === (odd + even + d[9]) % 10;
}

/** Vergi kimlik numarası: 10 digits, the last one a check digit over the first nine. */
export function isValidVkn(value: string): boolean {
  const d = digits(value, 10);
  if (!d) {
    return false;
  }
  let sum = 0;
  for (let i = 0; i < 9; i++) {
    const shifted = (d[i] + 9 - i) % 10;
    let part = (shifted * 2 ** (9 - i)) % 9;
    if (shifted !== 0 && part === 0) {
      part = 9;
    }
    sum += part;
  }
  return d[9] === (10 - (sum % 10)) % 10;
}

export function normalizeIban(value: string): string {
  return value.replace(/\s+/g, '').toUpperCase();
}

/** A Turkish IBAN: TR, two check digits, a five digit bank code, a reserved 0 and the account number. */
export function isValidTrIban(value: string): boolean {
  const iban = normalizeIban(value);
  if (!/^TR\d{24}$/.test(iban) || iban[9] !== '0') {
    return false;
  }
  // ISO 7064 mod 97, taken piece by piece so the number never outgrows a double.
  const rearranged = iban.slice(4) + '2927' + iban.slice(2, 4);
  let remainder = 0;
  for (const char of rearranged) {
    remainder = (remainder * 10 + Number(char)) % 97;
  }
  return remainder === 1;
}

/** Groups an IBAN in fours as it is printed on a bank card or statement. */
export function formatIban(value: string): string {
  return normalizeIban(value).replace(/(.{4})/g, '$1 ').trim();
}
