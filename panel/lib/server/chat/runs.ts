import type Anthropic from '@anthropic-ai/sdk';
import { itemsOf, providerName, type ChatEvent, type ChatModel, type ChatView, type EndReason, type ProviderId } from '../../chat';
import { roleOf, type UserRow } from '../access';
import { apiKeyOf } from '../apiKeys';
import { hub } from '../hub';
import { TOOL_TIMEOUT_MS } from '../plugin-proxy';
import { atLeast } from '../roles';
import { HttpError } from '../route';
import { owedResults, runTurn, type TurnEnd } from './agent';
import { callTool, instructions, listTools, McpError, type Send } from './mcp';
import { loadSkills, systemPrompt } from './prompt';
import { findModel } from './models';
import { clientFor, modelParams, providerFailure, withoutImages } from './providers';
import { appendMessage, chatRow, messagesOf, setSystemPrompt, turnEnded, turnStarted, type ChatRow } from './store';

// The turns that are running. A turn belongs to this process, not to the request that started it: a build takes
// minutes, and closing the page must not stop it. The page follows a turn through its events, replayed from the start
// to whoever opens the stream. A restart of the panel ends its turns; what they stored stays (see owedResults).

/** Requests to the model in one turn; each round of tool calls takes one. */
export const MAX_ROUNDS = 40;
/** Turns one user may have running at once, over all their chats. */
export const MAX_RUNS = 3;
export const MAX_MESSAGE_CHARS = 8000;
/** How long an ended turn's events stay for a page that was reconnecting. */
const KEEP_ENDED_MS = 30_000;

class Run {
  readonly events: ChatEvent[] = [];
  readonly listeners = new Set<(e: ChatEvent) => void>();
  readonly abort = new AbortController();
  /** How many of the chat's stored messages are from before this turn's answer; the page shows those, then the events. */
  base = 0;
  ended = false;
  usage = { input: 0, output: 0 };

  constructor(readonly userId: number) {}

  emit(e: ChatEvent) {
    if (e.type === 'usage') this.usage = { input: e.input, output: e.output };
    const last = this.events[this.events.length - 1];
    // Text arrives a few characters at a time: kept as one event, so a replay is short.
    if (e.type === 'text' && last?.type === 'text') this.events[this.events.length - 1] = { type: 'text', text: last.text + e.text };
    else this.events.push(e);
    for (const l of this.listeners) l(e);
  }
}

// One map per process: Next's route bundles each load their own copy of this module.
const g = globalThis as { __aibChatRuns?: Map<string, Run> };
const runs = (): Map<string, Run> => (g.__aibChatRuns ??= new Map());

export function running(chatId: string): boolean {
  const run = runs().get(chatId);
  return !!run && !run.ended;
}

/** Sends the turn's events so far to `listener`, then the ones that follow. Null when no turn is known for the chat. */
export function follow(chatId: string, listener: (e: ChatEvent) => void): (() => void) | null {
  const run = runs().get(chatId);
  if (!run) return null;
  for (const e of [...run.events]) listener(e);
  if (run.ended) return () => {};
  run.listeners.add(listener);
  return () => { run.listeners.delete(listener); };
}

/** Asks the chat's turn to stop: at once while the model writes, after the tool call that is running otherwise. */
export function stop(chatId: string): boolean {
  const run = runs().get(chatId);
  if (!run || run.ended) return false;
  run.abort.abort();
  return true;
}

/** The chat as the page shows it. */
export async function chatView(chat: ChatRow): Promise<ChatView> {
  const run = runs().get(chat.id);
  const live = !!run && !run.ended;
  const messages = await messagesOf(chat.id);
  return {
    id: chat.id, title: chat.title, provider: chat.provider, model: chat.model, updated: chat.updated_at.getTime(),
    items: itemsOf(live ? messages.slice(0, run.base) : messages),
    running: live,
    usage: { input: chat.input_tokens, output: chat.output_tokens },
    error: live ? null : chat.error,
  };
}

/**
 * Starts a turn: stores the user's message and lets the model answer in the background. Resolves once the message is
 * stored. Refuses (HttpError) when the turn cannot start; the caller has checked that `user` builds on the server.
 * The model may change between turns, the provider may not: thinking blocks that one provider's account returned
 * are not for another's.
 */
export async function startTurn(chat: ChatRow, user: UserRow, text: string, model: string): Promise<void> {
  const provider = chat.provider;
  if (!text.trim()) throw new HttpError(400, 'write a message first');
  if (text.length > MAX_MESSAGE_CHARS) throw new HttpError(400, `a message can be at most ${MAX_MESSAGE_CHARS} characters`);
  const chosen = await findModel(provider, model);
  if (!chosen) throw new HttpError(400, `${providerName(provider)} has no model '${model}' that can use tools`);
  if (running(chat.id)) throw new HttpError(409, 'the AI is still answering in this chat; stop it or wait');
  if ([...runs().values()].filter(r => !r.ended && r.userId === user.id).length >= MAX_RUNS) {
    throw new HttpError(429, `you have ${MAX_RUNS} chats running; wait for one or stop it`);
  }
  if (!hub().online(chat.server_id)) throw new HttpError(503, 'the server is offline, so the AI cannot build on it right now');

  // Taken before the first await, so two requests cannot both start a turn in one chat.
  const run = new Run(user.id);
  runs().set(chat.id, run);
  let apiKey: string | null;
  const messages = await (async () => {
    apiKey = await apiKeyOf(user.id, provider);
    if (!apiKey) {
      throw new HttpError(400, `this chat uses ${providerName(provider)}: add your ${providerName(provider)} API key under Access tokens, or start a new chat`);
    }
    const history = await messagesOf(chat.id);
    const owed = owedResults(history);
    for (const m of [...(owed ? [owed] : []), { role: 'user' as const, content: text }]) {
      await appendMessage(chat.id, m);
      history.push(m);
    }
    await turnStarted(chat.id, model);
    return history;
  })().catch(e => {
    runs().delete(chat.id);
    throw e;
  });
  run.base = messages.length;

  answer(run, chat, chosen, apiKey!, messages).catch(e => console.error(`chat ${chat.id}: turn failed to finish`, e));
}

async function answer(run: Run, chat: ChatRow, model: ChatModel, apiKey: string, messages: Anthropic.MessageParam[]) {
  const send: Send = body => hub().requestAsUser(chat.server_id, { t: 'mcp', body }, chat.user_id, TOOL_TIMEOUT_MS);
  let end: TurnEnd;
  try {
    const tools = await listTools(send);
    let system = chat.system_prompt;
    if (system === null) {
      system = systemPrompt(await instructions(send), await loadSkills());
      await setSystemPrompt(chat.id, system);
    }
    // The key is always the user's own, never one from the panel's environment.
    const client = clientFor(chat.provider, apiKey);
    end = await runTurn({ model: model.id, params: modelParams(model), system, tools, messages, maxRounds: MAX_ROUNDS, signal: run.abort.signal }, {
      ask(params, onText, signal) {
        if (!model.images) params = { ...params, messages: withoutImages(params.messages) };
        const stream = client.messages.stream(params, { signal });
        stream.on('text', onText);
        return stream.finalMessage();
      },
      callTool: (name, input) => callTool(send, name, input),
      async refusal() {
        const role = await roleOf(chat.user_id, chat.server_id);
        return atLeast(role, 'builder') ? null : 'You no longer have the builder role on this server, so nothing was built.';
      },
      append: m => appendMessage(chat.id, m),
      emit: e => run.emit(e),
    });
  } catch (e) {
    end = { reason: 'error', error: failure(chat.provider, e) };
  }
  const error = end.error ?? WHY[end.reason];
  run.ended = true;
  await turnEnded(chat.id, run.usage.input, run.usage.output, error).catch(e => console.warn(`chat ${chat.id}: could not store the turn's end`, e.message));
  run.emit({ type: 'end', reason: end.reason, ...(error ? { error } : {}) });
  run.listeners.clear();
  setTimeout(() => { if (runs().get(chat.id) === run) runs().delete(chat.id); }, KEEP_ENDED_MS).unref();
}

const WHY: Record<EndReason, string | null> = {
  done: null,
  stopped: null,
  limit: `The AI stopped after ${MAX_ROUNDS} steps. Say "continue" to let it go on.`,
  refused: 'The model declined this request.',
  error: 'The turn failed.',
};

/** What went wrong, in words for the user. Anything unexpected is logged and not shown. */
function failure(provider: ProviderId, e: unknown): string {
  const said = providerFailure(provider, e);
  if (said) return said;
  const code = (e as { code?: unknown } | null)?.code;
  if (e instanceof McpError || code === 'OFFLINE' || code === 'TIMEOUT') return `The Minecraft server did not answer: ${(e as Error).message}.`;
  console.error('chat: turn failed', e);
  return 'The panel hit an internal error. Say "continue" to try again.';
}

/** The chat when it is `user`'s and about `serverId`; otherwise as if it did not exist. */
export async function ownChat(chatId: string, serverId: string, user: UserRow): Promise<ChatRow> {
  const chat = await chatRow(chatId);
  if (!chat || chat.user_id !== user.id || chat.server_id !== serverId) throw new HttpError(404, 'no such chat');
  return chat;
}
