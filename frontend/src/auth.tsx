import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { getToken, setSessionExpiredHandler, setToken } from './api';

interface Auth {
  isSignedIn: boolean;
  signIn: (token: string) => void;
  signOut: () => void;
}

const AuthContext = createContext<Auth | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [isSignedIn, setSignedIn] = useState(() => getToken() !== null);

  const signIn = useCallback((token: string) => {
    setToken(token);
    setSignedIn(true);
  }, []);

  const signOut = useCallback(() => {
    setToken(null);
    setSignedIn(false);
  }, []);

  useEffect(() => setSessionExpiredHandler(signOut), [signOut]);

  const value = useMemo(() => ({ isSignedIn, signIn, signOut }), [isSignedIn, signIn, signOut]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): Auth {
  const auth = useContext(AuthContext);
  if (!auth) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return auth;
}
