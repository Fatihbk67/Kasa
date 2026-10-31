import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from './api';

interface FetchState<T> {
  data: T | null;
  error: string | null;
  errorStatus: number | null;
  loading: boolean;
  reload: () => void;
}

/** Loads data and keeps the last good result on screen during a reload, so polling never makes a page flicker. */
export function useFetch<T>(load: () => Promise<T>, deps: readonly unknown[]): FetchState<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [errorStatus, setErrorStatus] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const latestRequest = useRef(0);

  // eslint-disable-next-line react-hooks/exhaustive-deps
  const reload = useCallback(() => {
    const request = ++latestRequest.current;
    load()
      .then((result) => {
        if (request === latestRequest.current) {
          setData(result);
          setError(null);
          setErrorStatus(null);
        }
      })
      .catch((e: unknown) => {
        if (request === latestRequest.current) {
          setError(e instanceof Error ? e.message : 'Beklenmeyen bir hata oluştu.');
          setErrorStatus(e instanceof ApiError ? e.status : null);
        }
      })
      .finally(() => {
        if (request === latestRequest.current) {
          setLoading(false);
        }
      });
  }, deps);

  useEffect(() => {
    setLoading(true);
    reload();
    return () => {
      latestRequest.current++;
    };
  }, [reload]);

  return { data, error, errorStatus, loading, reload };
}

/** Reloads on an interval while a condition holds, such as an operation awaiting the bank's answer. */
export function usePolling(active: boolean, reload: () => void, intervalMs = 3000) {
  useEffect(() => {
    if (!active) {
      return;
    }
    const timer = setInterval(reload, intervalMs);
    return () => clearInterval(timer);
  }, [active, reload, intervalMs]);
}
