import mysql, { type Pool, type PoolConnection, type ResultSetHeader, type RowDataPacket } from 'mysql2/promise';

// One pool per process: the custom server and Next's route bundles both import this module.
const g = globalThis as { __aibPool?: Pool };

export function pool(): Pool {
  if (!g.__aibPool) {
    const uri = process.env.DATABASE_URL;
    if (!uri) throw new Error('DATABASE_URL is not set');
    const p = mysql.createPool({ uri, connectionLimit: 10, enableKeepAlive: true, timezone: 'Z', dateStrings: false });
    // Every DATETIME is UTC: mysql2 reads them as UTC ('Z'), so NOW() and CURRENT_TIMESTAMP must be UTC too, whatever
    // the server's own time zone is. Runs before the connection's first query.
    // The event hands over the callback-style connection, not the promise wrapper.
    p.on('connection', c => {
      (c as unknown as { query(sql: string, cb: (e: Error | null) => void): void })
        .query("SET time_zone = '+00:00'", e => { if (e) console.error('db: could not set time zone', e); });
    });
    g.__aibPool = p;
  }
  return g.__aibPool;
}

type Params = (string | number | boolean | Date | null)[];
type Runner = Pool | PoolConnection;

/** Rows of a SELECT. */
export async function query<T>(sql: string, params: Params = [], on: Runner = pool()): Promise<T[]> {
  const [rows] = await on.query<RowDataPacket[]>(sql, params);
  return rows as T[];
}

/** The first row of a SELECT, or null. */
export async function one<T>(sql: string, params: Params = [], on: Runner = pool()): Promise<T | null> {
  return (await query<T>(sql, params, on))[0] ?? null;
}

/** An INSERT, UPDATE or DELETE. */
export async function exec(sql: string, params: Params = [], on: Runner = pool()): Promise<ResultSetHeader> {
  const [result] = await on.query<ResultSetHeader>(sql, params);
  return result;
}

/** Runs `work` in a transaction on one connection. */
export async function transaction<T>(work: (c: PoolConnection) => Promise<T>): Promise<T> {
  const c = await pool().getConnection();
  try {
    await c.beginTransaction();
    const out = await work(c);
    await c.commit();
    return out;
  } catch (e) {
    await c.rollback();
    throw e;
  } finally {
    c.release();
  }
}
