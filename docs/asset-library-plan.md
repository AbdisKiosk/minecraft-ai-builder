# Personal asset library: plan

A builder saves their own windows, pillars, trees and other pieces once, and Claude reuses them instead of inventing
its own. The library lives on the panel and belongs to the account, so the assets follow the builder to every server.
Claude uses an asset through the existing `paste` op by name; no new tool is added.

Status: phases 1 and 2 are built (see "Assets" in `panel.md`); the deviations from this plan are listed under phase 2.
Phase 3 is a plan, except the `.schem` upload, which is built (see "Upload a .schem" in `panel.md`).

## What exists today
- **Saved blueprints:** `save_blueprint` writes a region to `plugins/AIBuilder/blueprints/<name>.json` on that one
  server, and `paste` with `name=` places it (`Tools.java`: `saveBlueprint`, `readSavedBlueprint`).
- **What Claude sees:** `get_context` lists up to 50 bare names, with no size, description or owner.
- **No user identity in the plugin:** the panel sends `{t:'mcp', body}` down the relay (`plugin-proxy.ts`) without
  saying which account is calling, so the plugin cannot tell whose assets to use.
- **Reusable pieces:** promote (`app/api/promote/route.ts`, `Drafts#export`, `Drafts#importRegion`) already exports a
  region from one server and imports it into a draft on another. History (`archive.ts`) already stores block files in
  R2 and shows them in the 3D viewer.

## Phase 1: storage and saving from the panel
No plugin change is needed for this phase.
- **Migration `005_assets.sql`:** one row per asset with owner, name, description, tags, size, block count, the
  Minecraft version it was saved on and bytes.
- **Blocks in R2:** `users/<user>/assets/<id>/`, in the same format the `draft export` frame already returns, plus the
  blocks file the 3D viewer reads.
- **`lib/server/assets.ts` and `/api/assets` routes:** list, get, rename, describe, delete, with a per-user quota on
  count and bytes.
- **Save from the map:** mark a box with the existing two-click selection and choose "Save as asset". The panel calls
  the existing `draft export` frame and stores the result.
- **Library page:** a new hash route with a card per asset, the 3D view, and "Place into a draft on server X" through
  the existing `draft import` frame.

## Phase 2: Claude uses the library
- **Identify the caller:** the panel adds the user id to `mcp` and `api` frames, and `CallContext` carries it.
- **Resolve names:** `paste` with `name=` looks in the server's saved blueprints first, then in the caller's library.
- **Fetch through the relay:** a new plugin-to-panel request (`asset`), answered with a download URL as the history
  upload does. The plugin caches the file on disk.
- **Listing in `get_context`:** name, size and a short description per asset, capped at about 30 most recently used,
  so the result size stays fixed (see "Token cost" in `CLAUDE.md`).
- **Save from Claude:** `save_blueprint` gains `description` and stores to the caller's library when a user is known.
- **Skill update:** the `build` skill tells Claude to check the library before inventing a window, pillar or tree.

Built with these deviations:
- **Grant, not a bare user id:** the panel sends a random per-request grant (`assetKey`) with the user id and takes
  ownership from the grant. The plugin uses the id only to name its cache directory.
- **Inline save:** `save_blueprint` sends the region up the relay as base64 (at most 5 MB compressed) instead of a
  presigned upload. Reads use a signed download URL valid for 60 seconds, as planned.
- **Ordering:** by `last_used_at` (migration `006_asset_usage.sql`) rather than `updated_at`: it is set when Claude
  fetches the asset for a paste or the panel places it, so renaming or describing an asset does not move it up.
- **Listing:** at most 30 assets, descriptions cut to 80 characters.

## Phase 3: making assets fit
- **Anchor and facing:** store which block is the attachment point and which way the asset faces, so Claude can rotate
  a window onto any wall instead of guessing from the min corner.
- **Version mismatch:** an asset saved on 1.21 and pasted on 1.8 goes through the existing unknown-block handling. The
  result reports what was skipped.
- **Save in game:** `/aibuilder asset save <name>` from a WorldEdit selection. This needs a link between a Minecraft
  player and a panel account, which does not exist yet.
- **`.schem` upload** on the Library page. Built: Sponge versions 2 and 3, parsed on the panel
  (`lib/server/schem.ts`) and saved through the same path as every other asset; only the blocks are kept.
- **Sharing an asset with a server** so all its members can use it.

## Tests and limits
- **Panel:** `npm test` cases for quota, ownership (one user cannot read another's asset) and the routes.
- **Plugin:** `RelayClientTest` against `FakePanel` for the fetch; `McpEndpointTest#toolListStaysWithinBudget` must
  still pass.
- **Memory:** pasting already goes through `submit`. Not yet checked: that the export read goes through
  `BuildService#reading`. Confirm before phase 1 ships.
- **Protocol:** the new frame means `RelayProtocol` and `hub.ts` change together. An old plugin ignores the user id, so
  the first step of phase 2 should not need a protocol bump.

## Decision: ownership
Assets belong to the account, not the server. Per-server assets are what `save_blueprint` already gives, and they stay
behind when the builder moves on. The cost is the caller-identity work in phase 2.
