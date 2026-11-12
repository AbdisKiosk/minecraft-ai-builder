// Only server.ts imports this: Next's bundler would trace the whole project for the directory read.
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { exec, query } from './db';

/**
 * Applies the files in migrations/ that have not run yet, in name order, and records each in schema_migrations.
 * A file is one or more statements separated by `;` at the end of a line. Never edit a file that has run; add one.
 */
export async function migrate(dir = path.join(process.cwd(), 'migrations')): Promise<string[]> {
  await exec(`CREATE TABLE IF NOT EXISTS schema_migrations (
    name VARCHAR(191) PRIMARY KEY, applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)`);
  const done = new Set((await query<{ name: string }>('SELECT name FROM schema_migrations')).map(r => r.name));
  const files = (await readdir(dir)).filter(f => f.endsWith('.sql')).sort();
  const applied: string[] = [];
  for (const file of files) {
    if (done.has(file)) continue;
    const text = await readFile(path.join(dir, file), 'utf8');
    const statements = text.replace(/^--.*$/gm, '').split(/;\s*$/m).map(s => s.trim()).filter(Boolean);
    // MySQL commits DDL implicitly, so a failed file can leave part of it applied; keep one change per file.
    for (const s of statements) await exec(s);
    await exec('INSERT INTO schema_migrations (name) VALUES (?)', [file]);
    applied.push(file);
  }
  return applied;
}
