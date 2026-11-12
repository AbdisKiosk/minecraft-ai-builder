import assert from 'node:assert/strict';
import { mkdir, mkdtemp, utimes, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { findPluginJar, jarDirs } from '../lib/server/plugin-jar.ts';

test('finds the newest plugin jar in the first directory that has one', async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'aib-jar-'));
  const empty = path.join(root, 'empty'), libs = path.join(root, 'libs');
  await mkdir(empty);
  await mkdir(libs);
  await writeFile(path.join(libs, 'AIBuilder-0.1.0.jar'), 'old');
  await writeFile(path.join(libs, 'AIBuilder-0.2.0-SNAPSHOT.jar'), 'newer');
  await writeFile(path.join(libs, 'worldedit-bukkit-7.3.19.jar'), 'not ours');
  await mkdir(path.join(libs, 'AIBuilder-9.9.9.jar'));
  await utimes(path.join(libs, 'AIBuilder-0.1.0.jar'), 1000, 1000);
  await utimes(path.join(libs, 'AIBuilder-0.2.0-SNAPSHOT.jar'), 2000, 2000);

  const jar = await findPluginJar([path.join(root, 'missing'), empty, libs]);
  assert.equal(jar?.name, 'AIBuilder-0.2.0-SNAPSHOT.jar');
  assert.equal(jar?.size, 5);
  assert.equal(jar?.file, path.join(libs, 'AIBuilder-0.2.0-SNAPSHOT.jar'));
  assert.equal(await findPluginJar([path.join(root, 'missing'), empty]), null);
});

test('looks in the image directory, then a checkout, unless PLUGIN_JAR_DIR is set', () => {
  assert.deepEqual(jarDirs('/app', {}), ['/app/plugin-jar', path.join('/app', '..', 'plugin', 'core', 'build', 'libs')]);
  assert.deepEqual(jarDirs('/app', { PLUGIN_JAR_DIR: 'jars' }), ['/app/jars']);
  assert.deepEqual(jarDirs('/app', { PLUGIN_JAR_DIR: '/srv/jars' }), ['/srv/jars']);
});
