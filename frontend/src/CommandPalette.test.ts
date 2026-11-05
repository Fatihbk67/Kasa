import { describe, expect, it } from 'vitest';
import { fold, searchCommands, type Command } from './CommandPalette';

const PAGES: Command[] = [
  { to: '/', label: 'Genel bakış', group: 'Panel' },
  { to: '/payments', label: 'Ödemeler', group: 'Para' },
  { to: '/payment-links', label: 'Ödeme bağlantıları', group: 'Para' },
  { to: '/disputes', label: 'İtirazlar', group: 'Para' },
  { to: '/risk', label: 'Risk ve dolandırıcılık', group: 'Para' },
  { to: '/settings', label: 'Ayarlar', group: 'Hesap' },
];

describe('command palette search', () => {
  it('ignores Turkish marks and case', () => {
    expect(fold('İTİRAZ Ödeme ışık')).toBe('itiraz odeme isik');
    expect(searchCommands('odeme', PAGES).map((page) => page.to)).toEqual(['/payments', '/payment-links']);
    expect(searchCommands('ITIRAZ', PAGES).map((page) => page.to)).toEqual(['/disputes']);
  });

  it('needs every word and puts names starting with the first word first', () => {
    expect(searchCommands('bağlantı ödeme', PAGES).map((page) => page.to)).toEqual(['/payment-links']);
    expect(searchCommands('dolandır', PAGES).map((page) => page.to)).toEqual(['/risk']);
    // "i" is inside several names, but only "İtirazlar" starts with it.
    const results = searchCommands('i', PAGES).map((page) => page.to);
    expect(results[0]).toBe('/disputes');
    expect(results).toContain('/risk');
  });

  it('matches the group name too, and lists everything for an empty query', () => {
    expect(searchCommands('hesap', PAGES).map((page) => page.to)).toEqual(['/settings']);
    expect(searchCommands('  ', PAGES)).toEqual(PAGES);
  });

  it('offers to open a pasted id as each kind of record', () => {
    const id = ' 4DF4C531-071B-4E6D-8ECC-7546C924259F ';
    const results = searchCommands(id, PAGES);
    expect(results[0]).toEqual({ to: '/payments/4df4c531-071b-4e6d-8ecc-7546c924259f', label: 'Ödemeyi aç', group: 'Kimlikle aç' });
    expect(results.map((result) => result.to.split('/')[1])).toEqual(['payments', 'customers', 'subscriptions', 'disputes', 'payouts']);
  });

  it('finds nothing for an unknown word', () => {
    expect(searchCommands('fatura', PAGES)).toEqual([]);
  });
});
