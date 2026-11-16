import { useCallback, useEffect, useLayoutEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { api, message, serverApi } from '../lib/api';
import { applyEvent, DEFAULT_MODEL, priceText, PROVIDERS, providerName, type ChatEvent, type ChatItem, type ChatModel, type ChatSummary, type ChatView, type ProviderId } from '../lib/chat';
import { ago, num } from '../lib/format';
import { chatHref, draftHref, go, tokensHref } from '../lib/routes';
import type { Server } from '../lib/types';

interface List { chats: ChatSummary[]; /** The providers the user has a usable key for. */ providers: ProviderId[] }

/** The models a chat can use per provider; `stale` names providers whose list could not be read just now. */
interface Catalog { models: Record<ProviderId, ChatModel[]>; stale: ProviderId[] }

/** The provider and model picked for the next message; `model` is what the user typed until it names one. */
interface Pick { provider: ProviderId; model: string }

/**
 * Chat with Claude about one server: it builds through the same tools as Claude Code, on the user's own API key for
 * Anthropic or OpenRouter.
 * A turn runs on the panel, so this page only follows it and can be closed meanwhile.
 */
export default function ChatPage({ server, chat }: { server: Server; chat: string | null }) {
  const base = `${serverApi(server.id)}/chats`;
  const [list, setList] = useState<List | null>(null);
  const [view, setView] = useState<ChatView | null>(null);
  const [items, setItems] = useState<ChatItem[]>([]);
  const [running, setRunning] = useState(false);
  const [usage, setUsage] = useState<{ input: number; output: number } | null>(null);
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  /** Null until the user's keys are known. */
  const [pick, setPick] = useState<Pick | null>(null);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');
  /** Bumped to read the chat again: after a message is sent and when a turn ends. */
  const [version, setVersion] = useState(0);

  const loadList = useCallback(() => {
    api<List>(base).then(setList, ex => setError(message(ex)));
  }, [base]);
  useEffect(loadList, [loadList]);
  useEffect(() => {
    api<Catalog>('/api/chat-models').then(setCatalog, ex => setError(message(ex)));
  }, []);

  useEffect(() => {
    setError('');
    if (!chat) {
      setView(null);
      setItems([]);
      setRunning(false);
      setUsage(null);
      return;
    }
    let current = true;
    api<ChatView>(`${base}/${encodeURIComponent(chat)}`).then(v => {
      if (!current) return;
      setView(v);
      setItems(v.items);
      setRunning(v.running);
      setUsage(null);
      setPick({ provider: v.provider, model: v.model });
    }, ex => { if (current) setError(message(ex)); });
    return () => { current = false; };
  }, [base, chat, version]);

  // Follows the running turn. The stream replays the turn from its start whenever it (re)connects.
  useEffect(() => {
    if (!chat || !view?.running) return;
    const source = new EventSource(`${base}/${encodeURIComponent(chat)}/events`);
    source.onopen = () => setItems(view.items);
    source.onmessage = ev => {
      const e = JSON.parse(ev.data) as ChatEvent;
      if (e.type === 'usage') setUsage({ input: e.input, output: e.output });
      else if (e.type === 'end') {
        source.close();
        setVersion(v => v + 1);
        loadList();
      } else setItems(now => applyEvent(now, e));
    };
    return () => source.close();
  }, [base, chat, view, loadList]);

  // A new chat starts on the first provider the user has a key for, unless they picked another.
  useEffect(() => {
    if (chat || !list) return;
    setPick(now => (now && list.providers.includes(now.provider) ? now
      : list.providers.length ? { provider: list.providers[0], model: DEFAULT_MODEL[list.providers[0]] } : null));
  }, [chat, list]);

  const chosen = pick ? catalog?.models[pick.provider]?.find(m => m.id === pick.model) ?? null : null;

  const send = async (text: string) => {
    if (!chosen) return false;
    const { provider, id: model } = chosen;
    setSending(true);
    setError('');
    try {
      if (chat) {
        await api(`${base}/${encodeURIComponent(chat)}`, { method: 'POST', body: JSON.stringify({ text, model }) });
        setVersion(v => v + 1);
      } else {
        const made = await api<{ id: string }>(base, { method: 'POST', body: JSON.stringify({ text, provider, model }) });
        loadList();
        go(chatHref(server.id, made.id));
      }
      return true;
    } catch (ex) {
      setError(message(ex));
      return false;
    } finally {
      setSending(false);
    }
  };

  const stop = () => {
    api(`${base}/${encodeURIComponent(chat!)}/stop`, { method: 'POST' }).catch(ex => setError(message(ex)));
  };

  const remove = async (c: ChatSummary) => {
    if (!confirm(`Delete the chat "${c.title}"? What it built stays in the world and in the history.`)) return;
    try {
      await api(`${base}/${encodeURIComponent(c.id)}`, { method: 'DELETE' });
      loadList();
      if (c.id === chat) go(chatHref(server.id));
    } catch (ex) {
      setError(message(ex));
    }
  };

  const total = view ? { input: view.usage.input + (usage?.input ?? 0), output: view.usage.output + (usage?.output ?? 0) } : null;
  // An open chat keeps its provider; a new one can use any provider the user has a key for.
  const providers = view ? [view.provider] : list?.providers ?? [];
  const blocked = !list || (chat && !view) ? null
    : view && !list.providers.includes(view.provider) ? 'chat-key'
    : !view && !list.providers.length ? 'key'
    : !server.online ? 'offline' : null;
  return (
    <div className="chat-page">
      <aside className="card chat-list">
        <a className="button small" href={chatHref(server.id)}>+ New chat</a>
        {!list ? <p className="muted">Loading…</p> : !list.chats.length ? <p className="muted">No chats yet.</p> : (
          <ul>
            {list.chats.map(c => (
              <li key={c.id} className={c.id === chat ? 'current' : ''}>
                <a href={chatHref(server.id, c.id)}><span>{c.title}</span><small className="muted">{ago(c.updated)}</small></a>
                <button className="ghost small" onClick={() => remove(c)} aria-label={`Delete the chat ${c.title}`}>×</button>
              </li>
            ))}
          </ul>
        )}
      </aside>
      <section className="card chat-main">
        <Conversation items={items} running={running} server={server.id} empty={!chat} />
        {view?.error && !running && <p className="warn">{view.error}</p>}
        {error && <p className="error">{error}</p>}
        {blocked === 'key' && <p className="warn">Chatting here uses your own API key for Anthropic or OpenRouter.{' '}
          <a href={tokensHref}>Add one under Access tokens</a>, then come back.</p>}
        {blocked === 'chat-key' && view && <p className="warn">This chat uses {providerName(view.provider)}, and you have no{' '}
          {providerName(view.provider)} key. <a href={tokensHref}>Add it under Access tokens</a>, or start a new chat.</p>}
        {blocked === 'offline' && <p className="warn">This server is offline, so Claude cannot build on it right now.</p>}
        <Composer disabled={!!blocked || sending || !chosen} running={running} onSend={send} onStop={stop} />
        <div className="chat-foot muted">
          {pick && providers.length > 0 && (
            <ModelPicker providers={providers} pick={pick} catalog={catalog} locked={!!view} disabled={running} onPick={setPick} />
          )}
          <span className="spacer" />
          {total && total.input + total.output > 0 && (
            <span title={`Tokens this chat has sent to and received from ${view ? providerName(view.provider) : 'the provider'}, billed to your key`}>
              {num(total.input)} tokens in · {num(total.output)} out</span>
          )}
        </div>
      </section>
    </div>
  );
}

/** "1M" or "200k". */
const tokens = (n: number) => (n >= 1_000_000 ? `${+(n / 1_000_000).toFixed(1)}M` : `${Math.round(n / 1000)}k`);

/**
 * The provider (for a new chat, among those the user has a key for) and its model: a list for Anthropic's few, a
 * search over the catalog for OpenRouter's hundreds.
 */
function ModelPicker({ providers, pick, catalog, locked, disabled, onPick }: {
  providers: ProviderId[]; pick: Pick; catalog: Catalog | null; locked: boolean; disabled: boolean; onPick: (p: Pick) => void;
}) {
  const models = catalog?.models[pick.provider] ?? [];
  const chosen = models.find(m => m.id === pick.model);
  const listId = `chat-models-${pick.provider}`;
  return (
    <div className="model-picker">
      {!locked && providers.length > 1 ? (
        <select value={pick.provider} disabled={disabled} aria-label="Provider"
          onChange={ev => { const provider = ev.target.value as ProviderId; onPick({ provider, model: DEFAULT_MODEL[provider] }); }}>
          {PROVIDERS.filter(p => providers.includes(p.id)).map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
        </select>
      ) : <span>{providerName(pick.provider)}</span>}
      {models.length <= 12 ? (
        <select value={chosen ? pick.model : ''} disabled={disabled || !catalog} aria-label="Model"
          onChange={ev => onPick({ ...pick, model: ev.target.value })}>
          {!chosen && <option value="">{catalog ? 'Pick a model' : 'Loading models…'}</option>}
          {models.map(m => <option key={m.id} value={m.id}>{m.name}</option>)}
        </select>
      ) : (
        <>
          <input type="search" list={listId} value={pick.model} disabled={disabled} spellCheck={false}
            placeholder="Search models, e.g. claude, gpt, gemini" aria-label="Model"
            onChange={ev => onPick({ ...pick, model: ev.target.value.trim() })} />
          <datalist id={listId}>
            {models.map(m => <option key={m.id} value={m.id} label={`${m.name} · ${priceText(m)}`} />)}
          </datalist>
        </>
      )}
      <span className="model-facts">
        {chosen ? <>
          {priceText(chosen)}{chosen.context ? ` · ${tokens(chosen.context)} context` : ''}
          {!chosen.images && ' · cannot see renders'}
          {!chosen.claude && ' · the build advice is written for Claude, so results may vary'}
        </> : catalog && pick.model ? `No model that can use tools is called "${pick.model}". Pick one from the list.` : null}
        {catalog?.stale.includes(pick.provider) && ` ${providerName(pick.provider)}\u2019s model list could not be read just now.`}
      </span>
    </div>
  );
}

function Conversation({ items, running, server, empty }: { items: ChatItem[]; running: boolean; server: string; empty: boolean }) {
  const box = useRef<HTMLDivElement>(null);
  /** Whether the reader is at the bottom; new text only scrolls the view then. */
  const pinned = useRef(true);
  useLayoutEffect(() => {
    const el = box.current!;
    if (pinned.current) el.scrollTop = el.scrollHeight;
  }, [items, running]);

  const last = items[items.length - 1];
  const waiting = running && !(last?.kind === 'tool' && !last.result);
  return (
    <div className="chat-log" ref={box} aria-live="polite"
      onScroll={ev => { const el = ev.currentTarget; pinned.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40; }}>
      {empty && !items.length && (
        <div className="chat-empty muted">
          <b>Ask Claude to build something on this server.</b>
          <span>Say what and where: stand there in game, name an area you marked on the map, or give coordinates.</span>
        </div>
      )}
      {items.map((item, i) => (
        item.kind === 'tool' ? <ToolCard key={item.id} item={item} server={server} />
          : <div key={i} className={`chat-msg ${item.kind}`}>{item.text}</div>
      ))}
      {waiting && <div className="chat-working muted"><span className="spinner" /> Claude is working…</div>}
    </div>
  );
}

/** A short line saying what a call does, from its input. */
function brief(input: unknown): string {
  if (!input || typeof input !== 'object') return '';
  const i = input as Record<string, unknown>;
  if (Array.isArray(i.ops)) return `${i.ops.length} op${i.ops.length === 1 ? '' : 's'}`;
  const text = JSON.stringify(i.region ?? i);
  return text === '{}' ? '' : text.length > 60 ? text.slice(0, 60) + '…' : text;
}

function ToolCard({ item, server }: { item: Extract<ChatItem, { kind: 'tool' }>; server: string }) {
  const r = item.result;
  let draft: string | null = null;
  if (r && !r.error && r.text.includes('"draft"')) {
    try {
      const d = JSON.parse(r.text).draft;
      if (typeof d === 'string') draft = d;
    } catch {
      // cut-off or not JSON: no link
    }
  }
  return (
    <div className={`chat-tool ${r?.error ? 'failed' : ''}`}>
      <details>
        <summary>
          <code>{item.name}</code> <span className="muted">{brief(item.input)}</span>
          <span className="spacer" />
          {!r ? <span className="spinner" aria-label="running" /> : r.error ? <span className="badge failed">failed</span>
            : draft ? <span className="badge running">in draft</span> : <span className="badge">done</span>}
        </summary>
        <pre>{JSON.stringify(item.input, null, 1)}</pre>
        {r && <pre>{r.text}</pre>}
      </details>
      {r?.error && <div className="error">{r.text.length > 300 ? r.text.slice(0, 300) + '…' : r.text}</div>}
      {r?.image && <img src={r.image} alt={`Render from ${item.name}`} />}
      {draft && <a href={draftHref(server, draft)}>Review the draft</a>}
    </div>
  );
}

function Composer({ disabled, running, onSend, onStop }:
  { disabled: boolean; running: boolean; onSend: (text: string) => Promise<boolean>; onStop: () => void }) {
  const [text, setText] = useState('');
  const submit = async (ev?: FormEvent) => {
    ev?.preventDefault();
    if (disabled || running || !text.trim()) return;
    if (await onSend(text)) setText('');
  };
  const onKey = (ev: KeyboardEvent<HTMLTextAreaElement>) => {
    if (ev.key === 'Enter' && !ev.shiftKey && !ev.nativeEvent.isComposing) submit(ev);
  };
  return (
    <form className="chat-composer" onSubmit={submit}>
      <textarea value={text} onChange={ev => setText(ev.target.value)} onKeyDown={onKey} rows={2} maxLength={8000}
        placeholder="Build a small stone bridge over the river at @river…" aria-label="Message to Claude" disabled={disabled} />
      {running ? <button type="button" className="ghost" onClick={onStop}>Stop</button>
        : <button type="submit" disabled={disabled || !text.trim()}>Send</button>}
    </form>
  );
}
