'use client';

import { useState } from 'react';
import { api, message } from '../../../lib/api';

export default function AcceptInvite({ token }: { token: string }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const accept = async () => {
    setBusy(true);
    setError('');
    try {
      const r = await api<{ server: string }>(`/api/invites/${encodeURIComponent(token)}`, { method: 'POST' });
      window.location.href = `/dashboard#/s/${encodeURIComponent(r.server)}`;
    } catch (ex) {
      setError(message(ex));
      setBusy(false);
    }
  };
  return (
    <>
      <div className="toolbar"><button onClick={accept} disabled={busy}>{busy ? 'Joining…' : 'Accept invite'}</button></div>
      {error && <p className="error">{error}</p>}
    </>
  );
}
