import type { IncomingMessage, ServerResponse } from 'node:http';
import { roleOf, serverExists } from './access';
import { accessForToken } from './accessTokens';
import { hub, type Json } from './hub';
import { atLeast } from './roles';
import { bearer } from './tokens';

/** MCP tool calls wait up to the plugin's job-wait-seconds (30 s by default) and renders can be slow. */
export const TOOL_TIMEOUT_MS = 150_000;
const MAX_BODY = 16 * 1024 * 1024;

/**
 * The plugin's own endpoints under /s/<server>/, so AIBUILDER_URL=https://<app>/s/<server> works for Claude Code
 * and the Python SDK unchanged: mcp, api/tools, api/<tool>, api/health. An access token opens the one server it was
 * made for, while its user is builder or above there.
 */
export async function handlePluginProxy(req: IncomingMessage, res: ServerResponse): Promise<void> {
  const url = new URL(req.url || '/', 'http://x');
  const m = /^\/s\/([a-z0-9]{1,16})\/(mcp|api\/[A-Za-z0-9_]+)\/?$/.exec(url.pathname);
  if (!m) return send(res, 404, { error: 'not found' });
  const [, serverId, rest] = m;

  if (rest === 'api/health') {
    const ok = await serverExists(serverId);
    return send(res, ok ? 200 : 404, { ok });
  }

  const token = bearer(req.headers.authorization);
  const access = token ? await accessForToken(token) : null;
  if (!access) {
    res.setHeader('WWW-Authenticate', 'Bearer');
    return send(res, 401, { error: "missing or wrong access token (copy this server's details from Connect your AI on its page in the panel)" });
  }
  if (access.serverId !== serverId) {
    return send(res, 403, { error: "this access token is for another server; each server has its own (Connect your AI on the server's page)" });
  }
  const role = await roleOf(access.userId, serverId);
  // Same answer for "no such server" and "not yours", so tokens can't probe for server ids.
  if (!role) return send(res, 404, { error: `unknown server '${serverId}'` });
  if (!atLeast(role, 'builder')) return send(res, 403, { error: `your role on '${serverId}' is ${role}; building needs builder` });

  let frame: Json;
  if (rest === 'mcp') {
    if (req.method !== 'POST') {
      res.setHeader('Allow', 'POST');
      return send(res, 405, null);
    }
    const text = await readBody(req, res);
    if (text === null) return;
    let body: unknown;
    try {
      body = JSON.parse(text);
    } catch {
      return send(res, 400, { jsonrpc: '2.0', id: null, error: { code: -32700, message: 'parse error' } });
    }
    frame = { t: 'mcp', body };
  } else if (rest === 'api/tools' && req.method === 'GET') {
    frame = { t: 'api', tool: 'tools' };
  } else if (req.method === 'POST') {
    const text = await readBody(req, res);
    if (text === null) return;
    let args: unknown = {};
    if (text.trim()) {
      try {
        args = JSON.parse(text);
      } catch {
        args = null;
      }
      if (!args || typeof args !== 'object' || Array.isArray(args)) return send(res, 400, { error: 'body must be a JSON object' });
    }
    frame = { t: 'api', tool: rest.slice('api/'.length), args };
  } else {
    res.setHeader('Allow', 'POST');
    return send(res, 405, null);
  }

  try {
    const reply = await hub().requestAsUser(serverId, frame, access.userId, TOOL_TIMEOUT_MS);
    send(res, reply.status, reply.body);
  } catch (e) {
    send(res, ...relayError(e));
  }
}

/** The status and body for a failed relay request. */
export function relayError(e: unknown): [number, Json] {
  const code = (e as { code?: unknown } | null)?.code;
  const msg = e instanceof Error ? e.message : String(e);
  if (code === 'OFFLINE') return [503, { error: msg, offline: true }];
  if (code === 'TIMEOUT') return [504, { error: msg }];
  console.warn('relay request failed', e);
  return [502, { error: 'relay failed: ' + String(e) }];
}

function send(res: ServerResponse, status: number, body: unknown) {
  res.statusCode = status;
  if (body === null || body === undefined) {
    res.end();
    return;
  }
  res.setHeader('Content-Type', 'application/json');
  res.end(JSON.stringify(body));
}

/** The request body as text, or null after answering 413 when it is too large. */
function readBody(req: IncomingMessage, res: ServerResponse): Promise<string | null> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    req.on('data', (c: Buffer) => {
      size += c.length;
      if (size > MAX_BODY) {
        req.destroy();
        send(res, 413, { error: 'body too large' });
        resolve(null);
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}
