import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { useNavigate } from 'react-router-dom';

export interface Command {
  to: string;
  label: string;
  group: string;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Records an id can belong to. A pasted id is offered for each, since the id alone does not say which it is. */
const RECORDS: { path: string; label: string }[] = [
  { path: '/payments', label: 'Ödemeyi aç' },
  { path: '/customers', label: 'Müşteriyi aç' },
  { path: '/subscriptions', label: 'Aboneliği aç' },
  { path: '/disputes', label: 'İtirazı aç' },
  { path: '/payouts', label: 'Hesap kesimini aç' },
];

/** Lower case without Turkish marks, so "odeme" finds "Ödemeler" and "IADE" finds "İade". */
export function fold(text: string): string {
  return text
    .toLocaleLowerCase('tr')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/ı/g, 'i');
}

/**
 * Pages whose name contains every typed word, those starting with the first word first. A pasted id instead
 * offers to open it as each kind of record.
 */
export function searchCommands(query: string, pages: Command[]): Command[] {
  const trimmed = query.trim();
  if (UUID.test(trimmed)) {
    const id = trimmed.toLowerCase();
    return RECORDS.map((record) => ({ to: `${record.path}/${id}`, label: record.label, group: 'Kimlikle aç' }));
  }
  const words = fold(trimmed).split(/\s+/).filter(Boolean);
  if (words.length === 0) {
    return pages;
  }
  const matches = pages.filter((page) => {
    const name = fold(`${page.label} ${page.group}`);
    return words.every((word) => name.includes(word));
  });
  const startsWith = (page: Command) => fold(page.label).split(/\s+/).some((part) => part.startsWith(words[0]));
  return [...matches.filter(startsWith), ...matches.filter((page) => !startsWith(page))];
}

/** Opens on Ctrl+K (⌘K on a Mac) from anywhere in the panel. */
export function useCommandShortcut(open: () => void) {
  useEffect(() => {
    const onKey = (event: globalThis.KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        open();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);
}

export function CommandPalette({ pages, onClose }: { pages: Command[]; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const listId = useId();
  const results = searchCommands(query, pages);
  const selected = Math.min(active, Math.max(results.length - 1, 0));

  useEffect(() => {
    dialog.current?.showModal();
  }, []);

  useEffect(() => {
    document.getElementById(`${listId}-${selected}`)?.scrollIntoView({ block: 'nearest' });
  }, [listId, selected]);

  const go = (command: Command | undefined) => {
    if (command) {
      onClose();
      navigate(command.to);
    }
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const step = event.key === 'ArrowDown' ? 1 : -1;
      setActive((selected + step + results.length) % Math.max(results.length, 1));
    } else if (event.key === 'Enter') {
      event.preventDefault();
      go(results[selected]);
    }
  };

  return (
    <dialog ref={dialog} className="dialog command-palette" aria-label="Hızlı arama" onClose={onClose}
      onClick={(event) => event.target === dialog.current && onClose()}>
      <input
        className="command-input"
        autoFocus
        role="combobox"
        aria-expanded="true"
        aria-controls={listId}
        aria-activedescendant={results.length ? `${listId}-${selected}` : undefined}
        aria-label="Sayfa adı ya da ödeme kimliği"
        placeholder="Sayfa ara ya da ödeme kimliği yapıştır…"
        value={query}
        onChange={(event) => {
          setQuery(event.target.value);
          setActive(0);
        }}
        onKeyDown={onKeyDown}
      />
      <ul id={listId} role="listbox" className="command-list" aria-label="Sonuçlar">
        {results.map((command, index) => (
          <li
            key={command.to}
            id={`${listId}-${index}`}
            role="option"
            aria-selected={index === selected}
            className={index === selected ? 'command-item active' : 'command-item'}
            onMouseMove={() => setActive(index)}
            onClick={() => go(command)}
          >
            <span>{command.label}</span>
            <span className="hint">{command.group}</span>
          </li>
        ))}
      </ul>
      {results.length === 0 && <p className="command-empty muted">“{query.trim()}” ile eşleşen sayfa yok.</p>}
      <p className="command-help hint">
        <kbd>↑</kbd> <kbd>↓</kbd> seç · <kbd>Enter</kbd> aç · <kbd>Esc</kbd> kapat
      </p>
    </dialog>
  );
}
