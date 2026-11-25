import { isSource, type ChatSource } from '../../../../../lib/chat';
import { defaultModel } from '../../../../../lib/server/chat/models';
import { usableProviders } from '../../../../../lib/server/apiKeys';
import { startTurn } from '../../../../../lib/server/chat/runs';
import { chatsOf, createChat, deleteChat } from '../../../../../lib/server/chat/store';
import { balanceOf, creditsEnabled } from '../../../../../lib/server/credits';
import { body, HttpError, json, requireRole, requireUser, route } from '../../../../../lib/server/route';

type P = { id: string };

/**
 * The caller's chats about this server, newest first, and what can pay for a chat: `providers` are the providers they
 * have a usable key for, then 'credits' on a panel that sells them, where `credits` is their balance in micro-dollars
 * (null otherwise).
 */
export const GET = route<P>(async (_req, { id }) => {
  const user = await requireUser();
  await requireRole(user, id, 'builder');
  const sold = creditsEnabled();
  const providers: ChatSource[] = [...await usableProviders(user.id), ...(sold ? ['credits' as const] : [])];
  return json({ chats: await chatsOf(id, user.id), providers, credits: sold ? await balanceOf(user.id) : null });
});

/**
 * Starts a chat with its first message: {"text", "provider"?, "model"?} answers {"id"}; the model's answer follows on its
 * events. The provider (Anthropic by default, or "credits") stays the chat's.
 */
export const POST = route<P>(async (req, { id }) => {
  const user = await requireUser();
  await requireRole(user, id, 'builder');
  const b = await body(req);
  const text = typeof b.text === 'string' ? b.text : '';
  const provider = b.provider ?? 'anthropic';
  if (!isSource(provider)) throw new HttpError(400, 'unknown provider');
  const model = typeof b.model === 'string' ? b.model : defaultModel(provider);
  const title = text.trim().replace(/\s+/g, ' ').slice(0, 80) || 'New chat';
  const chat = await createChat(id, user.id, title, provider, model);
  try {
    await startTurn(chat, user, text, model);
  } catch (e) {
    await deleteChat(chat.id);
    throw e;
  }
  return json({ id: chat.id }, 201);
});
