// The panel's chat, as the browser and the server both see it: the models on offer, what a conversation shows, and
// the events a running turn sends. No imports, so the page bundle gets none of the server's code.

export interface ChatModel { id: string; name: string; note: string }

/** The models a chat can use, the default first. The user pays Anthropic for whichever they pick. */
export const CHAT_MODELS: readonly ChatModel[] = [
  { id: 'claude-opus-5-5', name: 'Claude Opus 5.5', note: 'the default' },
  { id: 'claude-sonnet-5-5', name: 'Claude Sonnet 5.5', note: 'about half the price' },
  { id: 'claude-haiku-5-5', name: 'Claude Haiku 5.5', note: 'cheapest, for small jobs' },
  { id: 'claude-fable-5-2', name: 'Claude Fable 5.2', note: 'most capable, twice the price' },
];
export const DEFAULT_MODEL = CHAT_MODELS[0].id;

export function isChatModel(v: unknown): v is string {
  return CHAT_MODELS.some(m => m.id === v);
}

/** What a tool call answered, cut down for the page. `image` is a data URL (a render). */
export interface ToolView { error: boolean; text: string; image?: string }

/** One thing a conversation shows. A tool item has no result while the call runs. */
export type ChatItem =
  | { kind: 'user'; text: string }
  | { kind: 'assistant'; text: string }
  | { kind: 'tool'; id: string; name: string; input: unknown; result?: ToolView };

/** Why a turn ended. */
export type EndReason = 'done' | 'stopped' | 'limit' | 'refused' | 'error';

/** What a running turn sends the page. */
export type ChatEvent =
  | { type: 'text'; text: string }
  | { type: 'tool'; id: string; name: string; input: unknown }
  | { type: 'result'; id: string; result: ToolView }
  | { type: 'usage'; input: number; output: number }
  | { type: 'end'; reason: EndReason; error?: string };

export interface ChatSummary { id: string; title: string; model: string; updated: number }

export interface ChatView extends ChatSummary {
  items: ChatItem[];
  running: boolean;
  /** Tokens this conversation has sent to and received from Anthropic so far. */
  usage: { input: number; output: number };
  /** Why the last turn stopped early, if it did. */
  error: string | null;
}

/** The most characters of a tool's answer the page gets; Claude reads all of it. */
export const MAX_RESULT_TEXT = 4000;

/** The items after `e`, as a new array (the last item is copied when it changes). */
export function applyEvent(items: readonly ChatItem[], e: ChatEvent): ChatItem[] {
  if (e.type === 'text') {
    const last = items[items.length - 1];
    if (last?.kind === 'assistant') return [...items.slice(0, -1), { kind: 'assistant', text: last.text + e.text }];
    return [...items, { kind: 'assistant', text: e.text }];
  }
  if (e.type === 'tool') return [...items, { kind: 'tool', id: e.id, name: e.name, input: e.input }];
  if (e.type === 'result') return items.map(i => (i.kind === 'tool' && i.id === e.id ? { ...i, result: e.result } : i));
  return items as ChatItem[];
}

type Block = { type?: string; [k: string]: any };

/** What a tool_result block's content shows on the page. */
export function toolView(content: unknown, error: boolean): ToolView {
  const blocks: Block[] = typeof content === 'string' ? [{ type: 'text', text: content }] : Array.isArray(content) ? content : [];
  let text = blocks.filter(b => b.type === 'text').map(b => String(b.text ?? '')).join('\n');
  if (text.length > MAX_RESULT_TEXT) text = text.slice(0, MAX_RESULT_TEXT) + '…';
  const view: ToolView = { error, text };
  const image = blocks.find(b => b.type === 'image' && b.source?.type === 'base64');
  if (image) view.image = `data:${image.source.media_type};base64,${image.source.data}`;
  return view;
}

/** What stored messages (role and Messages API content) show: the text, and each tool call with its answer. */
export function itemsOf(messages: readonly { role: string; content: unknown }[]): ChatItem[] {
  let items: ChatItem[] = [];
  for (const m of messages) {
    const blocks: Block[] = typeof m.content === 'string' ? [{ type: 'text', text: m.content }] : Array.isArray(m.content) ? m.content : [];
    for (const b of blocks) {
      if (b.type === 'text' && b.text) {
        if (m.role === 'user') items.push({ kind: 'user', text: String(b.text) });
        else items = applyEvent(items, { type: 'text', text: String(b.text) });
      } else if (b.type === 'tool_use') {
        items = applyEvent(items, { type: 'tool', id: String(b.id), name: String(b.name), input: b.input });
      } else if (b.type === 'tool_result') {
        items = applyEvent(items, { type: 'result', id: String(b.tool_use_id), result: toolView(b.content, b.is_error === true) });
      }
    }
  }
  return items;
}
