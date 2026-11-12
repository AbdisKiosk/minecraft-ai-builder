# Return handoff: asset-library phase 2

This is a return update for the original session whose work I continued, not an introduction to the project.

## Work completed since your session

I completed **learning from corrections**, including phase 4. That was committed, reconciled with main's WorldGuard changes, and merged through **PR #7**:

- PR: https://github.com/AbdisKiosk/minecraft-ai-builder/pull/7
- Main merge commit: `c9f42bffdd7e6eebf4b79e5d4f4eddbd49d1f5bf`

The user then asked me to complete **personal asset-library phase 2**. Most of that implementation is now in the working tree. The user paused implementation to return control to you.

## Current state

- Repository: `/Users/chrskole/IdeaProjects/minecraft-ai-builder`
- Branch: **`asset-library-phase-2`**, based on `c9f42bf`.
- **All phase-2 changes are uncommitted and local.** No phase-2 PR has been opened.
- Estimated progress: **85%**. Core functionality is implemented; final review, documentation, and live integration verification remain.
- The branch currently tracks `origin/main`. When authorized to publish, explicitly use `git push -u origin asset-library-phase-2`.

## What I implemented

### Caller identity and authorization

- The panel proxy forwards the authenticated access-token owner's account identity on MCP/REST requests.
- `RelayHub.requestAsUser()` issues an opaque, per-request asset-access grant, scoped to one relay connection.
- Grants expire and are revoked on request completion, timeout, or disconnect.
- The panel determines asset ownership from the grant, never from a plugin-supplied user ID.
- Builder membership is checked again for each nested asset operation.
- Java's `CallContext` carries the identity and restores it after the request, including on exceptions.

### Asset operations and tool integration

- New `asset` relay operations: `list`, `get`, `check`, and `save`.
- `get_context` lists up to 30 personal assets with names, dimensions, and descriptions truncated to 80 characters.
- Library failures appear as `assets_error`, without hiding the rest of the context or pretending the library is empty.
- Named paste resolves **server-local blueprints first**, then the authenticated caller's personal library.
- `save_blueprint` accepts `description` and saves to the account library when authenticated.
- Requests without account identity retain server-local saving behaviour.
- An authenticated library failure does not silently fall back to saving locally.
- Save preflight checks name availability and quota before reading world chunks. Export reads/encoding use `BuildService.reading`.

### Downloads, caching, and storage

- Asset downloads use short-lived signed URLs and SHA-256 verification.
- Disk caches are separated by account. Every paste still resolves/authorizes the asset before using cached bytes.
- HTTP downloads are bounded during receipt. The disk cache is capped at 128 MB and evicts older files.
- Saved air places air, matching existing local-blueprint semantics.
- Shared `saveAssetData()` validation/storage logic enforces ownership-scoped names, dimensions, data limits, and existing account quotas.
- Partial file-write failures clean up stored files.
- New migration `006_asset_usage.sql` adds recent-use ordering.
- R2 reads can be bounded; `StateArrays` decoding now rejects malformed palettes, overflowing runs, and trailing data.
- Protocol documentation and the build skill were updated. The protocol change is additive; the version number was not bumped.

**Implementation choice:** downloads use signed URLs as planned. Saves currently send bounded base64 region data through the relay instead of using a separate presigned-upload handshake.

Limits are **5 MB compressed region data / 2,000,000 cells**, in addition to existing account quotas and server read/edit limits.

## Main files

New files:

```text
panel/lib/server/assetRelay.ts
panel/migrations/006_asset_usage.sql
panel/test/assetRelay.test.ts
plugin/core/src/main/java/me/abdiskiosk/minecraftaibuilder/plugin/AssetLibrary.java
plugin/core/src/test/java/me/abdiskiosk/minecraftaibuilder/plugin/AssetLibraryTest.java
```

The working diff also updates:

```text
.claude/skills/build/SKILL.md
panel/lib/server/assetStore.ts
panel/lib/server/assets.ts
panel/lib/server/hub.ts
panel/lib/server/plugin-proxy.ts
panel/lib/server/r2.ts
panel/test/assets.test.ts
plugin/common/src/main/java/me/abdiskiosk/minecraftaibuilder/common/RelayProtocol.java
plugin/common/src/main/java/me/abdiskiosk/minecraftaibuilder/common/StateArrays.java
plugin/core/src/main/java/me/abdiskiosk/minecraftaibuilder/plugin/BuildService.java
plugin/core/src/main/java/me/abdiskiosk/minecraftaibuilder/plugin/CallContext.java
plugin/core/src/main/java/me/abdiskiosk/minecraftaibuilder/plugin/relay/RelayClient.java
plugin/core/src/main/java/me/abdiskiosk/minecraftaibuilder/plugin/tools/Tools.java
plugin/core/src/test/java/me/abdiskiosk/minecraftaibuilder/plugin/relay/FakePanel.java
plugin/core/src/test/java/me/abdiskiosk/minecraftaibuilder/plugin/relay/RelayClientTest.java
plugin/core/src/test/java/me/abdiskiosk/minecraftaibuilder/plugin/tools/ToolsTest.java
```

## Latest verified results

These checks were run against the current phase-2 working tree before this handoff:

| Check | Result |
|---|---|
| `cd plugin && ./gradlew build` | Passed, including Java tests |
| `cd panel && npm test` | 55 tests passed |
| `cd panel && npm run typecheck` | Passed |
| `cd panel && npm run build` | Passed; `dist/server.js` produced (Auth0 env warnings only) |
| MCP tool-list budget test | Passed without raising its limit |
| `git diff --check` | Passed |

New tests cover ownership, grant expiry/revocation/socket isolation, quotas, bounded listings, partial-write cleanup, cache isolation and corruption, local-name precedence, and MCP/REST identity propagation.

The first test runs caught a tool-description budget overrun and a Node strip-only TypeScript incompatibility. Both were fixed. There is very little spare room in the existing tool-list budget; preserve the threshold.

**Not yet verified for phase 2:** migration against real MySQL, real R2 integration, live Minecraft/browser workflow, and live token-cost measurement. The panel production build now passes; do not confuse production builds from the earlier correction-learning task with phase-2 validation.

## Plan to finish

### 1. Review and harden the existing implementation

- Review grants during concurrent requests, timeout, disconnect, and reconnection.
- Check identity and ownership enforcement across both MCP and REST.
- Review bounded R2 reads, signed downloads, cache eviction, and download failures.
- Confirm older-panel/plugin compatibility.
- Review the shared save path's new limits: they also affect panel-created assets. Confirm and document that behaviour.

### 2. Complete edge-case coverage

- Add direct malformed-input tests for `StateArrays.decode`.
- Ideally test missing/expired grants through the actual hub request path, in addition to the pure grant/service tests.
- Check quota races and storage failures.
- Check realistic maximum-size `get_context` output.

### 3. Finish documentation and optional SDK integration

- Update `docs/asset-library-plan.md`: phase 2 is still described as planned.
- Update `docs/panel.md`: personal saves, local-name precedence, limits, cache, migration, and compatibility.
- Optionally add `description` to the Python SDK's `save_blueprint` helper. The SDK has not been changed yet.

### 4. Run final automated validation

From `panel/`:

```bash
npm test
npm run typecheck
npm run build
```

From `plugin/`:

```bash
./gradlew build
```

From the repository root:

```bash
python3 -m unittest discover -s sdk/python/tests
git diff --check
```

### 5. Verify real integration

With MySQL, R2, and a test Minecraft server configured:

1. Apply migration `006` through the normal startup migration path.
2. Give two accounts different assets with the same name; verify each sees and pastes only its own.
3. Save an asset on server A and reuse it on server B.
4. Confirm local blueprint names take precedence.
5. Delete an asset and verify cached bytes do not allow it to be pasted again.
6. Confirm review mode still routes pastes into drafts.
7. Check missing storage, expired grants, and download failures give clear errors without local-save fallback.
8. Measure live token cost when endpoint credentials are available.

### 6. Publish when authorized

Fetch/reconcile any new main changes, rerun checks, stage phase-2 files explicitly, then commit/push/open a PR. No phase-2 publication has been performed yet.

## Preserve existing user work

Do not alter or accidentally include these unrelated, pre-existing untracked paths:

```text
builds/clubhouse2/
docs/3d-viewer-brief.md
docs/setup.md
panel/run/
```

## Scope boundary

Phase 3 remains out of scope: anchors/facing metadata, in-game account linking and saving, `.schem` uploads, and sharing.

**Continue the working diff rather than reimplementing phase 2.**

---

## Phase 2 requirement checklist

From `docs/asset-library-plan.md`, phase 2:

| Requirement | Status | Where |
|---|---|---|
| Identify the caller: panel adds user id to `mcp`/`api`, `CallContext` carries it | **Done, with a deliberate deviation:** the panel adds an opaque grant plus the user id; ownership is taken from the grant, not the id | `plugin-proxy.ts`, `hub.ts`, `CallContext.java` |
| Resolve names: server-local blueprints first, then the caller's library | **Done** | `Tools.blueprint`, `Tools.saveBlueprint` |
| Fetch through the relay; plugin caches on disk | **Done**, with a design deviation: signed download URL for reads, inline bounded base64 for saves | `assetRelay.ts`, `AssetLibrary.java` |
| `get_context` listing: name, size, short description, ~30 most recently used | **Done** | `Tools.context`, `assetRelay.ts`, migration `006` |
| `save_blueprint` gains `description`; saves to the caller's library when a user is known | **Done** | `Tools.saveBlueprint` |
| Build skill tells Claude to check the library before inventing pieces | **Done** | `.claude/skills/build/SKILL.md` |
| Protocol: new frame; `RelayProtocol` and `hub.ts` change together; no bump needed | **Done**; additive frame, version stays 2 | `RelayProtocol.java`, `hub.ts` |
| Plugin test: `RelayClientTest` against `FakePanel` for the fetch | **Done** | `RelayClientTest.authenticatedMcpAndRestCallsFetchAssetsAndRestoreWorkerIdentity` |
| Tool-list budget still passes | **Done**, no threshold change | `McpEndpointTest` |

### Deviations worth a reviewer's attention

1. **Ownership is grant-based, not user-id-based.** The plan said "the panel adds the user id"; putting a user id in a plugin frame is not authorization, because a plugin is shared by every account that builds on that server and runs untrusted-ish code. The panel issues a random per-request key and looks up the owner from it. The `user` field is still sent so the plugin can scope cache directories, but it is never trusted for access. This is the safest reading of the plan and should be kept.
2. **Saves do not use a presigned upload.** Reads use signed URLs as planned. Saves go up the relay as base64, capped at 5 MB compressed. This avoids a second round trip and a second integrity check; the cost is relay bandwidth. If asset saves become large or frequent, switch to an upload handshake modelled on history's `upload`/`uploaded` frames.
3. **The relay asset handler uses `await import('./assetStore')`.** This is deliberate: `assetStore.ts` imports `hub.ts`, so a static import creates a module cycle that broke process startup during work. Do not "tidy" it into a top-level import without rechecking startup.
4. **Asset ordering uses `last_used_at`, not `updated_at`.** Renaming an asset should not pretend it was just used. Migration `006` adds the column and index; `get` and successful `place` touches update it.

## Wire contract

Panel → plugin, per authenticated tool request (additive fields; older plugins ignore them):

```json
{"t":"mcp","id":7,"user":12,"assetKey":"<64 hex chars>","body":{...}}
{"t":"api","id":7,"user":12,"assetKey":"<64 hex chars>","tool":"build","args":{...}}
```

Plugin → panel, only after `welcome.assets === true`:

```json
{"t":"asset","id":5,"assetKey":"<same grant>","op":"list","args":{}}
{"t":"asset","id":6,"assetKey":"<same grant>","op":"get","args":{"name":"oak-window"}}
{"t":"asset","id":7,"assetKey":"<same grant>","op":"check","args":{"name":"oak-window"}}
{"t":"asset","id":8,"assetKey":"<same grant>","op":"save","args":{"name":"oak-window","description":"...","size":[3,4,2],"data":"<base64 StateArrays>"}}
```

Panel answers with the existing `answer` frame:

```json
{"t":"answer","id":6,"status":200,"body":{"id":"abcdefgh2345","size":[3,4,2],"bytes":812,"sha256":"<64 hex>","url":"https://..."}}
```

Body shapes:

| Op | Request | 200 body on success |
|---|---|---|
| `list` | `{}` | `{"assets":[{"name","size","description"}]}`, max 30 |
| `get` | `{"name"}` | `{"id","size","bytes","sha256","url"}`; URL expires in 60 s |
| `check` | `{"name"}` | `{}` after name/quota preflight |
| `save` | `{"name","description","size","data"}` | `{"saved","size","blocks","library":true}` |

Errors use `{"error": "..."}` with status 400/403/409/410/503. The plugin turns any non-2xx into an `EditException` carrying the panel's message.

`welcome` now carries `"assets": <r2Configured()>`. Without it, the plugin refuses personal-asset operations with a clear message rather than trying frames an older panel will not answer.

## Request lifecycle

1. `plugin-proxy.ts` authenticates the bearer token, resolves the server and the user's role, and builds the frame.
2. `hub.requestAsUser()` issues a grant bound to the live connection.
3. The frame carries `user` + `assetKey`; the grant expires with the tool timeout (capped at 6 minutes).
4. `RelayClient` runs MCP/API work inside `CallContext.withIdentity(...)` and restores the previous identity in `finally`.
5. A nested `asset` frame comes back on the same socket. `RelayHub.asset()` resolves the owner from the grant, rechecks builder membership on that server, and answers.
6. `get` reads at most 5 MB from R2, signs a 60-second URL, touches usage, and returns metadata.
7. `AssetLibrary` downloads with a hard byte limit, verifies SHA-256, caches under `plugins/AIBuilder/asset-cache/<userId>/`, and decodes to states.
8. On reply, timeout, or disconnect the grant is revoked. Cached bytes are useless without a fresh successful `get`.

## Code map

| Concern | File |
|---|---|
| Grant issuance/lifetime, asset op rules | `panel/lib/server/assetRelay.ts` |
| Grant lookup and ownership from a socket | `panel/lib/server/hub.ts` |
| Proxy forwarding and identity | `panel/lib/server/plugin-proxy.ts` |
| Shared validation/storage service | `panel/lib/server/assets.ts`, `panel/lib/server/assetStore.ts` |
| Bounded R2 read and signed download | `panel/lib/server/r2.ts` |
| Recent-use ordering | `panel/migrations/006_asset_usage.sql` |
| Identity propagation | `plugin/core/.../plugin/CallContext.java`, `relay/RelayClient.java` |
| Download, checksum, cache, eviction | `plugin/core/.../plugin/AssetLibrary.java` |
| Tool behaviour and naming precedence | `plugin/core/.../plugin/tools/Tools.java` |
| Region wire format hardening | `plugin/common/.../common/StateArrays.java` |
| Frame documentation | `plugin/common/.../common/RelayProtocol.java` |

## Known limitations and review flags

- **Cache eviction is a full directory walk** on each paste that downloads, and it runs under the `AssetLibrary` monitor. It is bounded by 128 MB of `.bin` files, but a very large library will make this noticeable. Acceptable for phase 2; measure before optimizing.
- **The cache is per server, not per panel.** Each Minecraft server keeps its own copy. That is intended, but document the disk cost.
- **No cache invalidation from the panel.** Deletion is handled because pastes must reauthorize and resolve first; cached bytes are never trusted on their own. Confirm this with a live test.
- **No panel-side per-user rate limit on asset downloads**, beyond the relay's general behaviour and the 128-grant cap per connection. A user who pastes repeatedly re-downloads only on cache misses.
- **Save quota check is preflight plus transactional insert.** The store rechecks under a user-row lock, so the final decision is safe; the preflight exists to avoid reading chunks for a save that cannot fit.
- **`get_context` asset failures are non-fatal.** `assets_error` is intentional; do not turn it into a failed context call.
- **Shared `saveAssetData` limits now also apply to panel-created assets.** A panel export that was previously acceptable can now be refused at 2,000,000 cells or 5 MB compressed. This needs an explicit decision and documentation.
- **Base64 saves cap out around 5 MB compressed / ~6.7 MB JSON before transport.** The relay's general body cap is larger, so this asset-specific limit is the binding one. Keep the two in step if either changes.
- **No anchors or facing**, by design. Phase 3.
- **Python SDK is unchanged.** `save_blueprint(name, region)` still works, but does not expose `description`.

## Gotchas specific to this repository

- **Tool-list budget:** `McpEndpointTest#toolListStaysWithinBudget` has almost no headroom. Shorten descriptions instead of raising the limit. Run that test alone while iterating.
- **Node 24 strip-only TypeScript:** `npm test` runs `.ts` directly. Avoid TypeScript that requires transformation: parameter properties, enums, namespaces, decorators. This already broke `AssetGrants` once.
- **Module cycles:** `hub.ts` ↔ `assetStore.ts`/`assets.ts`. The asset handler's dynamic import is load-bearing. Verify panel startup after touching these files, not just tests.
- **Migration runner:** migrations are plain SQL applied on startup. `006` is an `ALTER TABLE` and a `CREATE INDEX`; verify it on a database that already has `005`, and note that MySQL has no `ADD COLUMN IF NOT EXISTS` on older versions.
- **R2 may be absent in dev.** `welcome.assets` is false without R2, and the plugin should show a clear upgrade/configuration message rather than retrying. Test that path.
- **Panel jar rebuilds:** rebuilding the panel jar while a local panel runs from `panel/build/libs/` can make it answer 500 until restarted. Restart the panel after a rebuild.
- **Relay compatibility:** the plugin refuses assets when `welcome.assets` is absent, but ordinary tools must keep working with an old panel. Do not require the user field for unrelated calls.
- **Do not log asset data or URLs.** Signed URLs are bearer credentials for 60 seconds. Log names, sizes, and status only.

## Suggested commit breakdown

Keep the phase-2 change reviewable rather than one large commit:

1. `panel`: grants, hub asset handler, proxy identity, signed bounded reads.
2. `panel`: shared save path, quota/name preflight, usage ordering + migration.
3. `plugin`: protocol fields, identity propagation, `AssetLibrary`, tool integration, skill update.
4. `tests`: panel rule tests, relay/MCP/REST tests, cache tests.
5. `docs`: plan status, panel docs, this handoff updated to final.

Each commit should build and test on its own where feasible. At minimum, the branch tip must pass all checks.

## Definition of done for phase 2

- [ ] All six phase-2 bullets from the plan are satisfied or consciously documented as deviations.
- [x] Panel production build passes.
- [x] `plugin/gradlew build` passes.
- [x] Panel tests, typecheck, and Java tests pass.
- [ ] Migration `006` applies cleanly to a database with existing assets.
- [ ] Two accounts cannot read, list, save over, or paste each other's assets, tested live.
- [ ] A deleted asset cannot be pasted from cache.
- [ ] Local blueprint names still take precedence.
- [ ] Old panel + new plugin: ordinary tools work, assets are unavailable with a clear message.
- [ ] New panel + old plugin: no breakage; assets simply are not used.
- [ ] `docs/asset-library-plan.md` and `docs/panel.md` describe what shipped.
- [ ] Tool-list budget unchanged and passing.
- [ ] A PR is opened (when authorized) with security notes and the save-path limits called out.

## Decisions still needed

1. **Save transport:** keep inline base64, or move to a presigned upload handshake before merge? Inline is simpler and enough for 5 MB; state the choice in the PR.
2. **Shared limits:** should panel-created assets keep the new 5 MB / 2,000,000-cell caps, or should the panel path keep its previous limits and only relay saves be capped?
3. **Per-user download rate limit:** add now, or accept the current grant cap for phase 2?
4. **Cache location:** keep `plugins/AIBuilder/asset-cache/`, or make it configurable with a documented default?
5. **Description in the SDK:** add `description` to `save_blueprint` now, or leave the SDK for a follow-up?

Answer these in the PR description if the user does not decide them explicitly. Do not silently change phase-3 scope to resolve them.

## First actions for you

```bash
cd /Users/chrskole/IdeaProjects/minecraft-ai-builder
git status --short --branch
git log --oneline -5
cd panel && npm test && npm run typecheck && npm run build
cd ../plugin && ./gradlew build
```

Then open `panel/lib/server/assetRelay.ts` and `plugin/core/src/main/java/me/abdiskiosk/minecraftaibuilder/plugin/AssetLibrary.java` first: those two hold the security boundary. Read `Tools.saveBlueprint` and `Tools.blueprint` next for behaviour. Only then start changing code.
