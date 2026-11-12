import { useEffect, useState } from 'react';
import { api, message } from './api';

/** GETs `url` while `enabled` (and again when it turns on): the body, or the error it failed with. */
export function useFetched<T>(url: string, enabled = true): { data: T | null; error: string } {
  const [state, setState] = useState<{ data: T | null; error: string }>({ data: null, error: '' });
  useEffect(() => {
    if (!enabled) return;
    let current = true;
    api<T>(url).then(
      data => { if (current) setState({ data, error: '' }); },
      ex => { if (current) setState({ data: null, error: message(ex) }); });
    return () => { current = false; };
  }, [url, enabled]);
  return state;
}
