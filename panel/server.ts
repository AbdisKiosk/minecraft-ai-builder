// The app's entry point: one Node process serves the Next.js pages and API routes, the plugin endpoints under
// /s/<server>/, the plugin jar's download and the plugins' relay WebSocket. Next.js alone can't hold WebSockets,
// hence this custom server.
import nextEnv from '@next/env';
import { createServer } from 'node:http';
import next from 'next';
import { migrate } from './lib/server/migrate';
import { installHub, RELAY, RelayHub } from './lib/server/hub';
import { PLUGIN_JAR_HREF } from './lib/pluginJar';
import { handlePluginJar } from './lib/server/plugin-jar';
import { handlePluginProxy } from './lib/server/plugin-proxy';

const dev = process.env.NODE_ENV !== 'production';
nextEnv.loadEnvConfig(process.cwd(), dev);

const host = process.env.HOST || (dev ? '127.0.0.1' : '0.0.0.0');
const port = Number(process.env.PORT || 3000);

const applied = await migrate();
if (applied.length) console.info(`database: applied ${applied.join(', ')}`);

const hub = new RelayHub();
installHub(hub);

const app = next({ dev, hostname: host, port });
const handle = app.getRequestHandler();
await app.prepare();

const server = createServer((req, res) => {
  if (req.url?.startsWith('/s/')) {
    handlePluginProxy(req, res).catch(e => {
      console.error('plugin proxy failed', e);
      if (!res.headersSent) res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'internal error' }));
    });
    return;
  }
  if (req.url?.split('?')[0] === PLUGIN_JAR_HREF) {
    handlePluginJar(req, res).catch(e => {
      console.error('plugin jar download failed', e);
      if (!res.headersSent) res.writeHead(500);
      res.end();
    });
    return;
  }
  handle(req, res);
});

// Next attaches its own 'upgrade' listener to this server on the first request (hot reload at /_next/hmr) and leaves
// other paths alone, so only /relay is ours. Handing upgrades to Next here as well would answer each one twice.
server.on('upgrade', (req, socket, head) => {
  if ((req.url || '').split('?')[0] === RELAY.PATH) hub.upgrade(req, socket, head);
});

// Claude's tool calls can wait up to 150 s for a long job.
server.requestTimeout = 0;
server.headersTimeout = 65_000;
server.keepAliveTimeout = 65_000;

server.listen(port, host, () => console.info(`AIBuilder panel on http://${host}:${port} (${dev ? 'dev' : 'production'})`));

for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(signal, () => {
    hub.closeAll();
    server.close();
    setTimeout(() => process.exit(0), 2000).unref();
  });
}
