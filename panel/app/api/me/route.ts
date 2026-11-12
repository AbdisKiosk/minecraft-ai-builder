import { json, requireUser, route } from '../../../lib/server/route';

export const GET = route(async () => {
  const u = await requireUser();
  return json({ id: u.id, name: u.name, email: u.email, picture: u.picture });
});
