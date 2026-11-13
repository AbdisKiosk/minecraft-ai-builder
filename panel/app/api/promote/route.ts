import { featureRefusal } from '../../../lib/plans';
import { PAYMENT_REQUIRED, serverLimits } from '../../../lib/server/billing';
import { hub } from '../../../lib/server/hub';
import { relayError } from '../../../lib/server/plugin-proxy';
import { body, HttpError, json, requireRole, requireUser, route } from '../../../lib/server/route';

const TIMEOUT_MS = 6 * 60_000;

const ints = (v: unknown, n: number): number[] | null =>
  Array.isArray(v) && v.length === n && v.every(Number.isInteger) ? (v as number[]) : null;

/**
 * Copies a region from one server into a draft on another (or the same) server: {from, to, world, box, toWorld,
 * origin, air, skipUnknown}. Nothing is placed until the target's draft is approved. Builder on both servers.
 */
export const POST = route(async req => {
  const user = await requireUser();
  const b = await body(req);
  const box = ints(b.box, 6);
  const origin = b.origin === undefined || b.origin === null ? null : ints(b.origin, 3);
  if (typeof b.from !== 'string' || typeof b.to !== 'string' || typeof b.world !== 'string' || !box
      || (b.origin != null && !origin) || (b.toWorld !== undefined && typeof b.toWorld !== 'string')) {
    throw new HttpError(400, 'give from, to, world, box [x1,y1,z1,x2,y2,z2] and optionally toWorld, origin [x,y,z], air, skipUnknown');
  }
  await requireRole(user, b.from, 'builder');
  await requireRole(user, b.to, 'builder');
  // Copying within one server is a draft like any other; between two, both owners' plans must allow it.
  if (b.from !== b.to) {
    for (const server of [b.from, b.to]) {
      const refused = featureRefusal(await serverLimits(server), 'promote');
      if (refused) throw new HttpError(PAYMENT_REQUIRED, refused);
    }
  }

  const min = [Math.min(box[0], box[3]), Math.min(box[1], box[4]), Math.min(box[2], box[5])];
  const size = [Math.abs(box[3] - box[0]) + 1, Math.abs(box[4] - box[1]) + 1, Math.abs(box[5] - box[2]) + 1];
  try {
    const exported = await hub().request(b.from, { t: 'draft', op: 'export', args: { world: b.world, box } }, TIMEOUT_MS);
    if (exported.status !== 200) return json(exported.body, exported.status);
    const imported = await hub().request(b.to, {
      t: 'draft', op: 'import', args: {
        world: b.toWorld ?? b.world,
        origin: origin ?? min,
        size,
        data: (exported.body as { data?: unknown } | null)?.data,
        air: b.air !== false,
        skipUnknown: b.skipUnknown === true,
        label: `promote from ${b.from} ${b.world} ${min.join(' ')}`,
      },
    }, TIMEOUT_MS);
    return json(imported.body, imported.status);
  } catch (e) {
    const [status, err] = relayError(e);
    return json(err, status);
  }
});
