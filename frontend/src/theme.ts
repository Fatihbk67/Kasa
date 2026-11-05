export type ThemeChoice = 'system' | 'light' | 'dark';

const STORAGE_KEY = 'kasa.theme';

import { tk } from './i18n';

/** Translated where shown. */
export const THEME_LABELS: Record<ThemeChoice, string> = {
  system: tk('Sistem'),
  light: tk('Açık'),
  dark: tk('Koyu'),
};

/** The person's saved choice; "system" when none is saved or storage is unavailable. */
export function storedTheme(): ThemeChoice {
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    return value === 'light' || value === 'dark' ? value : 'system';
  } catch {
    return 'system';
  }
}

/** Applies the choice to the page; the stylesheet reads it from the root element's data-theme attribute. */
export function applyTheme(choice: ThemeChoice): void {
  const root = document.documentElement;
  if (choice === 'system') {
    root.removeAttribute('data-theme');
  } else {
    root.setAttribute('data-theme', choice);
  }
}

export function saveTheme(choice: ThemeChoice): void {
  try {
    if (choice === 'system') {
      localStorage.removeItem(STORAGE_KEY);
    } else {
      localStorage.setItem(STORAGE_KEY, choice);
    }
  } catch {
    // Still applied for this visit, just not remembered.
  }
  applyTheme(choice);
}
