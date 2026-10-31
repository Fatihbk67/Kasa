const MAX_MINOR_UNITS = 1_000_000_000_000;

export function formatMoney(minorUnits: number, currency: string): string {
  return new Intl.NumberFormat('tr-TR', { style: 'currency', currency }).format(minorUnits / 100);
}

/**
 * Turns what a person types into minor units using only string and integer steps, because floating point
 * would turn an input like 19,99 into 1998.9999. Returns null when the text is not a valid positive amount.
 */
export function parseAmount(text: string): number | null {
  const input = text.trim();
  const turkishGrouped = /^\d{1,3}(\.\d{3})+(,\d{1,2})?$/;
  const plain = /^\d+([.,]\d{1,2})?$/;
  if (!turkishGrouped.test(input) && !plain.test(input)) {
    return null;
  }
  const normalized = turkishGrouped.test(input) ? input.replaceAll('.', '').replace(',', '.') : input.replace(',', '.');
  const [whole, fraction = ''] = normalized.split('.');
  const minorUnits = Number(whole) * 100 + Number(fraction.padEnd(2, '0'));
  return minorUnits > 0 && minorUnits <= MAX_MINOR_UNITS ? minorUnits : null;
}
