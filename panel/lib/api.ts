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

const downloads = new Map<string, Promise<void>>();

/**
 * Downloads the server's own copy of the plugin (AIBuilder.jar with its connection inside). The promise is kept, so
 * the setup page can show how a download started on the dashboard went.
 */
export function downloadPlugin(serverId: string): Promise<void> {
  const p = (async () => {
    const res = await fetch(`${serverApi(serverId)}/plugin`, { method: 'POST', credentials: 'same-origin' });
    if (res.status === 401) {
      signIn();
      throw new Error('sign in first');
    }
    if (!res.ok) {
      const body = await res.json().catch(() => null);
      throw new Error(body?.error || `${res.status} ${res.statusText}`);
    }
    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement('a');
    a.href = url;
    a.download = 'AIBuilder.jar';
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  })();
  downloads.set(serverId, p);
  return p;
}

/** The download started for this server since the page loaded, if any. */
export const pluginDownload = (serverId: string) => downloads.get(serverId);
