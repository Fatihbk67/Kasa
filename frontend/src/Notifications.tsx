import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from './api';
import { locale, t } from './i18n';
import type { AppNotification, Inbox } from './types';
import { usePolling, useFetch } from './useFetch';

const POLL_MS = 30_000;

/** "3 dk önce", the way people say it, falling back to a date once it is more than a week old. */
export function timeAgo(value: string, now: number = Date.now()): string {
  const seconds = Math.max(0, Math.round((now - new Date(value).getTime()) / 1000));
  if (seconds < 60) {
    return t('az önce');
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return t('{count} dk önce', { count: minutes });
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return t('{count} sa önce', { count: hours });
  }
  const days = Math.floor(hours / 24);
  if (days < 7) {
    return t('{count} gün önce', { count: days });
  }
  return new Intl.DateTimeFormat(locale(), { dateStyle: 'medium' }).format(new Date(value));
}

/**
 * The bell in the sidebar. It asks again every half minute, which is plenty for news like a dispute or a payout
 * and costs the server one indexed count per open tab.
 */
export function NotificationBell() {
  const navigate = useNavigate();
  const inbox = useFetch(() => api<Inbox>('/notifications'), []);
  const [open, setOpen] = useState(false);
  const panel = useRef<HTMLDivElement>(null);
  usePolling(true, inbox.reload, POLL_MS);

  useEffect(() => {
    if (!open) {
      return;
    }
    const close = (event: MouseEvent | KeyboardEvent) => {
      if (event instanceof KeyboardEvent ? event.key === 'Escape' : !panel.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', close);
    return () => {
      document.removeEventListener('mousedown', close);
      document.removeEventListener('keydown', close);
    };
  }, [open]);

  const unread = inbox.data?.unread ?? 0;
  const items = inbox.data?.items ?? [];

  const choose = async (item: AppNotification) => {
    setOpen(false);
    if (!item.read) {
      await api(`/notifications/${item.id}/read`, { method: 'POST' }).catch(() => undefined);
      inbox.reload();
    }
    if (item.link) {
      navigate(item.link);
    }
  };

  const readAll = async () => {
    await api('/notifications/read-all', { method: 'POST' }).catch(() => undefined);
    inbox.reload();
  };

  return (
    <div className="bell" ref={panel}>
      <button type="button" className="bell-button" aria-expanded={open} aria-haspopup="true"
        aria-label={unread > 0 ? t('Bildirimler, {count} okunmamış', { count: unread }) : t('Bildirimler')} onClick={() => setOpen(!open)}>
        <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
          <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
        </svg>
        {unread > 0 && <span className="bell-count" aria-hidden="true">{unread > 9 ? '9+' : unread}</span>}
      </button>
      {open && (
        <div className="bell-panel" role="dialog" aria-label={t('Bildirimler')}>
          <div className="bell-header">
            <strong>{t('Bildirimler')}</strong>
            {unread > 0 && <button type="button" className="link-button" onClick={readAll}>{t('Tümünü okundu say')}</button>}
          </div>
          {items.length === 0 ? (
            <p className="bell-empty muted">{t('Henüz bildirim yok. İtirazlar, hesap kesimleri ve hesap durumunuz burada görünür.')}</p>
          ) : (
            <ul className="bell-list">
              {items.map((item) => (
                <li key={item.id}>
                  <button type="button" className={item.read ? 'bell-item' : 'bell-item bell-item-unread'} onClick={() => choose(item)}>
                    <span className="bell-title">{item.title}</span>
                    <span className="bell-body">{item.body}</span>
                    <span className="bell-time">{timeAgo(item.createdAt)}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
