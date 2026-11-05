import { ApiError } from './api';
import { t } from './i18n';

const TOKEN_KEY = 'kasa.adminToken';

export function getAdminToken(): string | null {
  try {
    return sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setAdminToken(token: string | null) {
  try {
    if (token) {
      sessionStorage.setItem(TOKEN_KEY, token);
    } else {
      sessionStorage.removeItem(TOKEN_KEY);
    }
  } catch {
    // Storage can be blocked. The operator then signs in again after a reload.
  }
}

const SESSION_PREFIX = 'session:';

/** Marks a signed-in administrator's token, which travels differently from the shared machine key. */
export function asAdminSession(accessToken: string): string {
  return SESSION_PREFIX + accessToken;
}

function credentialHeaders(token: string | null): Record<string, string> {
  return token?.startsWith(SESSION_PREFIX)
    ? { Authorization: `Bearer ${token.slice(SESSION_PREFIX.length)}` }
    : { 'X-Admin-Token': token ?? '' };
}

/** Separate from the merchant client on purpose: an admin token never travels with a merchant session. */
export async function adminApi<T>(path: string, method: 'GET' | 'POST' = 'GET', token = getAdminToken(), body?: unknown): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api/v1/admin${path}`, {
      method,
      headers: { ...credentialHeaders(token), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, t('Sunucuya ulaşılamadı. Bağlantınızı kontrol edip yeniden deneyin.'));
  }
  if (response.status === 401) {
    throw new ApiError(401, t('Oturum geçersiz. Yeniden giriş yapın.'));
  }
  if (!response.ok) {
    throw new ApiError(response.status, t('İşlem tamamlanamadı.'));
  }
  return (response.status === 204 ? undefined : await response.json()) as T;
}
