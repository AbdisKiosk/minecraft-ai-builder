import type { PluginJar } from './plugin-jar';

// server.ts installs the jar lookup, so API routes can use it without Next's bundler tracing plugin-jar.ts's
// directory read (see the note there).

type Find = () => Promise<PluginJar | null>;
const g = globalThis as { __aibFindJar?: Find };

export function installJarSource(find: Find) {
  g.__aibFindJar = find;
}

export async function findJar(): Promise<PluginJar | null> {
  if (!g.__aibFindJar) throw new Error('start the app with server.ts (npm run dev / npm start), not next dev');
  return g.__aibFindJar();
}
