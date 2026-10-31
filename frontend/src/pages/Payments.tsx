import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../api';
import { EmptyState, ErrorNote, Loading, PageHeader, PAYMENT_STATUS_OPTIONS } from '../components';
import type { Payment, PaymentPage } from '../types';
import { useFetch } from '../useFetch';
import { PaymentTable } from './PaymentTable';

const PAGE_SIZE = 20;

export function Payments() {
  const [params, setParams] = useSearchParams();
  const status = params.get('status') ?? '';
  const query = `/payments?limit=${PAGE_SIZE}${status ? `&status=${status}` : ''}`;
  const firstPage = useFetch(() => api<PaymentPage>(query), [query]);
  const [items, setItems] = useState<Payment[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreError, setMoreError] = useState<string | null>(null);

  useEffect(() => {
    if (firstPage.data) {
      setItems(firstPage.data.items);
      setCursor(firstPage.data.nextCursor);
    }
  }, [firstPage.data]);

  const loadMore = async () => {
    setLoadingMore(true);
    setMoreError(null);
    try {
      const page = await api<PaymentPage>(`${query}&before=${cursor}`);
      setItems((current) => [...current, ...page.items]);
      setCursor(page.nextCursor);
    } catch (e) {
      setMoreError((e as Error).message);
    } finally {
      setLoadingMore(false);
    }
  };

  return (
    <>
      <PageHeader title="Ödemeler" />
      <div className="toolbar">
        <label className="field field-inline">
          Durum
          <select value={status} onChange={(e) => setParams(e.target.value ? { status: e.target.value } : {})}>
            <option value="">Tümü</option>
            {PAYMENT_STATUS_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </label>
      </div>
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
          <EmptyState title={status ? 'Bu durumda ödeme yok' : 'Henüz ödeme yok'} />
        </div>
      )}
    </>
  );
}
