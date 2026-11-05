import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import type { DisputeStatus, InvoiceStatus, OperationStatus, PaymentLinkStatus, PaymentStatus, Payout, SubscriptionStatus, WebhookDelivery } from './types';

type Tone = 'success' | 'warning' | 'danger' | 'neutral' | 'info';

const PAYMENT_STATUS: Record<PaymentStatus, { label: string; tone: Tone }> = {
  PENDING: { label: 'Bankadan yanıt bekleniyor', tone: 'warning' },
  AUTHORIZED: { label: 'Provizyon alındı', tone: 'info' },
  DECLINED: { label: 'Reddedildi', tone: 'danger' },
  FAILED: { label: 'Başarısız', tone: 'danger' },
  VOIDED: { label: 'İptal edildi', tone: 'neutral' },
  CAPTURED: { label: 'Tahsil edildi', tone: 'success' },
  PARTIALLY_REFUNDED: { label: 'Kısmen iade edildi', tone: 'warning' },
  REFUNDED: { label: 'İade edildi', tone: 'neutral' },
};

export function paymentStatusLabel(status: PaymentStatus): string {
  return PAYMENT_STATUS[status].label;
}

export const PAYMENT_STATUS_OPTIONS =Object.entries(PAYMENT_STATUS).map(([value, { label }]) => ({ value, label }));

const FAILURE_CODES: Record<string, string> = {
  card_declined: 'Kart bankası işlemi reddetti.',
  insufficient_funds: 'Kartta yeterli bakiye yok.',
  invalid_token: 'Kart bilgisi geçersiz.',
  capture_rejected: 'Banka tahsilatı reddetti.',
  bank_no_record: 'İstek bankaya ulaşmadı, para hareketi olmadı.',
  account_closed: 'Alıcı banka hesabı kapalı.',
  unknown_authorization: 'Banka bu provizyonu bulamadı.',
  risk_blocked: 'Ödeme bir risk kuralına takıldı ve bankaya gönderilmedi.',
  '3ds_failed': 'Kart sahibi doğrulaması (3D Secure) tamamlanmadı.',
  iyzico_declined: 'Ödeme iyzico sayfasında tamamlanmadı.',
};

export const RISK_REASONS: Record<string, string> = {
  CARD_VELOCITY: 'Aynı kartla kısa sürede çok fazla deneme',
  CARD_DECLINES: 'Kart bankadan art arda reddedildi',
  AMOUNT_ABOVE: 'Tutar belirlenen sınırın üstünde',
  CARD_BLOCKLISTED: 'Kart engelli kartlar listesinde',
};

export function RiskBadge({ level, reviewStatus }: { level: string | null; reviewStatus: string | null }) {
  if (reviewStatus === 'OPEN') {
    return <Badge label="İncelemede" tone="warning" />;
  }
  if (reviewStatus === 'REJECTED') {
    return <Badge label="İncelemede reddedildi" tone="danger" />;
  }
  if (reviewStatus === 'APPROVED') {
    return <Badge label="İncelendi, onaylandı" tone="success" />;
  }
  if (level === 'BLOCKED') {
    return <Badge label="Risk nedeniyle engellendi" tone="danger" />;
  }
  return null;
}

export function failureMessage(code: string | null): string {
  return (code && FAILURE_CODES[code]) || 'İşlem tamamlanamadı.';
}

function Badge({ label, tone }: { label: string; tone: Tone }) {
  return <span className={`badge badge-${tone}`}>{label}</span>;
}

export function PaymentStatusBadge({ status }: { status: PaymentStatus }) {
  return <Badge {...PAYMENT_STATUS[status]} />;
}

export function OperationStatusBadge({ status }: { status: OperationStatus }) {
  const map: Record<OperationStatus, { label: string; tone: Tone }> = {
    PENDING: { label: 'Bekliyor', tone: 'warning' },
    SUCCEEDED: { label: 'Başarılı', tone: 'success' },
    FAILED: { label: 'Başarısız', tone: 'danger' },
  };
  return <Badge {...map[status]} />;
}

export function PayoutStatusBadge({ status }: { status: Payout['status'] }) {
  const map: Record<Payout['status'], { label: string; tone: Tone }> = {
    PENDING: { label: 'Yolda', tone: 'warning' },
    PAID: { label: 'Ödendi', tone: 'success' },
    FAILED: { label: 'Başarısız', tone: 'danger' },
  };
  return <Badge {...map[status]} />;
}

export function DeliveryStatusBadge({ status }: { status: WebhookDelivery['status'] }) {
  const map: Record<WebhookDelivery['status'], { label: string; tone: Tone }> = {
    PENDING: { label: 'Sırada', tone: 'warning' },
    DELIVERED: { label: 'Teslim edildi', tone: 'success' },
    FAILED: { label: 'Başarısız', tone: 'danger' },
    CANCELLED: { label: 'İptal edildi', tone: 'neutral' },
  };
  return <Badge {...map[status]} />;
}

export function DisputeStatusBadge({ status }: { status: DisputeStatus }) {
  const map: Record<DisputeStatus, { label: string; tone: Tone }> = {
    NEEDS_RESPONSE: { label: 'Yanıt bekliyor', tone: 'danger' },
    UNDER_REVIEW: { label: 'İnceleniyor', tone: 'warning' },
    WON: { label: 'Kazanıldı', tone: 'success' },
    LOST: { label: 'Kaybedildi', tone: 'neutral' },
  };
  return <Badge {...map[status]} />;
}

export function PaymentLinkStatusBadge({ status }: { status: PaymentLinkStatus }) {
  const map: Record<PaymentLinkStatus, { label: string; tone: Tone }> = {
    OPEN: { label: 'Açık', tone: 'success' },
    SOLD_OUT: { label: 'Kontenjan doldu', tone: 'warning' },
    EXPIRED: { label: 'Süresi doldu', tone: 'neutral' },
    DISABLED: { label: 'Kapatıldı', tone: 'neutral' },
  };
  return <Badge {...map[status]} />;
}

export function SubscriptionStatusBadge({ status }: { status: SubscriptionStatus }) {
  const map: Record<SubscriptionStatus, { label: string; tone: Tone }> = {
    INCOMPLETE: { label: 'İlk ödeme bekleniyor', tone: 'warning' },
    ACTIVE: { label: 'Aktif', tone: 'success' },
    PAST_DUE: { label: 'Ödeme gecikti', tone: 'danger' },
    CANCELED: { label: 'İptal edildi', tone: 'neutral' },
  };
  return <Badge {...map[status]} />;
}

export function InvoiceStatusBadge({ status }: { status: InvoiceStatus }) {
  const map: Record<InvoiceStatus, { label: string; tone: Tone }> = {
    OPEN: { label: 'Ödenmedi', tone: 'warning' },
    PAID: { label: 'Ödendi', tone: 'success' },
    VOID: { label: 'İptal edildi', tone: 'neutral' },
    UNCOLLECTIBLE: { label: 'Tahsil edilemedi', tone: 'danger' },
  };
  return <Badge {...map[status]} />;
}

export function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat('tr-TR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}

export function formatDate(value: string): string {
  return new Intl.DateTimeFormat('tr-TR', { dateStyle: 'medium' }).format(new Date(`${value}T00:00:00`));
}

export function ErrorNote({ message }: { message: string | null }) {
  return message ? <p className="note note-danger" role="alert">{message}</p> : null;
}

/** Placeholder bars in the shape of the content to come, so the page does not jump when it arrives. */
export function Loading({ rows = 3 }: { rows?: number }) {
  return (
    <div className="skeleton" role="status">
      <span className="visually-hidden">Yükleniyor…</span>
      {Array.from({ length: rows }, (_, i) => <span key={i} className="skeleton-line" aria-hidden="true" />)}
    </div>
  );
}

/** Names the browser tab after the page, so several open Kasa tabs can be told apart. */
export function usePageTitle(title: string | null | undefined) {
  useEffect(() => {
    if (title) {
      document.title = `${title} · Kasa`;
    }
  }, [title]);
}

export function Brand() {
  return (
    <div className="brand">
      <svg className="brand-mark" viewBox="0 0 32 32" aria-hidden="true">
        <rect width="32" height="32" rx="8" />
        <rect x="7" y="10" width="18" height="13" rx="2.5" className="brand-mark-line" />
        <path d="M7 14.5h18M11 19h4" className="brand-mark-line" />
      </svg>
      Kasa
    </div>
  );
}

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <strong>{title}</strong>
      {children && <p className="muted">{children}</p>}
    </div>
  );
}

export function PageHeader({ title, description, actions }: { title: string; description?: string; actions?: ReactNode }) {
  usePageTitle(title);
  return (
    <header className="page-header">
      <div>
        <h1>{title}</h1>
        {description && <p className="muted">{description}</p>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </header>
  );
}

export function CopyButton({ value, label = 'Kopyala' }: { value: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      setCopied(false);
    }
  };
  return (
    <button type="button" className="button button-small" onClick={copy}>
      {copied ? 'Kopyalandı' : label}
    </button>
  );
}

/** Shows a secret that cannot be retrieved later, and says so. */
export function OneTimeSecret({ title, value }: { title: string; value: string }) {
  return (
    <div className="note note-warning">
      <strong>{title}</strong>
      <p>Bu değeri şimdi kaydedin. Güvenlik nedeniyle bir daha gösterilmeyecek.</p>
      <div className="secret-row">
        <code className="secret">{value}</code>
        <CopyButton value={value} />
      </div>
    </div>
  );
}

interface FormDialogProps {
  title: string;
  submitLabel: string;
  danger?: boolean;
  onClose: () => void;
  onSubmit: () => Promise<void>;
  children: ReactNode;
}

/** A native dialog, which gives focus trapping, Esc to close and screen reader support without a library. */
export function FormDialog({ title, submitLabel, danger, onClose, onSubmit, children }: FormDialogProps) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    dialog.current?.showModal();
  }, []);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await onSubmit();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Beklenmeyen bir hata oluştu.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <dialog ref={dialog} className="dialog" onClose={onClose}>
      <form onSubmit={submit}>
        <h2>{title}</h2>
        {children}
        <ErrorNote message={error} />
        <div className="dialog-actions">
          <button type="button" className="button" onClick={onClose} disabled={busy}>Vazgeç</button>
          <button type="submit" className={danger ? 'button button-danger' : 'button button-primary'} disabled={busy}>
            {busy ? 'İşleniyor…' : submitLabel}
          </button>
        </div>
      </form>
    </dialog>
  );
}
