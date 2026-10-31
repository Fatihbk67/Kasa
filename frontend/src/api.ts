const TOKEN_KEY = 'kasa.accessToken';

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

const MESSAGES: Record<number, string> = {
  400: 'Girilen bilgiler geçerli değil.',
  401: 'Oturumunuz sona erdi, lütfen yeniden giriş yapın.',
  403: 'Bu işlem için yetkiniz yok.',
  404: 'Kayıt bulunamadı.',
  409: 'Bu işlem şu anki durumda yapılamıyor.',
  410: 'Bu bağlantının süresi doldu.',
  422: 'Girilen tutar kullanılabilir tutarı aşıyor.',
  429: 'Çok fazla deneme yapıldı. Lütfen bir dakika sonra yeniden deneyin.',
};

let onSessionExpired: () => void = () => {};

export function setSessionExpiredHandler(handler: () => void) {
  onSessionExpired = handler;
}

// sessionStorage ends with the tab, which limits how long a stolen token stays useful on a shared computer.
export function getToken(): string | null {
  try {
    return sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setToken(token: string | null) {
  try {
    if (token) {
      sessionStorage.setItem(TOKEN_KEY, token);
    } else {
      sessionStorage.removeItem(TOKEN_KEY);
    }
  } catch {
    // Storage can be blocked. The session then lasts only until the page reloads.
  }
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'DELETE';
  body?: unknown;
  idempotencyKey?: string;
}

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = {};
  const token = getToken();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (options.idempotencyKey) {
    headers['Idempotency-Key'] = options.idempotencyKey;
  }

  let response: Response;
  try {
    response = await fetch(`/api/v1${path}`, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    });
  } catch {
    throw new ApiError(0, 'Sunucuya ulaşılamadı. Bağlantınızı kontrol edip yeniden deneyin.');
  }

  if (response.status === 401 && token) {
    onSessionExpired();
  }
  if (!response.ok) {
    throw new ApiError(response.status, MESSAGES[response.status] ?? 'Beklenmeyen bir hata oluştu.');
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** Created once per user intent, so a double click or a retried request cannot move money twice. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
