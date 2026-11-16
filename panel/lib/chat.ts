// The panel's chat, as the browser and the server both see it: the models on offer, what a conversation shows, and
// the events a running turn sends. No imports, so the page bundle gets none of the server's code.

export type ProviderId = 'anthropic' | 'openrouter';

export interface Provider {
  id: ProviderId;
  name: string;
  /** What its keys start with. */
  keyPrefix: string;
  /** Where a user makes a key. */
  keysUrl: string;
}

/** Where the chat can send its requests. Both speak Anthropic's Messages API; the user brings a key for either. */
export const PROVIDERS: readonly Provider[] = [
  { id: 'anthropic', name: 'Anthropic', keyPrefix: 'sk-ant-', keysUrl: 'https://platform.claude.com/settings/keys' },
  { id: 'openrouter', name: 'OpenRouter', keyPrefix: 'sk-or-', keysUrl: 'https://openrouter.ai/settings/keys' },
];

export function isProvider(v: unknown): v is ProviderId {
  return PROVIDERS.some(p => p.id === v);
}

export function providerName(id: ProviderId): string {
  return PROVIDERS.find(p => p.id === id)!.name;
}

export interface ChatModel {
  provider: ProviderId;
  /** The id the provider takes. */
  id: string;
  name: string;
  /** Dollars per million input and output tokens; null when it varies (a router that picks a model per request). */
  price: { input: number; output: number } | null;
  /** Tokens it reads at most. */
  context: number | null;
  /** Most tokens of one answer, when the provider says. */
  maxOutput: number | null;
  /** It can look at renders. A model that can't gets a note in their place. */
  images: boolean;
  /** One of Anthropic's models: requests use Anthropic's prompt caching. */
  claude: boolean;
  /** It takes adaptive thinking and an effort level. */
  adaptive: boolean;
}

/** The Claude models the chat offers through Anthropic's API, the default first, with OpenRouter's ids for them. */
const CLAUDE = [
  { name: 'Claude Opus 5.5', anthropic: 'claude-opus-5-5', openrouter: 'anthropic/claude-opus-5.5', input: 4, output: 20 },
  { name: 'Claude Sonnet 5.5', anthropic: 'claude-sonnet-5-5', openrouter: 'anthropic/claude-sonnet-5.5', input: 2, output: 10 },
  { name: 'Claude Haiku 5.5', anthropic: 'claude-haiku-5-5', openrouter: 'anthropic/claude-haiku-5.5', input: 0.1, output: 0.5 },
  { name: 'Claude Fable 5.2', anthropic: 'claude-fable-5-2', openrouter: 'anthropic/claude-fable-5.2', input: 8, output: 40 },
] as const;

const claudeVia = (provider: ProviderId): ChatModel[] => CLAUDE.map(m => ({
  provider, id: m[provider], name: m.name, price: { input: m.input, output: m.output },
  context: 1_000_000, maxOutput: 128_000, images: true, claude: true, adaptive: true,
}));

/** The models offered through Anthropic's API. */
export const ANTHROPIC_MODELS: readonly ChatModel[] = claudeVia('anthropic');

/** What OpenRouter offers when its catalog can't be read: the same Claude models. */
export const OPENROUTER_FALLBACK: readonly ChatModel[] = claudeVia('openrouter');

/** The model a new chat starts with on each provider. */
export const DEFAULT_MODEL: Record<ProviderId, string> = { anthropic: CLAUDE[0].anthropic, openrouter: CLAUDE[0].openrouter };

/** "$4 / $20 per million tokens", "free" or "price varies". */
export function priceText(m: ChatModel): string {
  if (!m.price) return 'price varies';
  if (m.price.input === 0 && m.price.output === 0) return 'free';
  const usd = (n: number) => '$' + (n >= 1 ? n.toFixed(2).replace(/\.00$/, '') : String(+n.toPrecision(2)));
  return `${usd(m.price.input)} / ${usd(m.price.output)} per million tokens`;
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

export interface ChatSummary { id: string; title: string; provider: ProviderId; model: string; updated: number }

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
