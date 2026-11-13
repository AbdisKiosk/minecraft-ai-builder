import { useCallback, useEffect, useState } from 'react';
import { api, message, serverApi } from '../lib/api';
import { ago } from '../lib/format';
import { can, type Role, type Server } from '../lib/types';
import { Badge, CopyButton } from './shared';

interface Member { id: number; name: string | null; email: string | null; picture: string | null; role: Role; since: number; you: boolean; manageable: boolean }
interface Invite { id: string; role: Role; by: string | null; created: number; expires: number }

const ROLE_TEXT: Record<Role, string> = {
  owner: 'everything, including deleting the server',
  admin: 'approve drafts, revert edits, invite builders and viewers',
  builder: 'build with an AI assistant and scripts, promote regions',
  viewer: 'see jobs, history and drafts',
};

/** Who can use the server, invite links, and role changes. */
export default function MembersCard({ server }: { server: Server }) {
  const base = serverApi(server.id);
  const [members, setMembers] = useState<Member[] | null>(null);
  const [grantable, setGrantable] = useState<Role[]>([]);
  const [invites, setInvites] = useState<Invite[]>([]);
  const [link, setLink] = useState<{ url: string; role: Role } | null>(null);
  const [role, setRole] = useState<Role>('builder');
  const [error, setError] = useState('');
  const admin = can(server.role, 'admin');

  const load = useCallback(async () => {
    try {
      const m = await api<{ members: Member[]; grantable: Role[] }>(`${base}/members`);
      setMembers(m.members);
      setGrantable(m.grantable);
      if (admin) setInvites((await api<{ invites: Invite[] }>(`${base}/invites`)).invites);
      setError('');
    } catch (ex) {
      setError(message(ex));
    }
  }, [base, admin]);
  useEffect(() => { load(); }, [load]);

  const act = async (work: () => Promise<unknown>) => {
    setError('');
    try {
      await work();
      await load();
    } catch (ex) {
      setError(message(ex));
    }
  };

  const invite = () => act(async () => {
    const r = await api<{ url: string; role: Role }>(`${base}/invites`, { method: 'POST', body: JSON.stringify({ role }) });
    setLink(r);
  });
  const change = (m: Member, next: Role) => act(() => api(`${base}/members/${m.id}`, { method: 'PATCH', body: JSON.stringify({ role: next }) }));
  const remove = (m: Member) => {
    const who = m.you ? 'Leave this server' : `Remove ${m.name || m.email}`;
    if (!confirm(`${who}?`)) return;
    act(async () => {
      await api(`${base}/members/${m.id}`, { method: 'DELETE' });
      if (m.you) window.location.hash = '#/';
    });
  };
  const revoke = (i: Invite) => act(() => api(`${base}/invites/${i.id}`, { method: 'DELETE' }));

  return (
    <section className="card"><h2>Members</h2>
      {error && <p className="error">{error}</p>}
      <div className="table-wrap"><table>
        <thead><tr><th>Who</th><th>Role</th><th className="hide-sm">Since</th><th /></tr></thead>
        <tbody>
          {(members || []).map(m => (
            <tr key={m.id}>
              <td><span className="me">{m.picture && <img src={m.picture} alt="" referrerPolicy="no-referrer" />}
                {m.name || m.email}{m.you && <span className="muted"> (you)</span>}</span></td>
              <td>{m.manageable && grantable.length ? (
                <select value={m.role} onChange={ev => change(m, ev.target.value as Role)}>
                  {[...new Set([m.role, ...grantable])].map(r => <option key={r} value={r}>{r}</option>)}
                </select>
              ) : <Badge kind={m.role === 'owner' ? 'done' : undefined}>{m.role}</Badge>}</td>
              <td className="hide-sm">{ago(m.since)}</td>
              <td className="right">{(m.manageable || (m.you && m.role !== 'owner')) &&
                <button className="ghost small" onClick={() => remove(m)}>{m.you ? 'Leave' : 'Remove'}</button>}</td>
            </tr>
          ))}
          {!members && <tr><td colSpan={4} className="muted">Loading…</td></tr>}
        </tbody></table></div>

      {admin && grantable.length > 0 && (
        <>
          <h3>Invite someone</h3>
          <p className="muted">Makes a link that one person can use within seven days. They sign in or sign up, then join with the role you pick.</p>
          <div className="toolbar">
            <select value={role} onChange={ev => setRole(ev.target.value as Role)}>
              {grantable.map(r => <option key={r} value={r}>{r}: {ROLE_TEXT[r]}</option>)}
            </select>
            <button onClick={invite}>Create invite link</button>
          </div>
          {link && (
            <div className="connect">
              <p>Invite link for a <b>{link.role}</b>, shown only now:</p>
              <pre>{link.url}</pre>
              <div className="toolbar"><CopyButton text={link.url} /></div>
            </div>
          )}
          {invites.length > 0 && (
            <div className="table-wrap"><table>
              <thead><tr><th>Open invite</th><th>Made by</th><th>Expires</th><th /></tr></thead>
              <tbody>{invites.map(i => (
                <tr key={i.id}><td><Badge>{i.role}</Badge></td><td>{i.by}</td><td>{new Date(i.expires).toLocaleDateString()}</td>
                  <td className="right"><button className="ghost small" onClick={() => revoke(i)}>Revoke</button></td></tr>
              ))}</tbody>
            </table></div>
          )}
        </>
      )}
    </section>
  );
}
