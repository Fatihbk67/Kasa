import { ApiError } from './api';

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

/** Separate from the merchant client on purpose: an admin token never travels with a merchant session. */
export async function adminApi<T>(path: string, method: 'GET' | 'POST' = 'GET', token = getAdminToken()): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api/v1/admin${path}`, { method, headers: { 'X-Admin-Token': token ?? '' } });
  } catch {
    throw new ApiError(0, 'Sunucuya ulaşılamadı. Bağlantınızı kontrol edip yeniden deneyin.');
  }
  if (response.status === 401) {
    throw new ApiError(401, 'Yönetici anahtarı geçersiz.');
  }
  if (!response.ok) {
    throw new ApiError(response.status, 'İşlem tamamlanamadı.');
  }
  return (await response.json()) as T;
}
