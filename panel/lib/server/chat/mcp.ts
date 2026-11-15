import type Anthropic from '@anthropic-ai/sdk';
import type { ToolAnswer } from './agent';

// The plugin's MCP endpoint as the chat uses it: the same JSON-RPC messages Claude Code sends to /s/<server>/mcp, sent
// down the relay by the panel itself as the signed-in user. So a chat's edits pass the same checks, drafts and history.

/** Sends one JSON-RPC request body to the server and resolves with the reply's status and body. */
export type Send = (body: unknown) => Promise<{ status: number; body: unknown }>;

type Json = Record<string, any>;

export class McpError extends Error {}

async function rpc(send: Send, method: string, params: Json = {}): Promise<Json> {
  const reply = await send({ jsonrpc: '2.0', id: 1, method, params });
  const body = reply.body as Json | null;
  if (reply.status !== 200 || !body || typeof body !== 'object') {
    throw new McpError(typeof body?.error === 'string' ? body.error : `the server answered ${reply.status}`);
  }
  if (body.error) throw new McpError(String(body.error.message ?? 'the server refused the request'));
  return body.result ?? {};
}

/** Starts a session, as an MCP client does: the plugin's instructions for every tool. */
export async function instructions(send: Send): Promise<string> {
  const result = await rpc(send, 'initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'aibuilder-panel-chat', version: '1' } });
  return typeof result.instructions === 'string' ? result.instructions : '';
}

/** The server's listed tools, as the Messages API takes them, in the plugin's order (the prompt cache needs it stable). */
export async function listTools(send: Send): Promise<Anthropic.Tool[]> {
  const result = await rpc(send, 'tools/list');
  const tools: Json[] = Array.isArray(result.tools) ? result.tools : [];
  return tools.map(t => ({ name: String(t.name), description: String(t.description ?? ''), input_schema: t.inputSchema ?? { type: 'object' } }));
}

/** An MCP tool result as a tool_result's content: its text, and a render as an image Claude can look at. */
export function toolAnswer(result: Json): ToolAnswer {
  const content: Exclude<ToolAnswer['content'], string | undefined> = [];
  for (const c of Array.isArray(result.content) ? result.content as Json[] : []) {
    if (c.type === 'text' && typeof c.text === 'string') content.push({ type: 'text', text: c.text });
    else if (c.type === 'image' && typeof c.data === 'string' && /^image\/(png|jpeg|gif|webp)$/.test(c.mimeType)) {
      content.push({ type: 'image', source: { type: 'base64', media_type: c.mimeType, data: c.data } });
    }
  }
  if (!content.length) content.push({ type: 'text', text: '{}' });
  return { content, isError: result.isError === true };
}

/** Calls a tool. Whatever goes wrong becomes an error answer, so Claude can tell the user or try something else. */
export async function callTool(send: Send, name: string, input: unknown): Promise<ToolAnswer> {
  try {
    return toolAnswer(await rpc(send, 'tools/call', { name, arguments: input ?? {} }));
  } catch (e) {
    const code = (e as { code?: unknown } | null)?.code;
    if (e instanceof McpError || code === 'OFFLINE' || code === 'TIMEOUT') return { content: (e as Error).message, isError: true };
    throw e;
  }
}
