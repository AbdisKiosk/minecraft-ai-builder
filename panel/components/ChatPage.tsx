import { useCallback, useEffect, useLayoutEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { api, message, serverApi } from '../lib/api';
import { applyEvent, CHAT_MODELS, DEFAULT_MODEL, type ChatEvent, type ChatItem, type ChatSummary, type ChatView } from '../lib/chat';
import { ago, num } from '../lib/format';
import { chatHref, draftHref, go, tokensHref } from '../lib/routes';
import type { Server } from '../lib/types';

interface List { chats: ChatSummary[]; key: boolean }

/**
 * Chat with Claude about one server: it builds through the same tools as Claude Code, on the user's own API key.
 * A turn runs on the panel, so this page only follows it and can be closed meanwhile.
 */
export default function ChatPage({ server, chat }: { server: Server; chat: string | null }) {
  const base = `${serverApi(server.id)}/chats`;
  const [list, setList] = useState<List | null>(null);
  const [view, setView] = useState<ChatView | null>(null);
  const [items, setItems] = useState<ChatItem[]>([]);
  const [running, setRunning] = useState(false);
  const [usage, setUsage] = useState<{ input: number; output: number } | null>(null);
  const [model, setModel] = useState(DEFAULT_MODEL);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');
  /** Bumped to read the chat again: after a message is sent and when a turn ends. */
  const [version, setVersion] = useState(0);

  const loadList = useCallback(() => {
    api<List>(base).then(setList, ex => setError(message(ex)));
  }, [base]);
  useEffect(loadList, [loadList]);

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
      setModel(v.model);
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

  const send = async (text: string) => {
    setSending(true);
    setError('');
    try {
      if (chat) {
        await api(`${base}/${encodeURIComponent(chat)}`, { method: 'POST', body: JSON.stringify({ text, model }) });
        setVersion(v => v + 1);
      } else {
        const made = await api<{ id: string }>(base, { method: 'POST', body: JSON.stringify({ text, model }) });
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
  const blocked = list && !list.key ? 'key' : !server.online ? 'offline' : null;
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
        {blocked === 'key' && <p className="warn">Chatting here uses your own Anthropic API key.{' '}
          <a href={tokensHref}>Add it under Access tokens</a>, then come back.</p>}
        {blocked === 'offline' && <p className="warn">This server is offline, so Claude cannot build on it right now.</p>}
        <Composer disabled={!!blocked || sending} running={running} onSend={send} onStop={stop} />
        <div className="chat-foot muted">
          <label>Model{' '}
            <select value={model} onChange={ev => setModel(ev.target.value)} disabled={running}>
              {CHAT_MODELS.map(m => <option key={m.id} value={m.id}>{m.name} ({m.note})</option>)}
            </select>
          </label>
          <span className="spacer" />
          {total && total.input + total.output > 0 && (
            <span title="Tokens this chat has sent to and received from Anthropic, billed to your API key">
              {num(total.input)} tokens in · {num(total.output)} out</span>
          )}
        </div>
      </section>
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
