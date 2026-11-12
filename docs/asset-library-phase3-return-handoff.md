# Return handoff: asset-library phase 3

## Update: all five phase-3 bullets are built (2026-11-12)

The sections after this one are the earlier handoff, kept for its plan, decisions and gotchas. This update supersedes
its "Current state", "Latest verified results" and "Decisions still needed".

**Branch** `asset-library-phase-3`, with `origin/main` (`1b7c5e3`) merged in. Each step was built on its own branch
from `0f7adef` and merged with `--no-ff`, so `git log --first-parent` shows them one by one:

| Step | Merged from | What shipped |
|---|---|---|
| Fix | `0f7adef` | `Lessons` repeated an id after a reload (made `LessonsTest` flaky) |
| 1 Version mismatch | `phase3-version-mismatch` | Place… warns when the asset is newer than the server (`lib/mcVersion.ts`) and lists skipped states; `paste name=` of a library asset leaves out unknown states as keep cells and returns `skipped` (≤10) + `skipped_total` |
| 2 Anchor and facing | `phase3-anchor` | Migration `007`; anchor/facing on Save as asset and Edit; `paste` `at` + `facing` (`common/Anchor.java`), checked against WorldEdit's own transform in `AnchorRotationTest` |
| 3 Sharing | `phase3-sharing` | Migration `008`; `/api/assets/[id]/shares`; Share dialog and "Shared with me" filter; relay `list`/`get` serve shared assets |
| 4 Account link + in-game save | `phase3-account-link` | Migration `009`; Minecraft account card on the Access tokens page; `/aibuilder link|unlink|asset save`; relay frames `link`, `unlink`, `player_asset`; `AssetLibrary.saveRegion` shared with `save_blueprint` |
| Fix | `b8bcd61` | `node dist/server.js` (the Docker `CMD`) failed at startup on `main` too: esbuild hoisted `next/server` out of the lazily imported asset store. The build now uses `--splitting --outdir=dist` and aliases `next/server` to `next/server.js` |

**Decisions taken** (the defaults this document recommended; confirm or change them in the PR):
1. Facing is stored six-way, but only the four horizontal facings turn; an `up`/`down` asset pastes only facing its
   own way (no vertical flip in `Blueprint`).
2. Legacy and uploaded assets keep NULL anchor and facing. `at` then means the lowest corner; `facing` is refused.
3. The account link is global (UUID → one account, at most 5 UUIDs per account), made with an 8-character code valid
   10 minutes, stored hashed, single use, 5 codes per user per 10 minutes, 20 link attempts per server per minute.
4. Sharing reaches members with the builder role or above, and only while the owner is a builder there too. Own
   assets win on `paste name=`; a name shared by several owners is a 409 naming them.
5. Version mismatch is report-only; nothing is remapped.
6. The `.schem` upload cap stays 8 MB.
7. `/aibuilder link|unlink|asset` need the new permission `aibuilder.asset` (default op).

**Verified:** panel `npm test` (105), `npm run typecheck`, `npm run build`, and the built `dist/server.js` starts and
answers `/api/health`; `cd plugin && ./gradlew build` with the tool-list budget (5,399 of 5,400 characters); SDK tests;
`git diff --check`. Migrations 001-006 then 007 and 008 were applied by agents to a throwaway MySQL 8.4 with existing
assets.

**Not verified yet:** nothing ran in a browser (Auth0) or against a live Minecraft server: real WorldEdit v2/v3
`.schem` files, quota and cleanup on R2, the new dialogs, a live `paste` with `at` + `facing`, a shared paste through
the relay with R2, `/aibuilder link` and `/aibuilder asset save` on a running server, and old-panel/new-plugin
combinations (reasoned through only: every new field and frame is additive, and a plugin without `links:true` in the
welcome refuses the in-game commands at once).

**Known gaps:** anchors are not in `get_context` (its 30-asset listing is at 5,201 of 5,500 characters); the Place…
dialog places by anchor position but cannot turn an asset (the draft import has no rotation). Closed since the first
update: the 3D anchor marker and click-to-pick (`anchor-ui`); an in-game save reads the world without the open draft,
the unknown-blocks refusal names the "leave out blocks" checkbox of both dialogs, and players have `/aibuilder asset
list` and tab completion (`ingame-polish`); the review findings fixed in `0bbe41a`.

**Old intro follows.** This is a return update for the session that finished phase 2, not an introduction to the
project. Phase 2 is merged. Phase 3 has started with the `.schem` upload; the other four phase-3 bullets are still a
plan.

## Work completed since the phase 2 handoff

Phase 2 was reviewed, committed, pushed, and merged through **PR #8**:

- PR: https://github.com/AbdisKiosk/minecraft-ai-builder/pull/8
- Main merge commit: `d42c9db` (`Merge pull request #8 from AbdisKiosk/asset-library-phase-2`)

The user then asked for the first phase-3 piece: uploading a WorldEdit `.schem` file on the Library page. That is
built and committed on the branch below.

## Current state

- Repository: `/Users/chrskole/IdeaProjects/minecraft-ai-builder`
- Branch: **`asset-library-phase-3`**, based on `d42c9db`.
- Branch tip before this handoff: `19ad7b1` (`Upload a WorldEdit .schem into the asset library`).
- The branch is **local only**. It has no upstream; `git ls-remote origin` has no `asset-library-phase-3`.
- `origin/main` is one commit ahead of the branch base: `1b7c5e3` (`Trigger the Coolify deploy with POST`). Reconcile
  that before opening a PR. It is a deployment-workflow change, not an asset change.
- Working tree: no tracked changes. The only untracked paths are the pre-existing user work listed under
  "Preserve existing user work" below.
- Phase-3 progress: **one of five bullets is built** (`.schem` upload). The remaining four are anchors/facing,
  version-mismatch reporting, in-game save, and sharing. In-game save also needs an account link that does not exist.

## What is implemented: `.schem` upload

### User-visible behaviour

- The **Assets** page has an **Upload .schem** button and dialog.
- The form takes `file`, `name`, `description`, and comma-separated `tags`. The name is prefilled from the file name
  (`My House (2).schem` becomes `my-house-2`) unless the user typed one.
- The route is `POST /api/assets/upload`, requires a signed-in user, and needs **no server role** because the file
  comes from the browser rather than from a server.
- On success the asset appears in the library like any other asset: same quota, same name rules, same ownership, same
  3D view, same "Place into a draft" path, and the same `paste name=` path for Claude.

### What the parser accepts

- Sponge schematic **version 2** (fields at the root) and **version 3** (wrapped in `Schematic`), which WorldEdit 7
  writes, as gzipped NBT.
- Only block states are kept. Block entities (sign text, chest contents), entities, and biomes are dropped. The
  schematic's `Offset` is ignored: the asset starts at `0 0 0`, in the same orientation as a region saved from a
  server.
- `minecraft:` is removed from state names; other namespaces stay. `cave_air` and `void_air` become `air`.
- `DataVersion` is mapped to a release name through a fixed table (1.13 through 1.21.10, plus 26.3). An unknown or
  missing data version is stored as unknown, never guessed; the 3D view is not shown for it.

### What the parser refuses, with its own message

- MCEdit `.schematic` files.
- Sponge version 1.
- Litematica files.
- Anything that is not gzipped NBT, or is damaged.
- Palettes and block data that do not match the declared size; a palette index that the palette lacks; data after the
  end; negative lengths; misplaced end tags; files that unpack to more than 64 MB.

### Limits

The file is treated as hostile input:

| Limit | Value |
|---|---|
| Uploaded file | 8 MB, checked by `Content-Length`, while streaming, and again by `file.size` |
| Unpacked NBT | 64 MB |
| Asset volume | 2,000,000 blocks (the existing asset cap) |
| Packed region data | 5 MB (the existing asset cap) |
| Palette entries | 65,536 |
| Characters in all used states | 4 MB |
| One block state | 256 characters |
| NBT nesting | 64 |

Every length in the file is checked against the bytes left before anything is allocated from it. The Next.js request
body limit is 10 MB, above the 8 MB upload bound, so the asset bound is the binding one.

### Design notes and deviations

- **No plugin, relay, protocol, or Java change.** The upload is entirely panel-side and saves through the existing
  `saveAssetData()` path. Nothing in the plugin needs to know whether an asset came from a server or an upload.
- **Uploads are not tied to a server.** There is no server role check; ownership comes from the signed-in user.
- **Only blocks, by design.** A schematic can carry more, but an asset is a reusable block piece. This matches the
  saved-blueprint semantics.
- **No anchor or facing metadata yet.** Uploaded assets are legacy-shaped for the phase-3 anchor work: there is no
  attachment point or front direction to store.
- **The upload path does not go through the relay's 5 MB save limit separately.** It parses to a region, then applies
  the same `MAX_REGION_BYTES` check, so the resulting asset obeys the same cap as every other asset.

## Main files

New files:

```text
panel/app/api/assets/upload/route.ts
panel/components/UploadAssetDialog.tsx
panel/lib/schemUpload.ts
panel/lib/server/schem.ts
panel/test/schem.test.ts
```

Updated by `19ad7b1`:

```text
docs/asset-library-plan.md
docs/panel.md
panel/components/AssetsPage.tsx
```

This handoff adds:

```text
docs/asset-library-phase3-return-handoff.md
```

## Latest verified results

These checks were run against this branch tip before this handoff:

| Check | Result |
|---|---|
| `cd panel && npm test` | 78 tests passed, including 17 new schematic tests |
| `cd panel && npm run typecheck` | Passed |
| `cd panel && npm run build` | Passed; `dist/server.js` produced (Auth0 env warnings only) |
| `cd plugin && ./gradlew build` | Passed (all tasks up-to-date; no Java files changed) |
| `git diff --check` | Passed |

**Not yet verified for the `.schem` upload:**

- A real file exported by WorldEdit 7 on a real server, both version 2 and version 3. The tests build synthetic NBT.
- A live upload against MySQL and R2, including quota rejection and cleanup after a partial failure.
- The browser flow in a deployed panel, including the 10 MB Next.js body limit and a file near 8 MB.
- The 3D view of an uploaded asset, including a schematic with an unknown `DataVersion`.
- Placement and Claude paste of an uploaded asset on a live server.

## Plan to complete phase 3

Phase 3 is "making assets fit". The plan's five bullets are the scope. Do them in an order that keeps each change
reviewable and does not make the anchor work depend on account linking.

### Step 0: publish and validate what exists

- Fetch `origin`, reconcile `main` (currently `1b7c5e3`) into `asset-library-phase-3`, rerun the panel and plugin
  checks, then push the branch and open a PR for the `.schem` upload.
- Run the live checks listed above. Capture at least one real WorldEdit v2 and one v3 file, an 8 MB boundary file, a
  file with an unknown `DataVersion`, and a Litematica/MCEdit file for the refusal messages.
- Decide whether 8 MB is the right upload cap before the route's limit is relied on; changing it later touches the
  dialog, the route, `schem.ts`, and the docs together.

### Step 1: surface version mismatches (small, mostly verification)

The planned behaviour is "an asset saved on 1.21 and pasted on 1.8 goes through the existing unknown-block handling.
The result reports what was skipped." The unknown-block handling already exists in the draft import path:

- `Drafts` returns `skipped` for unknown blocks; `placeAsset()` passes the plugin reply through; the place dialog
  already has a `skipped` field in its response type but does not show it to the user.
- Do the same for the Claude `paste` path: return the skipped states in the tool result, not only the draft reply.
- Add a compatibility preflight to the panel (asset `mc` versus the server's reported version) so a user can be
  warned before creating a draft. Keep it advisory: the existing `skipUnknown` checkbox remains the decision.
- Measure the real gap: unknown names are caught, but a state that exists in both versions with a changed meaning is
  not. Do not pretend the check is a full version migration. If renames matter, add a small explicit mapping table
  and tests rather than guessing.
- Show the skipped list in `PlaceAssetDialog` and in the Claude result. Add tests for an old server and an unknown
  state. Update `docs/panel.md` and `docs/asset-library-plan.md`.

### Step 2: anchor and facing (the core of "making assets fit")

Goal: Claude can put a window on any wall, or a pillar on any floor, without guessing the minimum corner.

**Data.** Add a migration `007_asset_anchor.sql`:

- `anchor_x`, `anchor_y`, `anchor_z` as nullable ints: the attachment cell inside the asset, zero-based from the
  asset's minimum corner. `NULL` means a legacy asset.
- `facing` as nullable `VARCHAR(8)`: `north`, `south`, `east`, `west`, `up`, or `down`. This is the direction the
  asset faces away from its attachment surface.
- Optionally `anchor_rotation` if a six-way facing is not enough; do not add it until a real piece needs it.

Nullable columns are deliberate: every existing asset and every uploaded `.schem` has no anchor, and a migration that
invents one would place old windows in the wrong spot. The plugin must fall back to today's minimum-corner behaviour
when either field is `NULL`.

**Capture.**

- In the panel's **Save as asset** flow, default the anchor to the selection's minimum corner and the facing to the
  player's yaw, then let the user change both. A click-to-pick anchor in the 3D view is the usable version; a text
  form is the minimum.
- In the upload dialog, leave both `NULL` (a `.schem` has no standard anchor) and allow the user to set them later in
  **Edit asset**.
- When in-game save exists (step 4), infer the anchor from the block the player is looking at and the facing from the
  player's yaw.

**Paste.**

- Extend `paste` with `at` (the world position of the anchor) and `facing` (the desired outward direction). Keep the
  existing `origin` + `rotation` form for exact placement and backward compatibility; exactly one of `origin` or `at`
  may be given.
- In the plugin, rotate the anchor cell with the blueprint, then compute `origin = at - rotatedAnchor`. `Op.Paste`
  already takes a minimum corner after rotation, so the rotation math can stay where it is; the anchor only changes
  how the origin is derived.
- Horizontal facings are a rotation (0/90/180/270). `up` and `down` need a vertical flip, which `Blueprint` does not
  currently expose to `Paste`; either add it or restrict v1 to the four horizontal facings and document the gap.
- The panel's **Place** dialog can stay minimum-corner for now. The Claude tool is where anchor placement matters
  most. Add a UI mode later if users ask for it.
- Draw the anchor and facing arrow in the 3D view, and include them in the asset card.

**Tests and budget.**

- Java: anchor rotation for each quarter turn, legacy `NULL` fallback, mismatch between an anchor and a different
  blueprint size, and `up`/`down` if implemented.
- Panel: validation of anchor bounds and facing values; migration against a database with existing assets.
- The tool-list budget is already tight. Adding `at` and `facing` to `paste` means shortening existing descriptions,
  not raising the threshold. Run `McpEndpointTest#toolListStaysWithinBudget` alone while iterating.
- Update the build skill so Claude prefers `at` + `facing` and still knows the `origin` fallback.

### Step 3: share an asset with a server

Goal: every builder on a server can use an asset its owner shared, without copying it.

- **Schema.** Migration `008_asset_shares.sql`: `asset_id`, `server_id`, `shared_by`, `created_at`, unique
  `(asset_id, server_id)`, foreign keys with cascading deletes.
- **Ownership stays.** Only the owner can share, unshare, rename, describe, or delete. A shared asset is still counted
  against the owner's quota, not the recipient's.
- **API.** `GET/POST/DELETE /api/assets/[id]/shares`; list responses gain an `owner`/`shared` marker. Keep the
  owner-only routes owner-only.
- **Relay authorization.** `asset` ops `list` and `get` must accept "owner" **or** "asset shared to this server and
  caller is a builder here". `save` and `check` stay owner-only. The grant and builder recheck remain the security
  boundary; do not add a plugin-supplied server or user trust.
- **Name collisions.** A user can already have an asset with the same name as a shared one. Decide and document the
  rule before writing the resolver: own assets win, and an ambiguous shared name is an error that lists the owners,
  rather than silently choosing one. This matters because `paste name=` has no owner qualifier today.
- **Cache.** The plugin's cache is per account today. A shared asset fetched by a non-owner must still be authorized
  on every paste, and the cache key must include the asset id and checksum, not just the name, so a rename or a
  replaced asset cannot reuse stale bytes.
- **UI.** Share dialog with the server list and builder-role explanation; a "shared" badge and filter on the library.
- **Tests.** Owner shares; another builder lists, gets, places, and pastes it; a non-member is denied; unshare and
  delete revoke access; quota is unchanged for the recipient; a shared name collision resolves as documented.

### Step 4: save in game (needs an account link)

Goal: `/aibuilder asset save <name>` from a WorldEdit selection.

This is the only phase-3 bullet with a missing prerequisite: there is no link between a Minecraft player and a panel
account. Do not fake it with a plugin-supplied user id; that would repeat the trust mistake phase 2 avoided with
grants.

**Link design (recommended).**

- Migration `009_minecraft_links.sql`: `uuid` (player UUID), `user_id`, `linked_at`, unique `uuid`, index `user_id`.
  The link is global to the account, not per server; server membership is still checked on every save.
- Panel page **Link Minecraft account** issues a one-time code bound to the signed-in user, valid for a few minutes,
  stored hashed, single-use, and rate-limited.
- In game, `/aibuilder link <code>` sends a `link` frame to the panel. The panel verifies the server's relay
  connection and the player's membership, consumes the code, and stores the UUID mapping. The plugin never chooses
  the user id.
- `/aibuilder unlink` removes the mapping for the calling player, and the panel page can revoke it too.

**Save command.**

- `/aibuilder asset save <name> [description]` requires builder permission and a WorldEdit selection.
- If the player has no link, fail with a message that names the panel page and the `/aibuilder link` command; do not
  silently save to the server-local blueprints.
- Read the selection through `BuildService.reading`, apply the existing volume and 5 MB limits, then send an `asset`
  `save` request as the linked user. Reuse `AssetLibrary.save` and the same quota/name preflight as `save_blueprint`.
- Consider `/aibuilder asset list` and `/aibuilder asset delete` later; they are not required by the plan.
- Tests: code expiry, reuse, and rate limit; UUID mapping; no-link error; selection limits; quota and duplicate name;
  unlink.

**If account linking is too large for this phase,** split it into its own effort and keep phase 3's in-game-save
bullet explicitly open. Do not ship a command that trusts whatever UUID the plugin sends.

### Cross-cutting work

- **Migrations:** `007`, `008`, and `009` must apply to a database that already has `005` and `006`. MySQL has no
  `ADD COLUMN IF NOT EXISTS` on older versions, so keep the runner's existing plain-SQL convention and test on a
  populated database.
- **Compatibility:** old panel + new plugin and new panel + old plugin must keep ordinary tools working. Anchor and
  share metadata are additive; old clients ignore them. Do not require the new fields for unrelated calls.
- **Token cost:** anchors add a few tokens per asset in `get_context`. Keep the 30-asset cap and the 80-character
  description cut; measure the worst case before adding more listing detail.
- **Test matrix:** panel tests, typecheck, build; Java build and the tool-list budget; `git diff --check`; SDK tests
  if the Python SDK gains anchor or share parameters.
- **Docs:** update `docs/panel.md`, `docs/asset-library-plan.md`, this handoff, and the build skill at each step.
  `docs/panel.md` is the user-facing description; keep the limits table in step with the code.

## Suggested commit breakdown

Keep each change reviewable. Each commit should build and test on its own where feasible; the branch tip must pass.

1. `docs`: this handoff (the commit that publishes the current state).
2. `panel`: version-mismatch reporting and skipped states in the place dialog.
3. `panel`/`plugin`: anchor and facing schema, capture, paste math, tests.
4. `panel`/`plugin`: sharing schema, routes, relay authorization, UI, tests.
5. `panel`/`plugin`: account link and in-game save.
6. `docs`: plan status, panel docs, final handoff.

If step 5 is split out, publish steps 1-4 first and leave a short open-items note rather than blocking the branch.

## Definition of done for phase 3

- [ ] The `.schem` upload is validated live with real WorldEdit v2 and v3 files, quota, and the 3D view.
- [ ] A version mismatch reports skipped blocks in both the panel and the Claude paste result.
- [ ] An asset stores an anchor and facing; `paste` with `at` + `facing` places it correctly; legacy assets still
      place at the minimum corner.
- [ ] A builder can save an asset from an in-game WorldEdit selection through a linked account.
- [ ] An owner can share an asset with a server; its builders can list, get, place, and paste it; revocation works.
- [ ] Panel tests, typecheck, build, Java build, and the tool-list budget pass.
- [ ] Migrations `007`-`009` apply cleanly to a populated database.
- [ ] Old panel + new plugin and new panel + old plugin keep ordinary tools working.
- [ ] `docs/asset-library-plan.md`, `docs/panel.md`, and this handoff describe what shipped.
- [ ] A PR is opened with the security notes for sharing and account linking.

## Decisions still needed

1. **Anchor representation:** six-way facing (north/south/east/west/up/down) or full rotation? Restrict v1 to
   horizontal facings if `up`/`down` would force a `Blueprint` flip change?
2. **Anchor defaults:** minimum corner plus north for legacy and uploaded assets, or leave them unusable for anchored
   placement until the user sets one?
3. **Account link:** global UUID link (recommended) or per-server link? Which panel page owns the code, and how long
   does it live?
4. **Sharing scope:** all builders on a server, or a role threshold? What is the name-collision rule for
   `paste name=`?
5. **Version mismatch:** report-only, or also remap known state renames? If remapping, which versions are in scope?
6. **Upload cap:** keep 8 MB, or raise it? Raising it means changing the dialog, route, parser, and docs together.
7. **Order:** anchors before sharing, or sharing first because it is more user-visible? The recommended order above
   puts the core placement work first.

Answer these in the PR descriptions if the user does not decide them explicitly. Do not silently change phase-3
scope to resolve them.

## Preserve existing user work

Do not alter or accidentally include these unrelated, pre-existing untracked paths:

```text
builds/clubhouse2/
docs/3d-viewer-brief.md
docs/setup.md
panel/run/
```

They are not part of phase 3 and were untracked before this handoff.

## Gotchas specific to this work

- **The NBT reader is the security boundary.** Every length is adversarial. Keep the `count()`/`need()` checks ahead
  of allocation, keep the 64 MB gunzip cap, and add a test for every new field before reading it.
- **The `.schem` format has two layouts.** Version 2 keeps `Palette` and `BlockData` at the root; version 3 keeps
  them under `Blocks`. Do not unify the paths without testing both.
- **`Offset` is ignored.** That is deliberate: the asset starts at `0 0 0` like a saved region. Document it; do not
  "fix" it into an offset that the import path does not understand.
- **Data versions are a fixed table.** A new Minecraft release needs a table entry; otherwise the asset is stored
  with an unknown version and the 3D view is hidden. That is safer than guessing.
- **Tool-list budget:** `McpEndpointTest#toolListStaysWithinBudget` has almost no headroom. Shorten descriptions
  instead of raising the limit. Run that test alone while iterating.
- **Node 24 strip-only TypeScript:** `npm test` runs `.ts` directly. Avoid parameter properties, enums, namespaces,
  and decorators.
- **Module cycles:** `hub.ts` ↔ `assetStore.ts`/`assets.ts`. The asset handler's dynamic import is load-bearing.
  Verify panel startup after touching these files, not just tests.
- **R2 may be absent in dev.** `welcome.assets` is false without R2; the plugin must show a clear message rather than
  retrying. Test that path.
- **Do not log asset data or signed URLs.** URLs are bearer credentials for 60 seconds. Log names, sizes, and status
  only.
- **Shared assets change the cache's meaning.** A cache entry authorized for the owner must not be reusable by a
  recipient without a fresh successful `get`. Reauthorize every paste and key the cache by asset id and checksum.
- **Account link codes are credentials.** Hash at rest, single-use, short TTL, rate-limited, and revocable.

## First actions for you

```bash
cd /Users/chrskole/IdeaProjects/minecraft-ai-builder
git fetch origin
git status --short --branch
git log --oneline -5
cd panel && npm test && npm run typecheck && npm run build
cd ../plugin && ./gradlew build
```

Then read `panel/lib/server/schem.ts`, `panel/app/api/assets/upload/route.ts`, and
`panel/components/UploadAssetDialog.tsx` first: they hold the new parser and the upload boundary. Read
`docs/asset-library-plan.md` for the phase-3 scope, then `panel/lib/server/assets.ts`, `panel/lib/server/assetRelay.ts`,
and `plugin/core/src/main/java/me/abdiskiosk/minecraftaibuilder/plugin/tools/Tools.java` before starting anchors.