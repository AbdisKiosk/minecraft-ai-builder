# Plan: learning from corrections

When a person fixes what Claude built, the fix becomes a short lesson that Claude reads at the start of the next
session on that server. The aim is a falling correction rate, not a growing rule book.

## Implementation status

Phases 1–4 are implemented. Phase 4 deliberately uses bounded, conservative evidence:
- New tracked edits save a compressed local after-snapshot, including a one-block shell when it fits under
  `history.max-volume`. Snapshot bytes count toward history's byte quota and are pruned with the entry.
- Nearby additions must have been air in that snapshot and directly touch a non-air block the entry placed.
  Later recorded edits, unknown overlapping edits and natural changes are excluded. Older entries without a
  baseline retain original-cell detection; no historical baseline is guessed from the world now.
- The server page charts the latest 200 retained tracked Claude builds, distinguishing unchecked builds from
  checked zeroes. The rate uses corrected original cells / original changed cells; nearby additions are separate.
  Measurements are live plugin metadata, not the archive's edit-time metadata, and need the server online.
- Lessons naming both block types of a source substitution get a review warning after it recurs in two distinct
  later checked Claude builds in the same world, created after the lesson was saved or edited. This is a warning,
  not an automatic verdict or deletion. Missing sources and free-text rules without named substitutions cannot
  be evaluated. Editing resets the observation period; marking seen does not.

Scope is one list per server. Every member may read lessons and mark them seen; admins and owners may change
them. Only panel rejects/reverts prompt for feedback. These decisions supersede the open questions below.

## What counts as a correction

Three signals, strongest first:

1. **A draft is rejected** on the panel (review mode). The panel then asks for feedback on the build.
2. **A history entry is reverted** on the panel (`revertOf` is already on the entry). The panel asks the same.
3. **Blocks changed after Claude placed them.** For an entry whose `source` is `panel mcp`, a cell is corrected when
   the world no longer holds the entry's after-state and no later history entry put the new state there.

Signal 3 is found by comparing, not by listening for block events: builders fix things with WorldEdit, which fires
no Bukkit block events. `History.revert` already does this comparison to find conflicts (`BlockDiff.revert`), reading
through `service.readStepped`, so the memory rules are already met.

A correction is itself a `BlockDiff` (Claude's after-state to the state now). Rendering, the 3D view and the R2
archive therefore work on it unchanged.

## Design

### 1. Detect (`plugin/common`, pure and unit tested)
New `Corrections`: takes an entry's diff, the diffs of later entries that overlap it, and a reader of the current
state. Returns the correction diff and a summary:
- substitutions by block type (`oak_planks -> spruce_planks x212`), removals, and state-only changes
  (`oak_stairs facing x18`), each with a count and at most five coordinates;
- the share of the entry's cells that were corrected.

Noise is dropped: changes below a threshold (a few cells, or a small share), and changes the world makes by itself
(leaf decay, grass and snow spread, crop growth, flowing liquids, fire). Check whether `BlockTraits` already has
these groups.

Limit of the first version: it sees only cells Claude changed. Blocks a builder adds beside the build need the
entry's full after-snapshot, which today exists only in the archive (`blocks.json.gz`). Phase 4 covers that.

### 2. Compute and store (`plugin/core`)
- `History.corrections(id)`: reads the entry's boxes with `readStepped`, runs `Corrections`, stamps the entry with
  `corrected: {cells, share, time}`.
- When: on request from the panel, and in the background at the first `get_context` of a session for the caller's
  Claude entries from the last 7 days that fit under `history.max-volume`. Never inside `get_context` itself.
- New `Lessons` class, stored in `lessons.json` beside `areas.json`, following `Markers`: per server, each lesson has
  text (at most 160 characters), its origin (`feedback`, `claude`, `manual`), the entries it came from and who wrote
  it. A lesson applies as soon as it is saved; there is no approval step.
- Revert and draft reject take an optional `feedback` text and a `remember` flag (see 3).

### 3. Feedback and review on the panel (`panel/`)
- **Feedback prompt:** after a person rejects a draft or reverts an entry, the panel asks "Do you want to give
  feedback on this build?". It comes after the action, never in front of it, and can be skipped with one click.
  The dialog has a text field and a "Remember this for future builds" box, ticked by default.
  - The feedback is stored on the entry and delivered to Claude once, as player notes are today, so Claude can
    try the same build again with it.
  - With the box ticked, the text is also saved as a lesson (origin `feedback`), cut to 160 characters.
- The history entry page gets "Changed since": the correction's stats and its highlight in the 3D viewer.
- "Save as lesson" with editable text, prefilled from the summary ("Use spruce_planks, not oak_planks, for floors").
- A Lessons page per server: list, edit, delete, and for each lesson its origin and the build it came from. Since
  nothing is approved beforehand, this page is where a wrong lesson is caught. It talks to the plugin through the
  existing `/s/<server>/api/` proxy, as the map's areas do. No new table is needed; archive the list with the entry
  detail only if lessons must outlive the server.

### 4. Deliver to Claude
- `get_context` gains `lessons`: at most 12 texts. That bounds the cost at about 500 tokens a session.
- When new corrections or feedback exist, `get_context` also gains `corrections`: the summaries (counts and a few
  coordinates), the feedback texts, and one line saying how to save a lesson. It costs tokens only when there is
  something to learn.
- Claude saves with a `lesson` tool that is registered but unlisted, so `tools/list` does not grow. Check first
  that unlisted tools can be called over MCP; if not, make it an op-free argument of an existing tool.
- Claude's lessons (origin `claude`) apply at once. To keep a one-off fix from becoming a rule without an approval
  step: Claude may save a lesson only from a correction above the noise threshold or from feedback; a new lesson
  shows as "new" on the panel until someone has seen it; and when the list is full, the oldest `claude` lesson
  makes room, never one a person wrote.

### 5. Measure
- Correction rate per entry (corrected cells / placed cells), charted per server on the panel.
- A lesson whose substitution keeps recurring after it was approved is flagged as not working.
- `python3 tools/mcp_cli.py cost` before and after, and `McpEndpointTest#toolListStaysWithinBudget` must still pass.

## Phases

| Phase | Delivers | Where |
|---|---|---|
| 1 | `Corrections` and its tests; `History.corrections(id)`; the `corrected` stamp | `plugin/common`, `plugin/core` |
| 2 | `Lessons` store; feedback prompt on reject and revert; Lessons page; `lessons` in `get_context` | `plugin/core`, `panel/` |
| 3 | "Changed since" on the entry page; background check at session start; `corrections` in `get_context`; the `lesson` tool | `plugin/core`, `panel/` |
| 4 | Additions beside a build, from a stored after-snapshot; correction rate chart; lessons that do not work | all |

Phase 2 is already useful without phase 3: a person's feedback becomes the lesson, Claude follows it.

## Decided

- **Approval:** none. Lessons apply at once and are removed or edited on the Lessons page.
- **Feedback:** the panel asks for it after a draft is rejected and after a build is reverted.

## Decisions to make

- **Scope:** one list per server (recommended), or also per user or per marked area.
- **Who may edit and delete lessons:** any member, or only the server's owner. See `panel/lib/server/roles.ts`.
- **Rejects and reverts made by Claude or in the game:** no prompt (recommended; there is nobody at the panel), or
  a note to the player in chat.

## Risks

- **Wrong attribution:** a changed block may come from another plugin or a different project in the same place.
  The noise threshold and the "new" mark on the Lessons page cover this; nothing stops a wrong lesson beforehand.
- **Chunk loads:** a background check reads regions nobody is in. It must go through `BuildService#reading`, one
  entry at a time, and skip when `MemoryGuard` refuses.
- **Lesson rot:** 12 lessons is a hard cap, so the panel must make deleting and merging easy.
