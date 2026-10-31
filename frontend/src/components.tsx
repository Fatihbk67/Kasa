import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import type { OperationStatus, PaymentStatus, Payout, WebhookDelivery } from './types';

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

export const PAYMENT_STATUS_OPTIONS = Object.entries(PAYMENT_STATUS).map(([value, { label }]) => ({ value, label }));

const FAILURE_CODES: Record<string, string> = {
  card_declined: 'Kart bankası işlemi reddetti.',
  insufficient_funds: 'Kartta yeterli bakiye yok.',
  invalid_token: 'Kart bilgisi geçersiz.',
  capture_rejected: 'Banka tahsilatı reddetti.',
  bank_no_record: 'İstek bankaya ulaşmadı, para hareketi olmadı.',
  account_closed: 'Alıcı banka hesabı kapalı.',
  unknown_authorization: 'Banka bu provizyonu bulamadı.',
};

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

export function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat('tr-TR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}

export function formatDate(value: string): string {
  return new Intl.DateTimeFormat('tr-TR', { dateStyle: 'medium' }).format(new Date(`${value}T00:00:00`));
}

export function ErrorNote({ message }: { message: string | null }) {
  return message ? <p className="note note-danger" role="alert">{message}</p> : null;
}

export function Loading() {
  return <p className="muted" role="status">Yükleniyor…</p>;
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
