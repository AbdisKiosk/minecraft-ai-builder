import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import { test } from 'node:test';

// The chat's modules import their neighbours without extensions, as the bundles resolve them; Node needs the extension.
registerHooks({
  resolve(specifier, context, next) {
    try {
      return next(specifier, context);
    } catch (e) {
      if (specifier.startsWith('.') && !/\.[cm]?[jt]s$/.test(specifier)) return next(`${specifier}.ts`, context);
      throw e;
    }
  },
});
const { ANTHROPIC_MODELS, applyEvent, DEFAULT_MODEL, itemsOf, MAX_RESULT_TEXT, OPENROUTER_FALLBACK, priceText } = await import('../lib/chat.ts');
const { owedResults, runTurn } = await import('../lib/server/chat/agent.ts');
const { callTool, instructions, listTools, toolAnswer } = await import('../lib/server/chat/mcp.ts');
const { skillText, systemPrompt } = await import('../lib/server/chat/prompt.ts');
const { keyRefusal } = await import('../lib/server/apiKeys.ts');
const { checkKey, clientFor, KeyCheckError, MAX_TOKENS, modelParams, providerFailure, withoutImages } = await import('../lib/server/chat/providers.ts');
const { openRouterCatalog, openRouterModel, openRouterModels } = await import('../lib/server/chat/models.ts');

type Msg = { role: 'user' | 'assistant'; content: any };
const usage = { input_tokens: 10, output_tokens: 5, cache_creation_input_tokens: 0, cache_read_input_tokens: 90 };
const answer = (stop_reason: string, ...content: any[]) => ({ content, stop_reason, usage, stop_details: null });
const text = (t: string) => ({ type: 'text', text: t });
const use = (id: string, name = 'build', input: unknown = {}) => ({ type: 'tool_use', id, name, input });

/** A turn against scripted answers from the model; records what was stored, emitted and called. */
function turn(answers: any[], over: { maxRounds?: number; refusal?: () => string | null; abort?: AbortController; onTool?: () => void } = {}) {
  const messages: Msg[] = [{ role: 'user', content: 'build a hut' }];
  const stored: Msg[] = [], events: any[] = [], calls: string[] = [], asked: any[] = [];
  const abort = over.abort ?? new AbortController();
  const end = runTurn({ model: 'm', params: { max_tokens: 1000, thinking: { type: 'adaptive' } }, system: 's', tools: [], messages: messages as any, maxRounds: over.maxRounds ?? 10, signal: abort.signal }, {
    async ask(params: any, onText: (t: string) => void) {
      asked.push({ ...params, messages: [...params.messages] });
      const next = answers.shift();
      if (next instanceof Error) throw next;
      for (const b of next.content) if (b.type === 'text') onText(b.text);
      return next;
    },
    async callTool(name: string) {
      calls.push(name);
      over.onTool?.();
      return { content: [{ type: 'text', text: '{"ok":true}' }], isError: false };
    },
    refusal: async () => over.refusal?.() ?? null,
    append: async (m: any) => { stored.push(m); },
    emit: (e: any) => { events.push(e); },
  } as any);
  return { end, messages, stored, events, calls, asked };
}

test('a turn runs tools until Claude is done, storing every message it sends back', async () => {
  const t = turn([answer('tool_use', text('Building.'), use('a'), use('b', 'check_region')), answer('end_turn', text('Done at 10 64 10.'))]);
  assert.deepEqual(await t.end, { reason: 'done' });
  assert.deepEqual(t.calls, ['build', 'check_region']);
  assert.deepEqual(t.stored.map(m => m.role), ['assistant', 'user', 'assistant']);
  // Both answers go back in one message, in the order of the calls.
  assert.deepEqual(t.stored[1].content.map((r: any) => r.tool_use_id), ['a', 'b']);
  assert.deepEqual(t.messages.slice(1), t.stored);
  assert.equal(t.asked[1].messages.length, 3);
  // The model's own fields go with every request.
  assert.equal(t.asked[0].max_tokens, 1000);
  assert.deepEqual(t.asked[1].thinking, { type: 'adaptive' });
  assert.deepEqual(t.events.filter(e => e.type === 'usage').at(-1), { type: 'usage', input: 200, output: 10 });
  assert.deepEqual(itemsOf(t.messages).map(i => i.kind), ['user', 'assistant', 'tool', 'tool', 'assistant']);
});

test('the events of a turn show what its stored messages show', async () => {
  const t = turn([answer('tool_use', text('One. '), use('a')), answer('end_turn', text('Two.'))]);
  await t.end;
  const live = t.events.reduce(applyEvent, [{ kind: 'user', text: 'build a hut' }]);
  assert.deepEqual(live, itemsOf(t.messages));
});

test('a turn stops at its round limit with every call answered', async () => {
  const t = turn([answer('tool_use', use('a')), answer('tool_use', use('b')), answer('tool_use', use('c'))], { maxRounds: 2 });
  assert.deepEqual(await t.end, { reason: 'limit' });
  assert.equal(t.calls.length, 2);
  assert.equal(t.stored.at(-1)!.role, 'user');
});

test('stopping during a tool call answers the rest without running them', async () => {
  const abort = new AbortController();
  const t = turn([answer('tool_use', use('a'), use('b'))], { abort, onTool: () => abort.abort() });
  assert.deepEqual(await t.end, { reason: 'stopped' });
  assert.deepEqual(t.calls, ['build']);
  const results = t.stored.at(-1)!.content;
  assert.equal(results.length, 2);
  assert.equal(results[1].is_error, true);
  assert.equal(owedResults(t.messages as any), null);
});

test('a request that fails because the user stopped is a stop, any other failure is thrown', async () => {
  const abort = new AbortController();
  abort.abort();
  assert.deepEqual(await turn([], { abort }).end, { reason: 'stopped' });
  await assert.rejects(turn([new Error('boom')]).end, /boom/);
});

test('a user who lost the builder role gets no tool run', async () => {
  const t = turn([answer('tool_use', use('a'))], { refusal: () => 'you are no longer a builder here' });
  assert.deepEqual(await t.end, { reason: 'error', error: 'you are no longer a builder here' });
  assert.deepEqual(t.calls, []);
  assert.equal(t.stored.at(-1)!.content[0].is_error, true);
});

test('a refusal and a cut-off tool call store nothing and run nothing', async () => {
  const refused = turn([{ ...answer('refusal', use('a')), stop_details: { explanation: 'no' } }]);
  assert.deepEqual(await refused.end, { reason: 'refused', error: 'no' });
  const cut = turn([answer('max_tokens', use('a'))]);
  assert.equal((await cut.end).reason, 'error');
  for (const t of [refused, cut]) {
    assert.deepEqual(t.stored, []);
    assert.deepEqual(t.calls, []);
  }
});

test('owedResults answers tool calls a restart left open', () => {
  assert.equal(owedResults([{ role: 'user', content: 'hi' }]), null);
  assert.equal(owedResults([{ role: 'assistant', content: [text('hi')] }] as any), null);
  const owed = owedResults([{ role: 'assistant', content: [text('x'), use('a'), use('b')] }] as any)!;
  assert.deepEqual((owed.content as any[]).map(r => [r.tool_use_id, r.is_error]), [['a', true], ['b', true]]);
});

test('itemsOf cuts long tool text and shows a render', () => {
  const items = itemsOf([
    { role: 'assistant', content: [{ type: 'thinking', thinking: '', signature: 'x' }, use('a', 'render_region', { region: [0, 0, 0, 1, 1, 1] })] },
    { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'a', content: [
      { type: 'image', source: { type: 'base64', media_type: 'image/png', data: 'AAAA' } }, { type: 'text', text: 'x'.repeat(MAX_RESULT_TEXT + 10) }] }] },
  ]);
  assert.equal(items.length, 1);
  const tool = items[0] as any;
  assert.equal(tool.result.image, 'data:image/png;base64,AAAA');
  assert.equal(tool.result.text.length, MAX_RESULT_TEXT + 1);
  assert.equal(tool.result.error, false);
});

test('the MCP client lists tools, reads instructions and turns failures into answers', async () => {
  const sent: any[] = [];
  const send = async (body: any) => {
    sent.push(body);
    if (body.method === 'initialize') return { status: 200, body: { result: { instructions: 'Start with get_context.' } } };
    if (body.method === 'tools/list') return { status: 200, body: { result: { tools: [{ name: 'build', description: 'Edits.', inputSchema: { type: 'object', properties: {} } }] } } };
    if (body.params.name === 'gone') return { status: 200, body: { error: { code: -32602, message: 'unknown tool: gone' } } };
    return { status: 200, body: { result: { isError: false, content: [{ type: 'image', data: 'AAAA', mimeType: 'image/png' }, { type: 'text', text: '{"placed":true}' }] } } };
  };
  assert.equal(await instructions(send), 'Start with get_context.');
  assert.deepEqual(await listTools(send), [{ name: 'build', description: 'Edits.', input_schema: { type: 'object', properties: {} } }]);
  const ok = await callTool(send, 'build', { ops: [] });
  assert.deepEqual(sent.at(-1).params, { name: 'build', arguments: { ops: [] } });
  assert.deepEqual((ok.content as any[]).map(c => c.type), ['image', 'text']);
  assert.deepEqual(await callTool(send, 'gone', {}), { content: 'unknown tool: gone', isError: true });
  const offline = async () => { throw Object.assign(new Error("server 's' is not connected"), { code: 'OFFLINE' }); };
  assert.deepEqual(await callTool(offline, 'build', {}), { content: "server 's' is not connected", isError: true });
  await assert.rejects(listTools(offline), /not connected/);
  assert.deepEqual(toolAnswer({}), { content: [{ type: 'text', text: '{}' }], isError: false });
});

test('the system prompt keeps the skills but nothing about scripts or subagents', () => {
  const skills = ['build', 'build-style'].map(n => readFileSync(new URL(`../../.claude/skills/${n}/SKILL.md`, import.meta.url), 'utf8'));
  const prompt = systemPrompt('- Start with get_context.\n- Large or repetitive builds: write a script with the aibuilder Python SDK instead of many calls.', skills);
  assert.match(prompt, /Start with get_context/);
  assert.match(prompt, /check_region/);
  assert.match(prompt, /## 4\. Phases and checks/);
  assert.doesNotMatch(prompt.replace('no shell, files, scripts or subagents', ''), /Vox|Python|subagent|\bscripts?\b|^---$/im);
  assert.equal(skillText('---\nname: x\n---\n# T\nkeep\n## Scripts\ndrop\n## Next\nkeep too'), '# T\nkeep\n## Next\nkeep too');
});

test('keyRefusal takes each provider\u2019s keys and points a key to its own provider', () => {
  const ant = 'sk-ant-api03-' + 'a'.repeat(40), or = 'sk-or-v1-' + '0'.repeat(64);
  assert.equal(keyRefusal('anthropic', ant), null);
  assert.equal(keyRefusal('openrouter', or), null);
  assert.match(keyRefusal('anthropic', or)!, /an OpenRouter key; add it under OpenRouter/);
  assert.match(keyRefusal('openrouter', ant)!, /an Anthropic key; add it under Anthropic/);
  assert.match(keyRefusal('anthropic', 'hello')!, /does not look like an Anthropic API key/);
  assert.match(keyRefusal('openrouter', 'hello')!, /does not look like an OpenRouter API key \(they start with sk-or-\)/);
  assert.match(keyRefusal('anthropic', 'sk-ant-admin01-' + 'a'.repeat(40))!, /Admin API key/);
});

test('OpenRouter offers the same Claude models when its catalog cannot be read', () => {
  assert.deepEqual(OPENROUTER_FALLBACK.map(m => m.name), ANTHROPIC_MODELS.map(m => m.name));
  assert.equal(ANTHROPIC_MODELS[0].id, DEFAULT_MODEL.anthropic);
  assert.equal(OPENROUTER_FALLBACK[0].id, DEFAULT_MODEL.openrouter);
  assert.ok([...ANTHROPIC_MODELS, ...OPENROUTER_FALLBACK].every(m => m.claude && m.adaptive && m.images));
});

const entry = (id: string, over: Record<string, unknown> = {}) => ({
  id, name: id, context_length: 200_000, pricing: { prompt: '0.000003', completion: '0.000015' },
  architecture: { input_modalities: ['text', 'image'] }, top_provider: { max_completion_tokens: 32_000 },
  supported_parameters: ['tools', 'max_tokens'], endpoints: ['/api/v1/chat/completions', '/api/v1/messages'], ...over,
});

test('the OpenRouter catalog keeps models that can call tools on the Messages API, Claude first', () => {
  const models = openRouterModels({ data: [
    entry('openai/gpt-6'),
    entry('anthropic/claude-opus-5.5', { reasoning: { supported_efforts: ['max', 'high', 'low'] } }),
    entry('anthropic/claude-opus-4.1'),
    entry('qwen/no-tools', { supported_parameters: ['max_tokens'] }),
    entry('openai/gpt-6:batch'),
    entry('meta/chat-only', { endpoints: ['/api/v1/chat/completions'] }),
    entry('openrouter/auto', { pricing: { prompt: '-1', completion: '-1' }, architecture: { input_modalities: ['text'] }, top_provider: {} }),
  ] });
  assert.deepEqual(models.map(m => m.id), ['anthropic/claude-opus-5.5', 'anthropic/claude-opus-4.1', 'openai/gpt-6', 'openrouter/auto']);
  const [opus, old, gpt, auto] = models;
  assert.deepEqual([opus.claude, opus.adaptive, old.claude, old.adaptive, gpt.claude, gpt.adaptive], [true, true, true, false, false, false]);
  assert.deepEqual(gpt.price, { input: 3, output: 15 });
  assert.equal(gpt.maxOutput, 32_000);
  assert.equal(gpt.images, true);
  assert.deepEqual([auto.price, auto.images, auto.maxOutput], [null, false, null]);
  assert.equal(openRouterModel({}), null);
  assert.deepEqual(openRouterModels(null), []);
});

test('priceText reads prices per million tokens', () => {
  const m = (price: unknown) => ({ ...ANTHROPIC_MODELS[0], price }) as any;
  assert.equal(priceText(m({ input: 4, output: 20 })), '$4 / $20 per million tokens');
  assert.equal(priceText(m({ input: 0.1, output: 0.25 })), '$0.1 / $0.25 per million tokens');
  assert.equal(priceText(m({ input: 0, output: 0 })), 'free');
  assert.equal(priceText(m(null)), 'price varies');
});

test('the catalog is read once in a while, and the Claude models stand in when it cannot be read', async () => {
  const g = globalThis as any;
  g.__aibOpenRouterModels = undefined;
  let asked = 0;
  const ok = (async () => { asked++; return new Response(JSON.stringify({ data: [entry('openai/gpt-6')] })); }) as unknown as typeof fetch;
  assert.deepEqual((await openRouterCatalog(ok)).models.map(m => m.id), ['openai/gpt-6']);
  await openRouterCatalog(ok);
  assert.equal(asked, 1);
  g.__aibOpenRouterModels = undefined;
  const down = (async () => new Response('', { status: 503 })) as unknown as typeof fetch;
  const r = await openRouterCatalog(down);
  assert.equal(r.stale, true);
  assert.deepEqual(r.models.map(m => m.id), OPENROUTER_FALLBACK.map(m => m.id));
  g.__aibOpenRouterModels = undefined;
});

test('each model gets only the request fields it takes', () => {
  const [opusDirect] = ANTHROPIC_MODELS, [opusVia] = OPENROUTER_FALLBACK;
  const [old, gpt] = openRouterModels({ data: [entry('anthropic/claude-opus-4.1'), entry('openai/gpt-6', { top_provider: { max_completion_tokens: 200_000 } })] });
  assert.deepEqual(modelParams(opusDirect), {
    max_tokens: MAX_TOKENS, thinking: { type: 'adaptive' }, output_config: { effort: 'high' }, cache_control: { type: 'ephemeral' },
  });
  assert.deepEqual(modelParams(opusVia), { ...modelParams(opusDirect), provider: { order: ['anthropic'] } });
  assert.deepEqual(modelParams(old), { max_tokens: 32_000, cache_control: { type: 'ephemeral' }, provider: { order: ['anthropic'] } });
  assert.deepEqual(modelParams(gpt), { max_tokens: MAX_TOKENS });
});

test('a model that cannot see images gets a note for each render, and nothing else changes', () => {
  const image = { type: 'image', source: { type: 'base64', media_type: 'image/png', data: 'AAAA' } };
  const messages: any[] = [
    { role: 'user', content: 'build' },
    { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'a', content: [image, { type: 'text', text: '{}' }] }] },
    { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'b', content: 'plain' }] },
  ];
  const out = withoutImages(messages) as any[];
  assert.equal(out[0], messages[0]);
  assert.equal(out[2], messages[2]);
  assert.deepEqual(out[1].content[0].content.map((c: any) => c.type), ['text', 'text']);
  assert.match(out[1].content[0].content[0].text, /cannot see images/);
  assert.equal(messages[1].content[0].content[0], image);
});

/** A fetch that records each request and answers with `status` and `body`. */
function recorder(status = 200, body: unknown = {}) {
  const requests: { url: string; headers: Headers; body: any }[] = [];
  const fetchImpl = async (url: string | URL | Request, init?: RequestInit) => {
    requests.push({ url: String(url), headers: new Headers(init?.headers), body: init?.body ? JSON.parse(String(init.body)) : null });
    return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
  };
  return { requests, fetchImpl: fetchImpl as typeof fetch };
}

const reply = { id: 'msg_1', type: 'message', role: 'assistant', model: 'm', content: [{ type: 'text', text: 'hi' }],
  stop_reason: 'end_turn', stop_sequence: null, usage: { input_tokens: 1, output_tokens: 1 } };

test('a provider\u2019s client sends the user\u2019s key to that provider only, whatever the panel\u2019s environment says', async () => {
  const saved = { ...process.env };
  Object.assign(process.env, { ANTHROPIC_API_KEY: 'sk-ant-panel', ANTHROPIC_AUTH_TOKEN: 'panel-token', ANTHROPIC_BASE_URL: 'https://elsewhere.example' });
  try {
    // A small max_tokens: the SDK insists on streaming for a large one, and this asks without.
    const params = (model: any) => ({ ...modelParams(model), max_tokens: 10, model: model.id, messages: [{ role: 'user' as const, content: 'hi' }] }) as any;
    const a = recorder(200, reply);
    await clientFor('anthropic', 'sk-ant-user', a.fetchImpl).messages.create(params(ANTHROPIC_MODELS[0]));
    const o = recorder(200, reply);
    await clientFor('openrouter', 'sk-or-user', o.fetchImpl).messages.create(params(OPENROUTER_FALLBACK[0]));
    assert.equal(a.requests[0].url, 'https://api.anthropic.com/v1/messages');
    assert.equal(o.requests[0].url, 'https://openrouter.ai/api/v1/messages');
    for (const [r, key] of [[a, 'sk-ant-user'], [o, 'sk-or-user']] as const) {
      const sent = [...r.requests[0].headers.values()].join(' ');
      assert.match(sent, new RegExp(key));
      assert.doesNotMatch(sent, /sk-ant-panel|panel-token/);
    }
    assert.equal(a.requests[0].body.provider, undefined);
    assert.deepEqual(o.requests[0].body.provider, { order: ['anthropic'] });
  } finally {
    process.env = saved;
  }
});

test('checkKey asks each provider without spending and tells a refused key from an unreachable provider', async () => {
  const ok = recorder(200, { data: { label: 'x' } });
  await checkKey('openrouter', 'sk-or-user', ok.fetchImpl);
  assert.equal(ok.requests[0].url, 'https://openrouter.ai/api/v1/key');
  assert.equal(ok.requests[0].headers.get('authorization'), 'Bearer sk-or-user');
  const models = recorder(200, { data: [], has_more: false, first_id: null, last_id: null });
  await checkKey('anthropic', 'sk-ant-user', models.fetchImpl);
  assert.match(models.requests[0].url, /^https:\/\/api\.anthropic\.com\/v1\/models/);
  for (const provider of ['anthropic', 'openrouter'] as const) {
    await assert.rejects(checkKey(provider, 'k', recorder(401, { error: { type: 'authentication_error', message: 'no' } }).fetchImpl),
      (e: any) => e instanceof KeyCheckError && e.kind === 'refused');
    await assert.rejects(checkKey(provider, 'k', recorder(500, { error: { type: 'api_error', message: 'down' } }).fetchImpl),
      (e: any) => e instanceof KeyCheckError && e.kind === 'unavailable');
  }
});

test('provider errors are explained with the provider\u2019s name', async () => {
  const fail = (status: number) => clientFor('openrouter', 'k', recorder(status, { error: { type: 'x', message: 'm' } }).fetchImpl)
    .messages.create({ model: 'm', max_tokens: 1, messages: [{ role: 'user', content: 'hi' }] }, { maxRetries: 0 }).catch(e => e);
  assert.match(providerFailure('openrouter', await fail(401))!, /^OpenRouter no longer accepts your API key/);
  assert.match(providerFailure('openrouter', await fail(402))!, /OpenRouter account has run out of credit/);
  assert.equal(providerFailure('openrouter', new Error('other')), null);
});
