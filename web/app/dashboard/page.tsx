import { redirect } from 'next/navigation';
import Panel from '../../components/Panel';
import { currentUser } from '../../lib/server/session';

export const dynamic = 'force-dynamic';

export default async function DashboardPage() {
  const user = await currentUser();
  if (!user) redirect('/auth/login?returnTo=/dashboard');
  return <Panel me={{ name: user.name, email: user.email, picture: user.picture }} />;
}
