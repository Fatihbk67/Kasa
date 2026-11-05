import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { locale, t, tk } from './i18n';
import type { DisputeStatus, InvoiceStatus, OperationStatus, PaymentLinkStatus, PaymentStatus, Payout, SubscriptionStatus, WebhookDelivery } from './types';

type Tone = 'success' | 'warning' | 'danger' | 'neutral' | 'info';

// Labels in the constants below are Turkish keys, translated where they are shown.
const PAYMENT_STATUS: Record<PaymentStatus, { label: string; tone: Tone }> = {
  PENDING: { label: tk('Bankadan yanıt bekleniyor'), tone: 'warning' },
  AUTHORIZED: { label: tk('Provizyon alındı'), tone: 'info' },
  DECLINED: { label: tk('Reddedildi'), tone: 'danger' },
  FAILED: { label: tk('Başarısız'), tone: 'danger' },
  VOIDED: { label: tk('İptal edildi'), tone: 'neutral' },
  CAPTURED: { label: tk('Tahsil edildi'), tone: 'success' },
  PARTIALLY_REFUNDED: { label: tk('Kısmen iade edildi'), tone: 'warning' },
  REFUNDED: { label: tk('İade edildi'), tone: 'neutral' },
};

export function paymentStatusLabel(status: PaymentStatus): string {
  return t(PAYMENT_STATUS[status].label);
}

/** For a status filter: the labels are translated when the options are asked for. */
export function paymentStatusOptions(): { value: string; label: string }[] {
  return Object.entries(PAYMENT_STATUS).map(([value, { label }]) => ({ value, label: t(label) }));
}

const FAILURE_CODES: Record<string, string> = {
  card_declined: tk('Kart bankası işlemi reddetti.'),
  insufficient_funds: tk('Kartta yeterli bakiye yok.'),
  invalid_token: tk('Kart bilgisi geçersiz.'),
  capture_rejected: tk('Banka tahsilatı reddetti.'),
  bank_no_record: tk('İstek bankaya ulaşmadı, para hareketi olmadı.'),
  account_closed: tk('Alıcı banka hesabı kapalı.'),
  unknown_authorization: tk('Banka bu provizyonu bulamadı.'),
  risk_blocked: tk('Ödeme bir risk kuralına takıldı ve bankaya gönderilmedi.'),
  '3ds_failed': tk('Kart sahibi doğrulaması (3D Secure) tamamlanmadı.'),
  iyzico_declined: tk('Ödeme iyzico sayfasında tamamlanmadı.'),
};

const RISK_REASONS: Record<string, string> = {
  CARD_VELOCITY: tk('Aynı kartla kısa sürede çok fazla deneme'),
  CARD_DECLINES: tk('Kart bankadan art arda reddedildi'),
  AMOUNT_ABOVE: tk('Tutar belirlenen sınırın üstünde'),
  CARD_BLOCKLISTED: tk('Kart engelli kartlar listesinde'),
};

export function riskReason(code: string): string {
  return RISK_REASONS[code] ? t(RISK_REASONS[code]) : code;
}

export function RiskBadge({ level, reviewStatus }: { level: string | null; reviewStatus: string | null }) {
  if (reviewStatus === 'OPEN') {
    return <Badge label={tk('İncelemede')} tone="warning" />;
  }
  if (reviewStatus === 'REJECTED') {
    return <Badge label={tk('İncelemede reddedildi')} tone="danger" />;
  }
  if (reviewStatus === 'APPROVED') {
    return <Badge label={tk('İncelendi, onaylandı')} tone="success" />;
  }
  if (level === 'BLOCKED') {
    return <Badge label={tk('Risk nedeniyle engellendi')} tone="danger" />;
  }
  return null;
}

export function failureMessage(code: string | null): string {
  return t((code && FAILURE_CODES[code]) || 'İşlem tamamlanamadı.');
}

/** Takes a Turkish key and shows it in the chosen language. */
function Badge({ label, tone }: { label: string; tone: Tone }) {
  return <span className={`badge badge-${tone}`}>{t(label)}</span>;
}

export function PaymentStatusBadge({ status }: { status: PaymentStatus }) {
  return <Badge {...PAYMENT_STATUS[status]} />;
}

export function OperationStatusBadge({ status }: { status: OperationStatus }) {
  const map: Record<OperationStatus, { label: string; tone: Tone }> = {
    PENDING: { label: tk('Bekliyor'), tone: 'warning' },
    SUCCEEDED: { label: tk('Başarılı'), tone: 'success' },
    FAILED: { label: tk('Başarısız'), tone: 'danger' },
  };
  return <Badge {...map[status]} />;
}

export function PayoutStatusBadge({ status }: { status: Payout['status'] }) {
  const map: Record<Payout['status'], { label: string; tone: Tone }> = {
    PENDING: { label: tk('Yolda'), tone: 'warning' },
    PAID: { label: tk('Ödendi'), tone: 'success' },
    FAILED: { label: tk('Başarısız'), tone: 'danger' },
  };
  return <Badge {...map[status]} />;
}

export function DeliveryStatusBadge({ status }: { status: WebhookDelivery['status'] }) {
  const map: Record<WebhookDelivery['status'], { label: string; tone: Tone }> = {
    PENDING: { label: tk('Sırada'), tone: 'warning' },
    DELIVERED: { label: tk('Teslim edildi'), tone: 'success' },
    FAILED: { label: tk('Başarısız'), tone: 'danger' },
    CANCELLED: { label: tk('İptal edildi'), tone: 'neutral' },
  };
  return <Badge {...map[status]} />;
}

export function DisputeStatusBadge({ status }: { status: DisputeStatus }) {
  const map: Record<DisputeStatus, { label: string; tone: Tone }> = {
    NEEDS_RESPONSE: { label: tk('Yanıt bekliyor'), tone: 'danger' },
    UNDER_REVIEW: { label: tk('İnceleniyor'), tone: 'warning' },
    WON: { label: tk('Kazanıldı'), tone: 'success' },
    LOST: { label: tk('Kaybedildi'), tone: 'neutral' },
  };
  return <Badge {...map[status]} />;
}

export function PaymentLinkStatusBadge({ status }: { status: PaymentLinkStatus }) {
  const map: Record<PaymentLinkStatus, { label: string; tone: Tone }> = {
    OPEN: { label: tk('Açık'), tone: 'success' },
    SOLD_OUT: { label: tk('Kontenjan doldu'), tone: 'warning' },
    EXPIRED: { label: tk('Süresi doldu'), tone: 'neutral' },
    DISABLED: { label: tk('Kapatıldı'), tone: 'neutral' },
  };
  return <Badge {...map[status]} />;
}

export function SubscriptionStatusBadge({ status }: { status: SubscriptionStatus }) {
  const map: Record<SubscriptionStatus, { label: string; tone: Tone }> = {
    INCOMPLETE: { label: tk('İlk ödeme bekleniyor'), tone: 'warning' },
    ACTIVE: { label: tk('Aktif'), tone: 'success' },
    PAST_DUE: { label: tk('Ödeme gecikti'), tone: 'danger' },
    CANCELED: { label: tk('İptal edildi'), tone: 'neutral' },
  };
  return <Badge {...map[status]} />;
}

export function InvoiceStatusBadge({ status }: { status: InvoiceStatus }) {
  const map: Record<InvoiceStatus, { label: string; tone: Tone }> = {
    OPEN: { label: tk('Ödenmedi'), tone: 'warning' },
    PAID: { label: tk('Ödendi'), tone: 'success' },
    VOID: { label: tk('İptal edildi'), tone: 'neutral' },
    UNCOLLECTIBLE: { label: tk('Tahsil edilemedi'), tone: 'danger' },
  };
  return <Badge {...map[status]} />;
}

export function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat(locale(), { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}

export function formatDate(value: string): string {
  return new Intl.DateTimeFormat(locale(), { dateStyle: 'medium' }).format(new Date(`${value}T00:00:00`));
}

export function ErrorNote({ message }: { message: string | null }) {
  return message ? <p className="note note-danger" role="alert">{message}</p> : null;
}

/** Placeholder bars in the shape of the content to come, so the page does not jump when it arrives. */
export function Loading({ rows = 3 }: { rows?: number }) {
  return (
    <div className="skeleton" role="status">
      <span className="visually-hidden">{t('Yükleniyor…')}</span>
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

export function CopyButton({ value, label }: { value: string; label?: string }) {
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
      {copied ? t('Kopyalandı') : label ?? t('Kopyala')}
    </button>
  );
}

/** Shows a secret that cannot be retrieved later, and says so. */
export function OneTimeSecret({ title, value }: { title: string; value: string }) {
  return (
    <div className="note note-warning">
      <strong>{title}</strong>
      <p>{t('Bu değeri şimdi kaydedin. Güvenlik nedeniyle bir daha gösterilmeyecek.')}</p>
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
      setError(e instanceof Error ? e.message : t('Beklenmeyen bir hata oluştu.'));
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
          <button type="button" className="button" onClick={onClose} disabled={busy}>{t('Vazgeç')}</button>
          <button type="submit" className={danger ? 'button button-danger' : 'button button-primary'} disabled={busy}>
            {busy ? t('İşleniyor…') : submitLabel}
          </button>
        </div>
      </form>
    </dialog>
  );
}
