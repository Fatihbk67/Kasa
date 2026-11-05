import { afterEach, describe, expect, it } from 'vitest';
import { setLang, t } from './i18n';
import { EN, EN_PARTS } from './locales/en';

// Every source file as text, read by Vite at test time; the dictionary and the tests themselves are left out.
const SOURCES = import.meta.glob<string>(['./**/*.{ts,tsx}', '!./**/*.test.ts', '!./locales/**'], {
  query: '?raw',
  import: 'default',
  eager: true,
});

const CALL = /\b(?:t|tk)\(\s*'((?:[^'\\]|\\.)*)'/g;
const PLACEHOLDER = /\{(\w+)\}/g;

function keysInCode(): Map<string, string> {
  const keys = new Map<string, string>();
  for (const [file, text] of Object.entries(SOURCES)) {
    for (const match of text.matchAll(CALL)) {
      keys.set(match[1].replace(/\\(.)/g, '$1'), file);
    }
  }
  return keys;
}

const placeholders = (text: string) => [...text.matchAll(PLACEHOLDER)].map((match) => match[1]).sort().join();

describe('English dictionary', () => {
  const keys = keysInCode();

  it('finds the texts in the code', () => {
    expect(Object.keys(SOURCES).length).toBeGreaterThan(30);
    expect(keys.size).toBeGreaterThan(50);
  });

  it('has English for every Turkish text the app shows', () => {
    const missing = [...keys].filter(([key]) => !(key in EN)).map(([key, file]) => `${file}: ${key}`);
    expect(missing).toEqual([]);
  });

  it('keeps no English for text the app no longer shows', () => {
    expect(Object.keys(EN).filter((key) => !keys.has(key))).toEqual([]);
  });

  it('keeps every placeholder in both languages', () => {
    const broken = Object.entries(EN).flatMap(([turkish, english]) =>
      english.split('|')
        .filter((form) => placeholders(form) !== placeholders(turkish))
        // A singular form may say "one" instead of printing the count.
        .filter((form) => !(english.includes('|') && placeholders(form) === placeholders(turkish.replace('{count}', ''))))
        .map((form) => `${turkish} → ${form}`));
    expect(broken).toEqual([]);
  });

  it('gives one Turkish text the same English everywhere', () => {
    const seen = new Map<string, string>();
    const conflicts: string[] = [];
    for (const part of Object.values(EN_PARTS)) {
      for (const [turkish, english] of Object.entries(part)) {
        if (seen.has(turkish) && seen.get(turkish) !== english) {
          conflicts.push(turkish);
        }
        seen.set(turkish, english);
      }
    }
    expect(conflicts).toEqual([]);
  });
});

describe('t()', () => {
  afterEach(() => setLang('tr'));

  it('shows the Turkish text itself, with its placeholders filled', () => {
    expect(t('{count} ödeme', { count: 3 })).toBe('3 ödeme');
  });

  it('picks the English singular or plural by count', () => {
    setLang('en');
    expect(t('{count} ödeme', { count: 1 })).toBe('1 payment');
    expect(t('{count} ödeme', { count: 3 })).toBe('3 payments');
  });

  it('falls back to Turkish rather than showing a key', () => {
    setLang('en');
    const unknown = ['Sözlükte', 'olmayan', 'metin'].join(' ');
    expect(t(unknown)).toBe(unknown);
  });
});
