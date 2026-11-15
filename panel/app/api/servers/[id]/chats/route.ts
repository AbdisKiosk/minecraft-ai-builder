import { DEFAULT_MODEL } from '../../../../../lib/chat';
import { apiKeyView } from '../../../../../lib/server/apiKeys';
import { startTurn } from '../../../../../lib/server/chat/runs';
import { chatsOf, createChat, deleteChat } from '../../../../../lib/server/chat/store';
import { body, json, requireRole, requireUser, route } from '../../../../../lib/server/route';

type P = { id: string };

/** The caller's chats about this server, newest first, and whether they have an API key to chat with. */
export const GET = route<P>(async (_req, { id }) => {
  const user = await requireUser();
  await requireRole(user, id, 'builder');
  const key = await apiKeyView(user.id);
  return json({ chats: await chatsOf(id, user.id), key: !!key?.usable });
});

/** Starts a chat with its first message: {"text", "model"?} answers {"id"}; Claude's answer follows on its events. */
export const POST = route<P>(async (req, { id }) => {
  const user = await requireUser();
  await requireRole(user, id, 'builder');
  const b = await body(req);
  const text = typeof b.text === 'string' ? b.text : '';
  const model = typeof b.model === 'string' ? b.model : DEFAULT_MODEL;
  const title = text.trim().replace(/\s+/g, ' ').slice(0, 80) || 'New chat';
  const chat = await createChat(id, user.id, title, model);
  try {
    await startTurn(chat, user, text, model);
  } catch (e) {
    await deleteChat(chat.id);
    throw e;
  }
  return json({ id: chat.id }, 201);
});
