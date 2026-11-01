import { createContext, useContext, type ReactNode } from 'react';
import { api } from './api';
import type { Me, Role } from './types';
import { useFetch } from './useFetch';

interface MeState {
  me: Me | null;
  reload: () => void;
}

const MeContext = createContext<MeState>({ me: null, reload: () => {} });

export function MeProvider({ children }: { children: ReactNode }) {
  const me = useFetch(() => api<Me>('/me'), []);
  return <MeContext.Provider value={{ me: me.data, reload: me.reload }}>{children}</MeContext.Provider>;
}

export function useMe(): MeState {
  return useContext(MeContext);
}

export const ROLE_LABELS: Record<Role, string> = {
  OWNER: 'Sahip',
  ADMIN: 'Yönetici',
  VIEWER: 'Görüntüleyici',
};

/**
 * Hides actions a role cannot use, purely as a convenience. The server enforces every permission on its
 * own, so changing anything in the browser gains nothing.
 */
export function canOperate(me: Me | null): boolean {
  return me?.role === 'OWNER' || me?.role === 'ADMIN';
}

export function isOwner(me: Me | null): boolean {
  return me?.role === 'OWNER';
}
