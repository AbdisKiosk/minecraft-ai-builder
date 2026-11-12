import { answer, deps } from '../../../../../lib/server/assetStore';
import { assetBlocks } from '../../../../../lib/server/assets';
import { requireUser, route } from '../../../../../lib/server/route';

/**
 * The asset's blocks for the 3D view (lib/schematic.ts BlockData, without models), in its own coordinates. For its owner
 * and for builders on a server it is shared with.
 */
export const GET = route<{ id: string }>(async (_req, { id }) => {
  const user = await requireUser();
  return answer(() => assetBlocks(deps(user), user.id, id));
});
