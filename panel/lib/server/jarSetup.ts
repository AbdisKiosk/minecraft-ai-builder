import { crc32 } from 'node:zlib';

// The plugin jar a server owner downloads carries the server's relay settings, so installing it is all the setup there
// is. The plugin reads this entry on start (JarSetup.java) and writes it into its config.yml.

export const SETUP_ENTRY = 'aibuilder-setup.properties';

export function setupProperties(url: string, token: string): string {
  return `# This server's connection to the AIBuilder panel. Keep this jar private: it contains the server's key.\nurl=${url}\ntoken=${token}\n`;
}

/**
 * The name of a server's download: AIBuilder-<server>-<commit>.jar, the commit coming from the built jar's name
 * (AIBuilder-<version>+<commit>.jar, see plugin/core/build.gradle). ASCII only, so it fits a plain Content-Disposition.
 */
export function downloadName(serverName: string, jarName: string): string {
  const server = serverName.normalize('NFKD').replace(/[^A-Za-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40)
    .replace(/-+$/, '');
  const commit = /\+([0-9A-Za-z.-]+)\.jar$/.exec(jarName)?.[1];
  return ['AIBuilder', server, commit].filter(Boolean).join('-') + '.jar';
}

const EOCD =0x06054b50, CEN = 0x02014b50, LOC = 0x04034b50;

/**
 * The jar with one more stored (uncompressed) file at the end. The original entries keep their bytes and offsets;
 * only the central directory is rewritten after the new entry. Zip64 archives are refused.
 */
export function addJarEntry(jar: Buffer, name: string, data: Buffer): Buffer {
  // The end-of-central-directory record is 22 bytes plus a comment of up to 64 KiB.
  let end = -1;
  for (let i = jar.length - 22; i >= Math.max(0, jar.length - 22 - 0xffff); i--) {
    if (jar.readUInt32LE(i) === EOCD && i + 22 + jar.readUInt16LE(i + 20) === jar.length) { end = i; break; }
  }
  if (end < 0) throw new Error('not a zip file');
  const entries = jar.readUInt16LE(end + 10);
  const cdSize = jar.readUInt32LE(end + 12);
  const cdOffset = jar.readUInt32LE(end + 16);
  if (entries === 0xffff || cdSize === 0xffffffff || cdOffset === 0xffffffff) throw new Error('zip64 jars are not supported');
  if (cdOffset + cdSize !== end) throw new Error('unexpected data between the central directory and its end record');

  const fileName = Buffer.from(name, 'utf8');
  const crc = crc32(data);
  // 1980-01-01 00:00, the earliest DOS time: the entry's content, not its date, is what matters.
  const time = 0, date = (0 << 9) | (1 << 5) | 1;

  const local = Buffer.alloc(30);
  local.writeUInt32LE(LOC, 0);
  local.writeUInt16LE(10, 4); // version needed
  local.writeUInt16LE(0x0800, 6); // UTF-8 name
  local.writeUInt16LE(0, 8); // stored
  local.writeUInt16LE(time, 10);
  local.writeUInt16LE(date, 12);
  local.writeUInt32LE(crc, 14);
  local.writeUInt32LE(data.length, 18);
  local.writeUInt32LE(data.length, 22);
  local.writeUInt16LE(fileName.length, 26);
  local.writeUInt16LE(0, 28);

  const central = Buffer.alloc(46);
  central.writeUInt32LE(CEN, 0);
  central.writeUInt16LE(20, 4); // made by
  central.writeUInt16LE(10, 6);
  central.writeUInt16LE(0x0800, 8);
  central.writeUInt16LE(0, 10);
  central.writeUInt16LE(time, 12);
  central.writeUInt16LE(date, 14);
  central.writeUInt32LE(crc, 16);
  central.writeUInt32LE(data.length, 20);
  central.writeUInt32LE(data.length, 24);
  central.writeUInt16LE(fileName.length, 28);
  // extra, comment, disk, internal and external attributes stay 0
  central.writeUInt32LE(cdOffset, 42); // the new entry starts where the old central directory did

  const added = local.length + fileName.length + data.length;
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(EOCD, 0);
  eocd.writeUInt16LE(entries + 1, 8);
  eocd.writeUInt16LE(entries + 1, 10);
  eocd.writeUInt32LE(cdSize + central.length + fileName.length, 12);
  eocd.writeUInt32LE(cdOffset + added, 16);

  return Buffer.concat([
    jar.subarray(0, cdOffset), local, fileName, data,
    jar.subarray(cdOffset, end), central, fileName, eocd,
  ]);
}
