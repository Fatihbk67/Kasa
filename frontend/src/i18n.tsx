import { createContext, Fragment, useContext, useState, type ReactNode } from 'react';
import { EN } from './locales/en';

/**
 * Turkish is written in the code itself and doubles as the key; English comes from a dictionary. A missing English
 * entry therefore still shows readable Turkish rather than a key, and i18n.test.ts fails the build before that can
 * ship. Placeholders look like {name}. An English entry may hold "singular|plural", picked by {count}.
 */
export type Lang = 'tr' | 'en';

export const LANG_LABELS: Record<Lang, string> = { tr: 'Türkçe', en: 'English' };

const STORAGE_KEY = 'kasa.lang';

function initialLang(): Lang {
  if (typeof window === 'undefined') {
    return 'tr';
  }
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === 'tr' || stored === 'en') {
      return stored;
    }
  } catch {
    // Storage can be blocked; the browser's language decides then.
  }
  return typeof navigator !== 'undefined' && !navigator.language.toLowerCase().startsWith('tr') ? 'en' : 'tr';
}

let current: Lang = initialLang();
if (typeof document !== 'undefined') {
  document.documentElement.lang = current;
}

export function lang(): Lang {
  return current;
}

/** Changes the language for every t() call from now on. Components re-render through LanguageProvider. */
export function setLang(next: Lang) {
  current = next;
  if (typeof document !== 'undefined') {
    document.documentElement.lang = next;
  }
  try {
    localStorage.setItem(STORAGE_KEY, next);
  } catch {
    // The choice then lasts until the page reloads.
  }
}

/** For Intl: numbers, money and dates follow the chosen language. */
export function locale(): string {
  return current === 'tr' ? 'tr-TR' : 'en-GB';
}

export function t(text: string, values?: Record<string, string | number>): string {
  let template = current === 'en' ? (EN[text] ?? text) : text;
  if (template.includes('|') && current === 'en' && values && typeof values.count === 'number') {
    const [one, other] = template.split('|');
    template = values.count === 1 ? one : other;
  }
  return values ? template.replace(/\{(\w+)\}/g, (match, name: string) => (name in values ? String(values[name]) : match)) : template;
}

/** Marks text kept in a constant and translated where it is shown, so the dictionary check still finds it. */
export function tk<T extends string>(text: T): T {
  return text;
}

const LanguageContext = createContext<{ lang: Lang; setLang: (lang: Lang) => void }>({ lang: current, setLang: () => {} });

/**
 * Switching language re-mounts the app under a new key. Every t() call then runs again, labels kept in constants
 * included, at the price of reloading the page's data, which a language switch can afford.
 */
export function LanguageProvider({ children }: { children: ReactNode }) {
  const [active, setActive] = useState<Lang>(current);
  const choose = (next: Lang) => {
    setLang(next);
    setActive(next);
  };
  return (
    <LanguageContext.Provider value={{ lang: active, setLang: choose }}>
      <Fragment key={active}>{children}</Fragment>
    </LanguageContext.Provider>
  );
}

export function useLanguage() {
  return useContext(LanguageContext);
}

export function LanguagePicker({ compact }: { compact?: boolean }) {
  const { lang: active, setLang } = useLanguage();
  return (
    <div className={compact ? 'segmented language-picker language-picker-compact' : 'segmented language-picker'} role="group" aria-label={t('Dil')}>
      {(['tr', 'en'] as const).map((choice) => (
        <button key={choice} type="button" lang={choice} aria-pressed={active === choice} title={LANG_LABELS[choice]} onClick={() => setLang(choice)}>
          {choice.toUpperCase()}
        </button>
      ))}
    </div>
  );
}
