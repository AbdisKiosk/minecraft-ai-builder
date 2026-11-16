import type Anthropic from '@anthropic-ai/sdk';
import { toolView, type ChatEvent, type EndReason } from '../../chat';

// One turn of the panel's chat: Claude answers the user's message, calling the server's tools as often as it needs.
// Everything it touches is passed in, so the loop runs in tests without Anthropic, a database or a Minecraft server.

export type ResultContent = Anthropic.ToolResultBlockParam['content'];
export interface ToolAnswer { content: ResultContent; isError: boolean }

export interface TurnInput {
  model: string;
  /** The fields that depend on the model (providers.ts modelParams): max_tokens, thinking, caching, routing. */
  params: Record<string, unknown>;
  system: string;
  tools: Anthropic.Tool[];
  /** The conversation so far, ending with the user's new message. The turn appends to it. */
  messages: Anthropic.MessageParam[];
  /** How many times Claude may be asked in this turn; each round of tool calls takes one. */
  maxRounds: number;
  signal: AbortSignal;
}

export interface TurnDeps {
  /** One request to Claude; `onText` gets the text as it is written. Rejects when the signal aborts. */
  ask(params: Anthropic.MessageCreateParamsNonStreaming, onText: (delta: string) => void, signal: AbortSignal): Promise<Anthropic.Message>;
  /** Runs a tool on the Minecraft server. A failure to reach the server is an answer too, not a rejection. */
  callTool(name: string, input: unknown): Promise<ToolAnswer>;
  /** Why the user may not build right now (they lost the builder role), or null. Asked before each round of tools. */
  refusal(): Promise<string | null>;
  /** Stores a message; the turn sends nothing to Claude that is not stored. */
  append(message: Anthropic.MessageParam): Promise<void>;
  emit(event: ChatEvent): void;
}

export interface TurnEnd { reason: EndReason; error?: string }

const toolUses = (content: Anthropic.ContentBlock[]) => content.filter((b): b is Anthropic.ToolUseBlock => b.type === 'tool_use');

export async function runTurn(input: TurnInput, deps: TurnDeps): Promise<TurnEnd> {
  const { messages, signal } = input;
  const usage = { input: 0, output: 0 };
  for (let round = 0; round < input.maxRounds; round++) {
    if (signal.aborted) return { reason: 'stopped' };
    let message: Anthropic.Message;
    try {
      message = await deps.ask({
        max_tokens: 16_000,
        ...input.params,
        model: input.model,
        system: input.system,
        tools: input.tools,
        messages,
      } as Anthropic.MessageCreateParamsNonStreaming, text => deps.emit({ type: 'text', text }), signal);
    } catch (e) {
      if (signal.aborted) return { reason: 'stopped' };
      throw e;
    }
    const u = message.usage;
    usage.input += u.input_tokens + (u.cache_creation_input_tokens ?? 0) + (u.cache_read_input_tokens ?? 0);
    usage.output += u.output_tokens;
    deps.emit({ type: 'usage', ...usage });

    // A refusal can cut a tool call off mid-input: keep none of that answer and run none of its tools.
    if (message.stop_reason === 'refusal') return { reason: 'refused', error: message.stop_details?.explanation ?? undefined };
    const calls = toolUses(message.content);
    if (message.stop_reason === 'max_tokens' && calls.length) {
      return { reason: 'error', error: 'Claude’s answer was cut off in the middle of a tool call, so it was not run. Ask for the build in smaller steps.' };
    }
    if (message.content.length) {
      const assistant: Anthropic.MessageParam = { role: 'assistant', content: message.content };
      await deps.append(assistant);
      messages.push(assistant);
    }
    if (message.stop_reason === 'pause_turn') continue;
    if (!calls.length) return { reason: 'done' };

    const refused = await deps.refusal();
    const results: Anthropic.ToolResultBlockParam[] = [];
    for (const call of calls) {
      deps.emit({ type: 'tool', id: call.id, name: call.name, input: call.input });
      // Every call gets an answer, or the conversation could not be sent to Claude again.
      const answer: ToolAnswer = signal.aborted ? { content: 'Not run: the user stopped the turn.', isError: true }
        : refused ? { content: refused, isError: true }
        : await deps.callTool(call.name, call.input);
      deps.emit({ type: 'result', id: call.id, result: toolView(answer.content, answer.isError) });
      results.push({ type: 'tool_result', tool_use_id: call.id, content: answer.content, is_error: answer.isError });
    }
    const user: Anthropic.MessageParam = { role: 'user', content: results };
    await deps.append(user);
    messages.push(user);
    if (refused) return { reason: 'error', error: refused };
  }
  return { reason: signal.aborted ? 'stopped' : 'limit' };
}

/**
 * The answers a conversation still owes: when its last message is Claude calling tools (the panel stopped before they
 * ran or were stored), an error answer for each, so the next request to Claude is valid. Null when none are owed.
 */
export function owedResults(messages: readonly Anthropic.MessageParam[]): Anthropic.MessageParam | null {
  const last = messages[messages.length - 1];
  if (last?.role !== 'assistant' || typeof last.content === 'string') return null;
  const calls = last.content.filter(b => b.type === 'tool_use');
  if (!calls.length) return null;
  return {
    role: 'user',
    content: calls.map(c => ({
      type: 'tool_result' as const, tool_use_id: c.id, is_error: true,
      content: 'Interrupted: the panel restarted, so it is unknown whether this ran. Look at the world before doing it again.',
    })),
  };
}
