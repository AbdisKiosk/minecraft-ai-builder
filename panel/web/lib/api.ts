let onUnauthorized = () => {};

/** Called when a request finds the login session gone. */
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler;
}

export async function api<T = any>(path: string, options: RequestInit = {}): Promise<T> {
  const res = await fetch(path, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    credentials: 'same-origin',
  });
  if (res.status === 401) {
    onUnauthorized();
    throw new Error('log in first');
  }
  const text = await res.text();
  const body = text ? JSON.parse(text) : null;
  if (!res.ok) throw new Error(body && body.error ? body.error : res.status + ' ' + res.statusText);
  return body;
}

export function message(ex: unknown): string {
  return ex instanceof Error ? ex.message : String(ex);
}

export const serverApi = (id: string) => `/api/servers/${encodeURIComponent(id)}`;
