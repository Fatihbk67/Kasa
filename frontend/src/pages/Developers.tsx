import { useState } from 'react';
import { api, ApiError } from '../api';
import { DeliveryStatusBadge, EmptyState, ErrorNote, FormDialog, formatDateTime, Loading, OneTimeSecret, PageHeader } from '../components';
import type { ApiKey, WebhookDelivery, WebhookEndpoint } from '../types';
import { useFetch } from '../useFetch';

function ApiKeys() {
  const keys = useFetch(() => api<ApiKey[]>('/api-keys'), []);
  const [creating, setCreating] = useState(false);
  const [revoking, setRevoking] = useState<ApiKey | null>(null);
  const [name, setName] = useState('');
  const [created, setCreated] = useState<string | null>(null);

  const create = async () => {
    const result = await api<{ key: string }>('/api-keys', { method: 'POST', body: { name } });
    setCreated(result.key);
    setCreating(false);
    setName('');
    keys.reload();
  };

  const revoke = async () => {
    await api(`/api-keys/${revoking!.id}`, { method: 'DELETE' });
    setRevoking(null);
    keys.reload();
  };

  return (
    <section>
      <div className="section-header">
        <div>
          <h2>API anahtarları</h2>
          <p className="muted">Sunucunuzun Kasa'ya bağlanması için kullanılır. Tarayıcıya veya mobil uygulamaya koymayın.</p>
        </div>
        <button type="button" className="button" onClick={() => setCreating(true)}>Anahtar oluştur</button>
      </div>
      {created && <OneTimeSecret title="API anahtarınız" value={created} />}
      <ErrorNote message={keys.error} />
      {keys.loading ? <Loading /> : keys.data && keys.data.length > 0 ? (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Ad</th>
                <th scope="col">Önek</th>
                <th scope="col" className="hide-narrow">Oluşturulma</th>
                <th scope="col"><span className="visually-hidden">İşlemler</span></th>
              </tr>
            </thead>
            <tbody>
              {keys.data.map((key) => (
                <tr key={key.id}>
                  <td>{key.name}</td>
                  <td><code>{key.prefix}</code></td>
                  <td className="hide-narrow muted">{formatDateTime(key.createdAt)}</td>
                  <td className="numeric">
                    {key.revokedAt
                      ? <span className="muted">İptal edildi</span>
                      : <button type="button" className="button button-small" onClick={() => setRevoking(key)}>İptal et</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : !keys.error && <div className="card"><EmptyState title="Henüz API anahtarı yok" /></div>}

      {creating && (
        <FormDialog title="API anahtarı oluştur" submitLabel="Oluştur" onClose={() => setCreating(false)} onSubmit={create}>
          <label className="field">
            Anahtar adı
            <input required autoFocus maxLength={100} placeholder="Canlı sunucu" value={name} onChange={(e) => setName(e.target.value)} />
          </label>
        </FormDialog>
      )}
      {revoking && (
        <FormDialog title="Anahtarı iptal et" submitLabel="İptal et" danger onClose={() => setRevoking(null)} onSubmit={revoke}>
          <p><strong>{revoking.name}</strong> anahtarını kullanan sunucular hemen erişimini kaybeder. Bu işlem geri alınamaz.</p>
        </FormDialog>
      )}
    </section>
  );
}

function Deliveries({ endpoint }: { endpoint: WebhookEndpoint }) {
  const deliveries = useFetch(() => api<WebhookDelivery[]>(`/webhook-endpoints/${endpoint.id}/deliveries`), [endpoint.id]);
  const [retryError, setRetryError] = useState<string | null>(null);

  const retry = async (delivery: WebhookDelivery) => {
    setRetryError(null);
    try {
      await api(`/webhook-endpoints/deliveries/${delivery.id}/retry`, { method: 'POST' });
      deliveries.reload();
    } catch (e) {
      setRetryError((e as Error).message);
    }
  };

  if (deliveries.loading) {
    return <Loading />;
  }
  if (deliveries.error) {
    return <ErrorNote message={deliveries.error} />;
  }
  if (!deliveries.data || deliveries.data.length === 0) {
    return <p className="muted">Henüz teslimat yok. Yeni ödeme olayları burada görünür.</p>;
  }
  return (
    <>
      <ErrorNote message={retryError} />
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col">Olay</th>
              <th scope="col">Durum</th>
              <th scope="col" className="numeric">Deneme</th>
              <th scope="col"><span className="visually-hidden">İşlemler</span></th>
            </tr>
          </thead>
          <tbody>
            {deliveries.data.map((delivery) => (
              <tr key={delivery.id}>
                <td><code>{delivery.eventType}</code></td>
                <td><DeliveryStatusBadge status={delivery.status} /></td>
                <td className="numeric">{delivery.attempts}</td>
                <td className="numeric">
                  {delivery.status === 'FAILED' && !endpoint.disabledAt && (
                    <button type="button" className="button button-small" onClick={() => retry(delivery)}>Yeniden dene</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

function Webhooks() {
  const endpoints = useFetch(() => api<WebhookEndpoint[]>('/webhook-endpoints'), []);
  const [creating, setCreating] = useState(false);
  const [disabling, setDisabling] = useState<WebhookEndpoint | null>(null);
  const [url, setUrl] = useState('');
  const [secret, setSecret] = useState<string | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);

  const create = async () => {
    try {
      const result = await api<{ secret: string }>('/webhook-endpoints', { method: 'POST', body: { url } });
      setSecret(result.secret);
      setCreating(false);
      setUrl('');
      endpoints.reload();
    } catch (e) {
      if (e instanceof ApiError && e.status === 422) {
        throw new Error('Bu adres kullanılamaz. Herkese açık bir https adresi girin.');
      }
      throw e;
    }
  };

  const disable = async () => {
    await api(`/webhook-endpoints/${disabling!.id}`, { method: 'DELETE' });
    setDisabling(null);
    endpoints.reload();
  };

  return (
    <section>
      <div className="section-header">
        <div>
          <h2>Webhook'lar</h2>
          <p className="muted">Ödeme sonuçlandığında sunucunuza imzalı bildirim gönderilir.</p>
        </div>
        <button type="button" className="button" onClick={() => setCreating(true)}>Adres ekle</button>
      </div>
      {secret && <OneTimeSecret title="İmza anahtarınız" value={secret} />}
      <ErrorNote message={endpoints.error} />
      {endpoints.loading ? <Loading /> : endpoints.data && endpoints.data.length > 0 ? (
        <div className="stack">
          {endpoints.data.map((endpoint) => (
            <div className="card" key={endpoint.id}>
              <div className="endpoint-row">
                <code className="endpoint-url">{endpoint.url}</code>
                <div className="endpoint-actions">
                  {endpoint.disabledAt && <span className="muted">Devre dışı</span>}
                  <button type="button" className="button button-small" aria-expanded={openId === endpoint.id} onClick={() => setOpenId(openId === endpoint.id ? null : endpoint.id)}>
                    {openId === endpoint.id ? 'Teslimatları gizle' : 'Teslimatlar'}
                  </button>
                  {!endpoint.disabledAt && (
                    <button type="button" className="button button-small" onClick={() => setDisabling(endpoint)}>Devre dışı bırak</button>
                  )}
                </div>
              </div>
              {openId === endpoint.id && <div className="endpoint-deliveries"><Deliveries endpoint={endpoint} /></div>}
            </div>
          ))}
        </div>
      ) : !endpoints.error && <div className="card"><EmptyState title="Henüz webhook adresi yok" /></div>}

      {creating && (
        <FormDialog title="Webhook adresi ekle" submitLabel="Ekle" onClose={() => setCreating(false)} onSubmit={create}>
          <label className="field">
            Adres
            <input type="url" required autoFocus maxLength={2000} placeholder="https://ornek.com/kasa-webhook" value={url} onChange={(e) => setUrl(e.target.value)} />
          </label>
        </FormDialog>
      )}
      {disabling && (
        <FormDialog title="Adresi devre dışı bırak" submitLabel="Devre dışı bırak" danger onClose={() => setDisabling(null)} onSubmit={disable}>
          <p>Bekleyen teslimatlar iptal edilir ve bu adrese yeni bildirim gönderilmez.</p>
        </FormDialog>
      )}
    </section>
  );
}

export function Developers() {
  return (
    <>
      <PageHeader title="Geliştiriciler" />
      <div className="sections">
        <ApiKeys />
        <Webhooks />
      </div>
    </>
  );
}
