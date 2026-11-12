/** Sends the browser to Auth0 when the session is gone, and back to this page afterwards. */
export function signIn() {
  const back = window.location.pathname + window.location.hash;
  window.location.href = '/auth/login?returnTo=' + encodeURIComponent(back);
}

export async function api<T = any>(path: string, options: RequestInit = {}): Promise<T> {
  const res = await fetch(path, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    credentials: 'same-origin',
  });
  if (res.status === 401) {
    signIn();
    throw new Error('sign in first');
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
