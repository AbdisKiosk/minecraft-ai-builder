import { ownChat, stop } from '../../../../../../../lib/server/chat/runs';
import { json, requireRole, requireUser, route } from '../../../../../../../lib/server/route';

/** Stops the chat's running turn: {"stopped": false} when none was running. */
export const POST = route<{ id: string; chat: string }>(async (_req, { id, chat }) => {
  const user = await requireUser();
  await requireRole(user, id, 'viewer');
  return json({ stopped: stop((await ownChat(chat, id, user)).id) });
});
