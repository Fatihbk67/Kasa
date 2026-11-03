import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';

type Notify = (message: string) => void;

const ToastContext = createContext<Notify>(() => {});

const VISIBLE_MS = 4000;

/**
 * A short confirmation after an action succeeds. It is announced to screen readers through a live region,
 * and it never carries the only copy of anything the person needs: errors stay next to the form that caused them.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [message, setMessage] = useState<string | null>(null);
  const timer = useRef<number | undefined>(undefined);

  const notify = useCallback<Notify>((text) => {
    window.clearTimeout(timer.current);
    setMessage(text);
    timer.current = window.setTimeout(() => setMessage(null), VISIBLE_MS);
  }, []);

  useEffect(() => () => window.clearTimeout(timer.current), []);

  return (
    <ToastContext.Provider value={notify}>
      {children}
      <div className="toast-region" role="status" aria-live="polite">
        {message && (
          <div className="toast">
            <span>{message}</span>
            <button type="button" className="toast-close" aria-label="Kapat" onClick={() => setMessage(null)}>×</button>
          </div>
        )}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): Notify {
  return useContext(ToastContext);
}
