import { redirect } from 'next/navigation';
import Logo from '../../../components/Logo';
import { previewInvite } from '../../../lib/server/invites';
import { currentUser } from '../../../lib/server/session';
import AcceptInvite from './AcceptInvite';

export const dynamic = 'force-dynamic';

const ROLE_TEXT = {
  owner: 'own',
  admin: 'manage',
  builder: 'build on',
  viewer: 'view',
} as const;

export default async function InvitePage({ params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  const user = await currentUser();
  // New users land on the sign-up screen; Auth0 links to log in from there.
  if (!user) redirect(`/auth/login?screen_hint=signup&returnTo=${encodeURIComponent('/invite/' + token)}`);
  const invite = await previewInvite(token);
  return (
    <div className="landing">
      <nav className="landing-nav"><a className="brand" href="/"><Logo />AIBuilder</a>
        <a className="button ghost" href="/dashboard">Dashboard</a></nav>
      <div className="card invite-card">
        {!invite ? <><h2>Invite not found</h2><p>This link is not valid. Ask for a new one.</p></>
          : invite.state !== 'open' ? <><h2>Invite {invite.state}</h2>
              <p>This invite to <b>{invite.server.name}</b> {invite.state === 'used' ? 'has already been used' : 'has expired'}. Ask {invite.by || 'its owner'} for a new one.</p></>
          : <>
            <h2>Join {invite.server.name}</h2>
            <p>{invite.by || 'Someone'} invited you to {ROLE_TEXT[invite.role]} <b>{invite.server.name}</b> as <b>{invite.role}</b>.</p>
            <p className="muted">Signed in as {user.name || user.email}.</p>
            <AcceptInvite token={token} />
          </>}
      </div>
    </div>
  );
}
