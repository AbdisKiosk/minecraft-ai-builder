import type Anthropic from '@anthropic-ai/sdk';
import type { ChatSummary } from '../../chat';
import { exec, one, query } from '../db';
import { shortId } from '../tokens';

// The chats and their messages in MySQL (migration 011_chat.sql). A chat belongs to one user and one server.

export interface ChatRow {
  id: string;
  server_id: string;
  user_id: number;
  title: string;
  model: string;
  system_prompt: string | null;
  input_tokens: number;
  output_tokens: number;
  error: string | null;
  updated_at: Date;
}

const COLUMNS = 'id, server_id, user_id, title, model, system_prompt, input_tokens, output_tokens, error, updated_at';

export async function createChat(serverId: string, userId: number, title: string, model: string): Promise<ChatRow> {
  const id = shortId(12);
  await exec('INSERT INTO chats (id, server_id, user_id, title, model) VALUES (?, ?, ?, ?, ?)', [id, serverId, userId, title, model]);
  return (await chatRow(id))!;
}

export async function chatRow(id: string): Promise<ChatRow | null> {
  const r = await one<ChatRow>(`SELECT ${COLUMNS} FROM chats WHERE id = ?`, [id]);
  // mysql2 gives BIGINT as a string once it is large; these stay far below that.
  return r && { ...r, input_tokens: Number(r.input_tokens), output_tokens: Number(r.output_tokens) };
}

/** The user's newest chats about a server. */
export async function chatsOf(serverId: string, userId: number): Promise<ChatSummary[]> {
  const rows = await query<{ id: string; title: string; model: string; updated_at: Date }>(
    'SELECT id, title, model, updated_at FROM chats WHERE server_id = ? AND user_id = ? ORDER BY updated_at DESC LIMIT 50', [serverId, userId]);
  return rows.map(r => ({ id: r.id, title: r.title, model: r.model, updated: r.updated_at.getTime() }));
}

export async function messagesOf(chatId: string): Promise<Anthropic.MessageParam[]> {
  const rows = await query<{ role: 'user' | 'assistant'; content: string }>(
    'SELECT role, content FROM chat_messages WHERE chat_id = ? ORDER BY id', [chatId]);
  return rows.map(r => ({ role: r.role, content: JSON.parse(r.content) }));
}

export async function appendMessage(chatId: string, message: Anthropic.MessageParam): Promise<void> {
  await exec('INSERT INTO chat_messages (chat_id, role, content) VALUES (?, ?, ?)', [chatId, message.role, JSON.stringify(message.content)]);
}

/** A turn starts: the chat moves to the top of the list and its last error goes. */
export async function turnStarted(chatId: string, model: string): Promise<void> {
  await exec('UPDATE chats SET model = ?, error = NULL, updated_at = NOW() WHERE id = ?', [model, chatId]);
}

export async function setSystemPrompt(chatId: string, prompt: string): Promise<void> {
  await exec('UPDATE chats SET system_prompt = ? WHERE id = ?', [prompt, chatId]);
}

/** A turn ended: what it used, and why it stopped early if it did. */
export async function turnEnded(chatId: string, input: number, output: number, error: string | null): Promise<void> {
  await exec('UPDATE chats SET input_tokens = input_tokens + ?, output_tokens = output_tokens + ?, error = ?, updated_at = NOW() WHERE id = ?',
    [input, output, error, chatId]);
}

export async function deleteChat(chatId: string): Promise<void> {
  await exec('DELETE FROM chats WHERE id = ?', [chatId]);
}
