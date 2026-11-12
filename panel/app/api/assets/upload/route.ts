import { answer, deps } from '../../../../lib/server/assetStore';
import { readUpload, uploadSchematic } from '../../../../lib/server/schem';
import { requireUser, route } from '../../../../lib/server/route';

/**
 * Uploads a WorldEdit .schem (Sponge version 2 or 3) as an asset: a multipart form with `file` (at most 8 MB), `name`,
 * `description` and `tags` (comma-separated). Only the blocks are kept. Needs no server role. Answers 201 with the asset.
 */
export const POST = route(async req => {
  const user = await requireUser();
  return answer(async () => {
    const { fields, file } = await readUpload(req);
    return uploadSchematic(deps(user), user.id, fields, file);
  }, 201);
});
