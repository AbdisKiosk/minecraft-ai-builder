import { chatView, ownChat, startTurn, stop } from '../../../../../../lib/server/chat/runs';
import { deleteChat } from '../../../../../../lib/server/chat/store';
import { body, json, requireRole, requireUser, route } from '../../../../../../lib/server/route';

type P = { id: string; chat: string };

/** The chat as the page shows it. While a turn runs, `items` stop before its answer: the events carry that. */
export const GET = route<P>(async (_req, { id, chat }) => {
  const user = await requireUser();
  await requireRole(user, id, 'builder');
  return json(await chatView(await ownChat(chat, id, user)));
});

/** The next message: {"text", "model"?}. Answers 202; the model's answer follows on the chat's events. */
export const POST = route<P>(async (req, { id, chat }) => {
  const user = await requireUser();
  await requireRole(user, id, 'builder');
  const row = await ownChat(chat, id, user);
  const b = await body(req);
  await startTurn(row, user, typeof b.text === 'string' ? b.text : '', typeof b.model === 'string' ? b.model : row.model);
  return json({ id: row.id }, 202);
});

/** Deletes the chat, stopping its turn. What it built stays in the world and in the server's history. */
export const DELETE = route<P>(async (_req, { id, chat }) => {
  const user = await requireUser();
  await requireRole(user, id, 'viewer');
  const row = await ownChat(chat, id, user);
  stop(row.id);
  await deleteChat(row.id);
  return new Response(null, { status: 204 });
});
