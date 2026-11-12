import JSZip from 'jszip';

/**
 * Recent clients can describe a texture as { sprite, force_translucent } instead of a string. Viewer 5 expects
 * strings. Supply just those model JSON overrides ahead of the unmodified client jar, without copying textures.
 */
export async function modelOverrides(jar: ArrayBuffer): Promise<Blob | null> {
  const zip = await JSZip.loadAsync(jar);
  const overrides = new JSZip();
  let changed = false;
  for (const file of Object.values(zip.files)) {
    if (file.dir || !file.name.startsWith('assets/minecraft/models/block/') || !file.name.endsWith('.json')) continue;
    const model = JSON.parse(await file.async('string'));
    let edited = false;
    for (const [key, texture] of Object.entries(model.textures ?? {})) {
      if (texture && typeof texture === 'object' && 'sprite' in texture && typeof texture.sprite === 'string') {
        model.textures[key] = texture.sprite;
        edited = true;
      }
    }
    if (edited) {
      overrides.file(file.name, JSON.stringify(model));
      changed = true;
    }
  }
  return changed ? overrides.generateAsync({ type: 'blob', compression: 'DEFLATE' }) : null;
}
