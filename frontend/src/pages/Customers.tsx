import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import { EmptyState, ErrorNote, FormDialog, formatDateTime, Loading, PageHeader } from '../components';
import { canOperate, useMe } from '../me';
import { formatMoney } from '../money';
import type { Customer, CustomerPage, PaymentPage } from '../types';
import { useFetch } from '../useFetch';
import { PaymentTable } from './PaymentTable';

function friendlyCustomerError(e: unknown): Error {
  return e instanceof ApiError && e.status === 409 ? new Error('Bu e-postayla kayıtlı bir müşteri zaten var.') : e as Error;
}

/** Each currency on its own line: adding lira to dollars would produce a number that means nothing. */
function Totals({ customer }: { customer: Customer }) {
  if (customer.totals.length === 0) {
    return <span className="muted">—</span>;
  }
  return <>{customer.totals.map((total) => <div key={total.currency}>{formatMoney(total.spent, total.currency)}</div>)}</>;
}

function displayName(customer: Customer): string {
  return customer.anonymized ? 'Silinmiş müşteri' : customer.name || customer.email || '—';
}

function CustomerDialog({ customer, onClose, onDone }: { customer?: Customer; onClose: () => void; onDone: (saved: Customer) => void }) {
  const [email, setEmail] = useState(customer?.email ?? '');
  const [name, setName] = useState(customer?.name ?? '');

  const submit = async () => {
    try {
      const saved = await api<Customer>(customer ? `/customers/${customer.id}` : '/customers', {
        method: customer ? 'PUT' : 'POST',
        body: { email: email.trim(), name },
      });
      onDone(saved);
    } catch (e) {
      throw friendlyCustomerError(e);
    }
  };

  return (
    <FormDialog title={customer ? 'Müşteriyi düzenle' : 'Yeni müşteri'} submitLabel="Kaydet" onClose={onClose} onSubmit={submit}>
      <label className="field">
        E-posta
        <input type="email" required autoFocus maxLength={254} value={email} onChange={(e) => setEmail(e.target.value)} />
      </label>
      <label className="field">
        Ad soyad
        <input maxLength={200} value={name} onChange={(e) => setName(e.target.value)} />
      </label>
    </FormDialog>
  );
}

export function Customers() {
  const { me } = useMe();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const applied = params.get('q') ?? '';
  const [draft, setDraft] = useState(applied);
  const query = `/customers?limit=30${applied ? `&q=${encodeURIComponent(applied)}` : ''}`;
  const firstPage = useFetch(() => api<CustomerPage>(query), [query]);
  const [items, setItems] = useState<Customer[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreError, setMoreError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    if (firstPage.data) {
      setItems(firstPage.data.items);
      setCursor(firstPage.data.nextCursor);
    }
  }, [firstPage.data]);

  const search = (event: FormEvent) => {
    event.preventDefault();
    setParams(draft.trim() ? { q: draft.trim() } : {});
  };

  const loadMore = async () => {
    setLoadingMore(true);
    setMoreError(null);
    try {
      const page = await api<CustomerPage>(`${query}&before=${cursor}`);
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
      <PageHeader
        title="Müşteriler"
        description="Ödeme yapan kişiler ve sizde bıraktıkları tutar."
        actions={canOperate(me) && <button type="button" className="button button-primary" onClick={() => setCreating(true)}>Yeni müşteri</button>}
      />
      <form className="toolbar" onSubmit={search} role="search">
        <label className="field field-inline">
          Ara
          <input type="search" placeholder="E-posta veya ad" value={draft} onChange={(e) => setDraft(e.target.value)} />
        </label>
        <button type="submit" className="button">Ara</button>
      </form>
      <ErrorNote message={firstPage.error} />
      {firstPage.loading ? <Loading /> : items.length > 0 ? (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">Müşteri</th>
                  <th scope="col" className="numeric">Harcama</th>
                  <th scope="col" className="hide-narrow">Kayıt</th>
                </tr>
              </thead>
              <tbody>
                {items.map((customer) => (
                  <tr key={customer.id} className="row-link" onClick={() => navigate(`/customers/${customer.id}`)}>
                    <td>
                      <a href={`/customers/${customer.id}`} onClick={(e) => e.preventDefault()}>{displayName(customer)}</a>
                      {customer.name && <span className="hint row-hint">{customer.email}</span>}
                    </td>
                    <td className="numeric"><Totals customer={customer} /></td>
                    <td className="hide-narrow muted">{formatDateTime(customer.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <ErrorNote message={moreError} />
          {cursor !== null && (
            <div className="load-more">
              <button type="button" className="button" onClick={loadMore} disabled={loadingMore}>
                {loadingMore ? 'Yükleniyor…' : 'Daha fazla göster'}
              </button>
            </div>
          )}
        </>
      ) : !firstPage.error && (
        <div className="card">
          <EmptyState title={applied ? 'Aramanızla eşleşen müşteri yok' : 'Henüz müşteri yok'}>
            {!applied && 'Ödeme bağlantısıyla ödeme yapan kişiler burada görünür.'}
          </EmptyState>
        </div>
      )}
      {creating && <CustomerDialog onClose={() => setCreating(false)} onDone={(saved) => navigate(`/customers/${saved.id}`)} />}
    </>
  );
}

export function CustomerDetailPage() {
  const { id } = useParams();
  const { me } = useMe();
  const detail = useFetch(() => api<Customer>(`/customers/${id}`), [id]);
  const payments = useFetch(() => api<PaymentPage>(`/payments?customerId=${id}&limit=20`), [id]);
  const [dialog, setDialog] = useState<'edit' | 'anonymize' | null>(null);

  if (detail.loading && !detail.data) {
    return <Loading />;
  }
  if (!detail.data) {
    return (
      <>
        <ErrorNote message={detail.errorStatus === 404 ? 'Bu müşteri bulunamadı.' : detail.error} />
        <Link to="/customers">Müşterilere dön</Link>
      </>
    );
  }

  const customer = detail.data;
  const canEdit = canOperate(me) && !customer.anonymized;
  const anonymize = async () => {
    try {
      await api(`/customers/${customer.id}`, { method: 'DELETE' });
    } catch (e) {
      throw e instanceof ApiError && e.status === 409
        ? new Error('Açık bir itiraz var. Kanıt olarak gerekebileceği için itiraz sonuçlanmadan silinemez.')
        : e;
    }
    setDialog(null);
    detail.reload();
  };

  return (
    <>
      <p className="breadcrumb"><Link to="/customers">Müşteriler</Link> / Ayrıntı</p>
      <PageHeader
        title={displayName(customer)}
        description={customer.anonymized ? 'Kişisel veriler silindi. Ödeme kayıtları yasal saklama için duruyor.' : customer.email ?? undefined}
        actions={canEdit && (
          <>
            <button type="button" className="button" onClick={() => setDialog('edit')}>Düzenle</button>
            <button type="button" className="button" onClick={() => setDialog('anonymize')}>Kişisel veriyi sil</button>
          </>
        )}
      />
      <section className="card">
        <dl className="details">
          <div><dt>Harcama</dt><dd><Totals customer={customer} /></dd></div>
          <div><dt>Tahsil edilen ödeme</dt><dd>{customer.totals.reduce((sum, total) => sum + total.paymentCount, 0)}</dd></div>
          <div><dt>Kayıt</dt><dd>{formatDateTime(customer.createdAt)}</dd></div>
        </dl>
      </section>

      <div className="section-header"><h2>Ödemeler</h2></div>
      <ErrorNote message={payments.error} />
      {payments.loading && !payments.data ? <Loading /> : payments.data && payments.data.items.length > 0
        ? <PaymentTable payments={payments.data.items} />
        : !payments.error && <div className="card"><EmptyState title="Henüz ödeme yok" /></div>}

      {dialog === 'edit' && (
        <CustomerDialog customer={customer} onClose={() => setDialog(null)} onDone={() => {
          setDialog(null);
          detail.reload();
        }} />
      )}
      {dialog === 'anonymize' && (
        <FormDialog title="Kişisel veriyi sil" submitLabel="Kalıcı olarak sil" danger onClose={() => setDialog(null)} onSubmit={anonymize}>
          <p>Ad ve e-posta geri getirilemeyecek şekilde silinir. Ödeme kayıtları, mali mevzuat gereği kişiyle bağlantısız olarak kalır.</p>
        </FormDialog>
      )}
    </>
  );
}
