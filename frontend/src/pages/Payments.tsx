import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api, downloadFile } from '../api';
import { EmptyState, ErrorNote, Loading, PageHeader, PAYMENT_STATUS_OPTIONS } from '../components';
import { parseAmount } from '../money';
import type { Payment, PaymentPage } from '../types';
import { useFetch } from '../useFetch';
import { PaymentTable } from './PaymentTable';

const PAGE_SIZE = 20;
const FILTER_KEYS = ['q', 'status', 'from', 'to', 'min', 'max'] as const;

type FilterKey = (typeof FILTER_KEYS)[number];
type Filters = Record<FilterKey, string>;

/** Amounts stay in the URL as typed, so a shared or reloaded page shows the same form. */
function toApiQuery(filters: Filters): string {
  const query = new URLSearchParams();
  (['q', 'status', 'from', 'to'] as const).forEach((key) => {
    if (filters[key]) {
      query.set(key, filters[key]);
    }
  });
  const min = filters.min ? parseAmount(filters.min) : null;
  const max = filters.max ? parseAmount(filters.max) : null;
  if (min !== null) {
    query.set('minAmount', String(min));
  }
  if (max !== null) {
    query.set('maxAmount', String(max));
  }
  return query.toString();
}

function readFilters(params: URLSearchParams): Filters {
  return Object.fromEntries(FILTER_KEYS.map((key) => [key, params.get(key) ?? ''])) as Filters;
}

export function Payments() {
  const [params, setParams] = useSearchParams();
  const applied = useMemo(() => readFilters(params), [params]);
  const [draft, setDraft] = useState<Filters>(applied);
  const [formError, setFormError] = useState<string | null>(null);
  const apiQuery = toApiQuery(applied);
  const firstPage = useFetch(() => api<PaymentPage>(`/payments?limit=${PAGE_SIZE}&${apiQuery}`), [apiQuery]);
  const [items, setItems] = useState<Payment[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreError, setMoreError] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);
  const [exportNote, setExportNote] = useState<string | null>(null);
  const [exportError, setExportError] = useState<string | null>(null);
  const hasFilters = FILTER_KEYS.some((key) => applied[key]);

  useEffect(() => setDraft(applied), [applied]);

  useEffect(() => {
    if (firstPage.data) {
      setItems(firstPage.data.items);
      setCursor(firstPage.data.nextCursor);
    }
  }, [firstPage.data]);

  const apply = (event: FormEvent) => {
    event.preventDefault();
    const invalidAmount = [draft.min, draft.max].some((value) => value && parseAmount(value) === null);
    if (invalidAmount) {
      setFormError('Tutarları 149,90 gibi yazın.');
      return;
    }
    const min = draft.min ? parseAmount(draft.min)! : null;
    const max = draft.max ? parseAmount(draft.max)! : null;
    if (min !== null && max !== null && min > max) {
      setFormError('En düşük tutar en yüksek tutardan büyük olamaz.');
      return;
    }
    if (draft.from && draft.to && draft.from > draft.to) {
      setFormError('Başlangıç tarihi bitiş tarihinden sonra olamaz.');
      return;
    }
    setFormError(null);
    setExportNote(null);
    const next = Object.fromEntries(Object.entries(draft).filter(([, value]) => value.trim()));
    setParams(next);
  };

  const clear = () => {
    setFormError(null);
    setExportNote(null);
    setParams({});
  };

  const update = (key: FilterKey, value: string) => setDraft((current) => ({ ...current, [key]: value }));

  const loadMore = async () => {
    setLoadingMore(true);
    setMoreError(null);
    try {
      const page = await api<PaymentPage>(`/payments?limit=${PAGE_SIZE}&${apiQuery}&before=${cursor}`);
      setItems((current) => [...current, ...page.items]);
      setCursor(page.nextCursor);
    } catch (e) {
      setMoreError((e as Error).message);
    } finally {
      setLoadingMore(false);
    }
  };

  const exportCsv = async () => {
    setExporting(true);
    setExportError(null);
    setExportNote(null);
    try {
      const { truncated } = await downloadFile(`/payments/export?${apiQuery}`, 'kasa-odemeler.csv');
      if (truncated) {
        setExportNote('Dosya yalnızca en yeni 10.000 ödemeyi içeriyor. Tümünü almak için tarih aralığını daraltıp birkaç parça halinde indirin.');
      }
    } catch (e) {
      setExportError((e as Error).message);
    } finally {
      setExporting(false);
    }
  };

  return (
    <>
      <PageHeader
        title="Ödemeler"
        actions={<button type="button" className="button" onClick={exportCsv} disabled={exporting}>{exporting ? 'Hazırlanıyor…' : 'CSV indir'}</button>}
      />
      <ErrorNote message={exportError} />
      {exportNote && <p className="note note-warning" role="status">{exportNote}</p>}

      <form className="card filters" onSubmit={apply} aria-label="Ödeme filtreleri">
        <label className="field filters-search">
          Ara
          <input type="search" maxLength={200} placeholder="Açıklama veya ödeme kimliği" value={draft.q} onChange={(e) => update('q', e.target.value)} />
        </label>
        <label className="field">
          Durum
          <select value={draft.status} onChange={(e) => update('status', e.target.value)}>
            <option value="">Tümü</option>
            {PAYMENT_STATUS_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </label>
        <label className="field">
          Başlangıç
          <input type="date" value={draft.from} onChange={(e) => update('from', e.target.value)} />
        </label>
        <label className="field">
          Bitiş
          <input type="date" value={draft.to} onChange={(e) => update('to', e.target.value)} />
        </label>
        <label className="field">
          En düşük (₺)
          <input inputMode="decimal" value={draft.min} onChange={(e) => update('min', e.target.value)} />
        </label>
        <label className="field">
          En yüksek (₺)
          <input inputMode="decimal" value={draft.max} onChange={(e) => update('max', e.target.value)} />
        </label>
        <div className="filters-actions">
          <button type="submit" className="button button-primary">Uygula</button>
          {hasFilters && <button type="button" className="button" onClick={clear}>Temizle</button>}
        </div>
        {formError && <div className="filters-error"><ErrorNote message={formError} /></div>}
      </form>

      <ErrorNote message={firstPage.error} />
      {firstPage.loading ? <Loading /> : items.length > 0 ? (
        <>
          <PaymentTable payments={items} />
          <ErrorNote message={moreError} />
          {cursor && (
            <div className="load-more">
              <button type="button" className="button" onClick={loadMore} disabled={loadingMore}>
                {loadingMore ? 'Yükleniyor…' : 'Daha fazla göster'}
              </button>
            </div>
          )}
        </>
      ) : !firstPage.error && (
        <div className="card">
          <EmptyState title={hasFilters ? 'Bu filtrelere uyan ödeme yok' : 'Henüz ödeme yok'} />
        </div>
      )}
    </>
  );
}
