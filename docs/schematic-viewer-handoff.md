# Handoff: 3D view on EngineHub's SchematicWebViewer

> Historical. This was written for the self-hosted Java panel, which is gone: the viewer now lives in the panel's
> `panel/lib/` and `panel/components/Pictures.tsx`, and the paths, commands and `panel.json` below no longer exist.

## Continuation status (2026-11-12)

The implementation is wired and builds. `npm run typecheck`, all eleven `npm test` cases and `./gradlew build`
pass (234 Java tests), including the authenticated client-jar route test. Browser checks used the production panel
jar and RealGolf 26.3.build.249, history `hmwci3j2d` and draft `dmwchw0qy`.

Verification results:

- History: textured Before / After / Changes, only-changed, cut height and PNG restoration checked. Three picked
  changed blocks were compared with `/blocks`, including coordinates, compact before/after labels and kind.
- Draft: all three modes, only-changed, cut height and PNG restoration pass with the final right-handed view.
- An asymmetric two-block fixture confirms east is left from a north-facing camera and exact picking still works.
- Old-plugin, missing-WebGL and client-jar failure messages, rapid control changes and unmount are checked.
- **Performance follow-up:** large clubhouse redraws take tens of seconds in headless Chrome; one history redraw
  exceeded a 60-second test deadline. The library re-parses the jar and rebuilds the scene per draw. Check interactive
  performance with a normal GPU-backed browser before shipping; do not rely on the original one-second estimate.
  Camera handedness was fixed after the first complete history control run; the final draft and asymmetric-fixture
  checks cover that change. Screenshots and temporary Playwright scripts are outside the repo.

Changes since the original WIP:

- Pictures owns React controls, debounces redraws and ignores stale completion callbacks. Both detail pages pass
  the server's `mc`. Missing versions, WebGL and old plugins get explanatory messages; PNGs remain the default.
- The schematic NBT uses `Int8Array` for block bytes. Browser Buffer constructors can differ between bundles;
  `Buffer` there made nbt-ts throw `Invalid tag value` despite passing Node checks.
- **Complete states are essential.** Reads/history omit default properties. `/blocks` now includes `models`,
  parallel to the compact `palette`, with defaults restored through `WorldEditBridge.defaultState`. WE7 uses the
  registry; WE6 uses the existing legacy table. Resolution happens once per type, on the server thread. The
  browser uses `models` in NBT and compact `palette` labels for inspection. Update plugins with the panel; old
  plugins keep working for PNGs but show a 3D upgrade message.
- **26.x texture references need adaptation.** `modelResources.ts` scans the client jar once per mounted viewer
  and builds a small, in-memory resource pack converting texture `{ sprite, ... }` objects to string references.
  The library searches the first jar before packs, so that compatibility pack is supplied first, with the original
  jar as fallback. No jar on disk is modified and no Minecraft data is committed. `jszip` is now a direct dependency.
- Draws are serialized/coalesced. The downloaded jar/override blob URLs are reused until unmount; the library
  still re-parses them per draw. Failed renders dispose the engine even when the library returns no handles.
- The library's Babylon `Intermediate` performance preset marks every model instance unpickable. Enable picking
  on visible/enabled model meshes after rendering, while keeping hidden prototypes and change overlays unpickable.
  Recover the clicked cell from the instance's translation relative to its hidden prototype, rather than rounding
  the face hit: thin models and exact cell-edge hits otherwise report a neighbouring air cell.
- Set the scene to right-handed in `getClientJarUrl`, which runs before model/material construction. This matches
  Minecraft/PNG north elevations (east on the left), rather than mirroring the build with Babylon's default.
- Gradle's optional `public/` input is a file tree, because removing the only asset otherwise broke clean builds.
- `panel/web/tests/` covers conversion, filters, cuts, varints, full model states and texture-reference adaptation.
  Run `npm test` with Node 22.18+; building the app still needs only Node 20.9+.
- The local dev plugin was updated and Minecraft restarted gracefully. The previous jar and the temporary
  Playwright check/screenshots are in the approved temp directory, under `opencode/viewer-check/`. No builds,
  drafts or history were edited by the checks.

**Before shipping/merging:** licensing review is required, along with the performance follow-up above. SchematicWebViewer 5.0.1 is MIT, but its bundled
`@enginehub/schematicjs` 0.10.0 dependency declares GPL-3.0 (confirmed in its upstream LICENSE too). Resolve
distribution/source obligations and third-party notices; do not assume the viewer's MIT label covers the whole
bundle. No repository licensing has been changed. Open the PR as a draft until this is resolved.

The original handoff below is retained for context; its TODOs and “never run” notes are historical, not current.

---

## Goal

The panel's Pictures card (history entry and draft pages) has a "3D (interactive)" entry in its view dropdown.
It was drawn with hand-written three.js and coloured boxes (merged in PR #5). This change replaces it with
[EngineHub/SchematicWebViewer](https://github.com/EngineHub/SchematicWebViewer) (`@enginehub/schematicwebviewer`
5.0.1, MIT, Babylon.js), which draws real Minecraft block models and textures. The PNG views stay unchanged and
remain the default.

## Where things are

- **Branch:** `schematic-viewer`, cut from `origin/main` at `143a1e7`, which already has the Next.js panel (`panel/web`) and PR #5.
- **Remote:** push to `origin` (AbdisKiosk) and open the PR there. The old `fork` remote is no longer used.
- **Leave out of commits:** `builds/clubhouse2/`, `docs/3d-viewer-brief.md`, `docs/setup.md` and `panel/package-lock.json`. They were untracked before this work and are not part of it; `panel/package-lock.json` is an empty stray from running npm in `panel/`.

## Design decisions

1. **The browser builds the schematic, not the plugin.**
   - The plugin's `/blocks` JSON is already in Sponge order (y, then z, then x).
   - `panel/web/lib/schematic.ts` turns it into a gzipped Sponge v2 schematic in base64, which is what `renderSchematic` takes.
   - Each toggle (mode, only changed, cut height) builds a new schematic locally. No new endpoint and no NBT writer in Java.
2. **The panel serves the Minecraft client jar.**
   - The library needs a complete client jar for models and textures. Its default fetches from Mojang through a CORS proxy.
   - Instead, the panel route `GET /api/minecraft/{version}/client.jar` downloads it once from Mojang's piston-meta manifest, checks Mojang's SHA-1, caches it in `<dir of panel.json>/cache/client-jars/<version>.jar`, and serves it behind the login with `Cache-Control: private, max-age=31536000, immutable`.
   - The viewer passes `getClientJarUrl: async () => '/api/minecraft/<mc>/client.jar'`. Because of the immutable caching, the browser downloads it once per version.
   - The first use of a version needs internet on the panel host.
   - Licence note for the PR: the jar is fetched from Mojang by the user's own panel and shown only to its logged-in users. Nothing is redistributed in the repo or the jar.
3. **Version mapping (`ClientJars.clientVersion`).**
   - Paper reports e.g. `26.3.build.248`; `.build.*` is stripped.
   - Servers before 1.13 map to `1.13.2`, because WE6 servers report flattened block names (`LegacyBlockTable`).
   - The version string is validated by a regex, so `../` cannot escape the cache folder.
4. **Changes are marked by an overlay; the library cannot tint.**
   - In Changes mode, after `renderSchematic` returns, `viewer3d.ts` adds translucent thin-instanced boxes per kind (green / red / yellow, the `IsoRenderer` colours) to the library's Babylon scene, through `handles.getEngine().scenes[0]`.
   - Removed blocks are drawn from their before state (`cells()` in `schematic.ts`).
   - **Gotcha:** the library builds a selection octree before returning, so `scene.createOrUpdateSelectionOctree()` must be called again after adding meshes, or they are not drawn.
5. **Click to inspect** is our own `scene.pick` on the library's canvas.
   - The library places block (x, y, z) at `x - W/2 + 0.5` (and the same for y and z).
   - So the cell is `floor(p + size/2)`, where `p` is the hit point stepped 0.01 back along the normal.
6. **Each toggle re-renders.** Each `show()` builds a new canvas and `renderSchematic`, then destroys the old one and copies over the ArcRotateCamera's alpha, beta, radius and target, so the camera stays where the user left it. A newer `show()` cancels an older one still loading (`shown` counter).
7. **`Buffer` polyfill.** Both nbt-ts and the library call Node's global `Buffer`, which browsers lack. `schematic.ts` sets `globalThis.Buffer ??= Buffer` from the `buffer` package; `viewer3d.ts` imports `schematic.ts`, so it runs first.

## Done (in the WIP commit)

### Plugin
- `DiffImages.encode`: `palette` is now a plain list of state strings. The `rgb`, `transparent` and `boxes` fields and the `BlockColors`/`BlockShape` imports are gone, since the library uses real models.
- `DiffImagesTest`: updated, and `paletteListsEachStateOnceInFirstSeenOrder` replaces the colour/shape test.

### Panel (Java)
- New `ClientJars.java` (download, SHA-1 check, cache, per-version lock, `UnknownVersion` → 404, other `IOException` → 502).
- `PanelServer`:
  - adds the field and a package-private constructor `PanelServer(PanelConfig, ClientJars)` for tests;
  - the public constructor uses `ClientJars.MOJANG_MANIFEST`;
  - adds the route `/api/minecraft/{version}/client.jar` after the drafts `blocks` route.
- Tests:
  - `ClientJarsTest`: version mapping, single download, bad checksum leaves no file, unknown and malformed versions. **All 4 pass.**
  - `FakeMojang`: local manifest, version and jar server.
  - `PanelServerTest#servesTheMinecraftClientJarToLoggedInUsers`: 401 without login, 200 with the bytes and an immutable header, 404 for an unknown version. **Not run yet**; see the testing gotcha below.

### Panel web
- `package.json`:
  - removed `three` and `@types/three`;
  - added `@babylonjs/core ^8.56.2` (imported directly, deduped with the library's), `@enginehub/schematicwebviewer ^5.0.1`, `@enginehub/nbt-ts ^1.4.1` and `buffer ^6.0.3`;
  - `package-lock.json` is updated to match.
- Deleted `lib/viewer3d.ts` (three.js) with `git rm`. A new `lib/viewer3d.ts` replaces it at the same path.
- Deleted `public/three.LICENSE`.
- New `lib/schematic.ts`: `BlockData` type, `decode()`, `cells(diff, view)` and `toSchematic(diff, cells)`.
- New `lib/viewer3d.ts`: `supported()` and `createViewer(holder, diff, jarUrl, onPick)` → `{ show(view), dispose() }`. It has never been run or type-checked.

## Still to do

1. **`components/Pictures.tsx`:**
   - Replace the old `Viewer3d` function (it imports the deleted three.js API: `BlockData`, `Viewer`, `mount`) with a React component that keeps the controls in React state.
   - Add an `mc` prop and pass it from `DiffPage.tsx` and `DraftPage.tsx` as `servers.get(server)?.info?.mc`. `DraftPage` needs `servers` passed in the same way `DiffPage` gets it. Without `mc`, show "3D needs the server's Minecraft version".
   - Sketch (unverified):

   ```tsx
   function Viewer3d({ url, mc }: { url: string; mc: string }) {
     const holder = useRef<HTMLDivElement>(null);
     const [viewer, setViewer] = useState<Viewer | null>(null);
     const [diff, setDiff] = useState<Diff | null>(null);
     const [note, setNote] = useState('Loading blocks…');
     const [mode, setMode] = useState<Mode>('changes');
     const [onlyChanged, setOnlyChanged] = useState(false);
     const [cut, setCut] = useState(0);           // slider value (height in the box)
     const [picked, setPicked] = useState<Picked | null>(null);
     useEffect(() => {                            // load data + module once per url
       let live = true, v: Viewer | null = null;
       Promise.all([import('../lib/viewer3d'), import('../lib/schematic'), api<BlockData>(url)]).then(([m, s, data]) => {
         if (!live || !holder.current) return;
         if (!m.supported()) throw new Error('This browser has no WebGL 2, which the 3D view needs.');
         const d = s.decode(data);
         setDiff(d); setCut(Math.min(d.height - 1, d.topChange + 1));
         v = m.createViewer(holder.current, d, `/api/minecraft/${encodeURIComponent(mc)}/client.jar`, setPicked);
         setViewer(v);
       }).catch(ex => live && setNote(message(ex)));
       return () => { live = false; v?.dispose(); };
     }, [url, mc]);
     useEffect(() => {                            // re-render on toggles, debounced for the slider
       if (!viewer) return;
       setNote('Drawing…');
       const t = setTimeout(() => viewer.show({ mode, onlyChanged, cut }).then(() => setNote(''), ex => setNote(message(ex))), 250);
       return () => clearTimeout(t);
     }, [viewer, mode, onlyChanged, cut]);
     // render: .viewer3d > .v3-bar (Before/After/Changes seg buttons, "only changed blocks" checkbox enabled in changes
     // mode, range input min=0 max=diff.height-1 showing diff.origin[1]+cut, Reset view optional), .v3-stage ref={holder},
     // .v3-info showing note or picked: `${pos.join(' ')}  ${before} → ${after}  (${kind ?? 'unchanged'})`
   }
   ```

   - The first draw downloads the client jar (about 30 MB on first use per version, then from the browser cache) and parses it. Show "Loading Minecraft textures…" while that happens.
2. **CSS (`app/globals.css`):**
   - The `.viewer3d`, `.v3-bar`, `.v3-stage`, `.v3-info` and `.renders.one` rules are still there and still fit.
   - Remove `.v3-reset` usage if Reset view is dropped. A reset could also just recreate the viewer.
3. **Docs:** in `docs/panel.md`, mention that the 3D view downloads the Minecraft client from Mojang on first use, into `cache/client-jars/` beside `panel.json` (needs outbound HTTPS). CLAUDE.md needs no change.
4. **Build:** run `cd panel/web && npm run typecheck`, then `./gradlew build` (this also runs `npm ci` and `next build`).
5. **Check in the browser:**
   - Start the local panel from `panel/run` with `java -jar ../build/libs/AIBuilderPanel-0.1.0-SNAPSHOT.jar`; the login is in `panel/run/local-test.txt`.
   - Connect the RealGolf dev server (`~/IdeaProjects/RealGolf`, start it with `sh ./gradlew runServer`), after copying `plugin/build/libs/AIBuilder-0.1.0-SNAPSHOT.jar` into `RealGolf/run/plugins/`. Its `mc` reports as `26.3.build.24x`, which tests the `.build` stripping.
   - Check on history entry `hmwci3j2d` (985 changes, clubhouse) and draft `dmwchw0qy`:
     - textures load;
     - Before / After / Changes and only-changed work;
     - the cut slider works;
     - the overlay colours match the legend;
     - clicking a block shows the right coordinates (compare with the Changed blocks table);
     - switching back to Isometric restores the PNGs.
   - Watch the console for missing-model logs; the library `console.log`s unknown block states.
6. **Commit, push and open the PR** against `origin` main. Suggested title: "Draw the panel's 3D view with EngineHub's SchematicWebViewer". Mention the client jar download in the PR body.

## Gotchas

- **`-PskipWeb` breaks the server tests.** `./gradlew ... -PskipWeb` makes every `PanelServerTest` and three `RelayClientTest` tests fail with "Static resource directory '/web' does not exist". This was already the case on main and is not caused by this work. Run the tests without `-PskipWeb`, which needs Node, or as part of `./gradlew build`.
- **A rebuilt jar breaks the running panel.** Rebuilding the panel jar while the local panel runs from `panel/build/libs/` makes it answer 500. Restart it afterwards.
- **`npm run dev` for UI work:** `cd panel/web && npm run dev` proxies `/api` and `/events` to a panel on `:8080` (`PANEL_URL`), so the client.jar route is proxied too.
- **The library's re-render cost:** `renderSchematic` re-fetches the jar URL and re-parses it with JSZip on every call. The immutable cache header makes the fetch cheap, but the parse is about a second per toggle. If that feels slow, cache the parsed resources: the library has no hook for it, so it would mean forking or patching.
- **Coordinate handedness:** Babylon is left-handed and the library maps schematic z straight to Babylon z, so the view may look mirrored compared with the isometric PNGs. Check this in the browser. If it is mirrored, the click mapping in `viewer3d.ts` still matches, because it uses the library's own placement formula.
- **Constraints from CLAUDE.md:** do not touch MCP `tools/list` or `McpEndpoint.INSTRUCTIONS`. Nothing browser-side goes into the plugin jar.

## Files touched

```
plugin/src/main/java/.../plugin/DiffImages.java          (palette → strings)
plugin/src/test/java/.../plugin/DiffImagesTest.java
panel/src/main/java/.../panel/ClientJars.java            (new)
panel/src/main/java/.../panel/PanelServer.java           (ClientJars field, ctor, client.jar route)
panel/src/test/java/.../panel/ClientJarsTest.java        (new)
panel/src/test/java/.../panel/FakeMojang.java            (new)
panel/src/test/java/.../panel/PanelServerTest.java       (route test)
panel/web/package.json, package-lock.json
panel/web/lib/schematic.ts                               (new)
panel/web/lib/viewer3d.ts                                (rewritten for the library)
panel/web/public/three.LICENSE                           (deleted)
panel/web/components/Pictures.tsx                        (TODO)
panel/web/components/DiffPage.tsx, DraftPage.tsx         (TODO: pass mc)
docs/panel.md                                            (TODO)
```
