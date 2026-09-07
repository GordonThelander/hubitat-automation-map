# vis-network capability framework

Why this exists: the framing bugs fixed on 2026-09-05/06 took five attempts because each fix was
based on an assumption about vis-network's behaviour, shipped to a real hub, and only then proven
wrong. This document separates what is actually documented, what this project has independently
proven, and what remains an untested assumption in the current code - so the next change to this
area starts from that record instead of from memory.

**Pinned version:** `vis-network@10.1.1` (`apps/automation_map.groovy`, loaded from unpkg with a
`crossorigin`/`integrity` hash). Nothing below is assumed to hold for any other version - the
`stabilizationIterationsDone` behaviour in particular is version-sensitive.

**Validation status, 2026-09-06:** every claim below marked "proven" or "reproduced" was checked
against the real pinned build in a standalone harness
(`Bucket/inert-shelf-harness/validate-framework.html`) before this document was written up for
publication, not accepted from documentation or a search result alone. One claim in an earlier draft
(the `stabilizationIterationsDone` explanation) was corrected as a direct result of that testing - see
the Events section for what changed and why.

**Scope:** only the API surface this app actually calls, confirmed by grep against the live source,
not the library's full surface:

`new vis.DataSet()`, `.add()`, `.clear()`, `.update()`, `.getIds()`, `.keySet()`, `.values()`
`new vis.Network()`, `.fit()`, `.moveTo()`, `.getBoundingBox()`, `.getPositions()`, `.getScale()`,
`.getViewPosition()`, `.setOptions()`, `.on()`/`.once()` for `stabilizationIterationsDone`,
`afterDrawing`, `blurNode`, `hoverNode`, `click`
Construction options in use: `physics.stabilization.iterations`, `physics.barnesHut.*`,
`interaction.hover`/`tooltipDelay`, `edges.smooth.type`.

## How to use this document

Documented fact and proven-in-this-project fact are marked separately on purpose. A behaviour being
documented does not mean it has been checked against what this app actually does with it - several
of today's bugs were each individually "correct" per the docs and still wrong in combination. Before
relying on anything marked **assumed** below, follow the verification protocol at the end.

## DataSet

- **`update()` is upsert - documented.** ["When an item does not exist, it will be created."](https://visjs.github.io/vis-data/data/dataset.html)
  `add()` throws on a duplicate id instead; `updateOnly()` throws if the id is *absent* instead -
  the strict counterpart `update()` does not have.
- **Proven in this project, 2026-09-05, re-verified 2026-09-06:** `shelveInertNodes()` calls
  `nodes.update()` with every inert node's id. Called against a dataset already narrowed to a
  focused view, this silently re-inserted every inert node rather than failing - a 6-node dataset
  became 26. The upsert mechanism itself was re-confirmed directly in
  `validate-framework.html`: `update()` on an id never previously added inserted it and grew the
  DataSet's length, rather than throwing or being a no-op.
- **Hardening this documentation surfaces, not yet applied:** `updateOnly()` would have made this
  fail loudly (an exception) instead of silently succeeding on the wrong dataset. Worth using
  wherever an update is only ever supposed to touch pre-existing items.

## Network.fit()

- **Documented options:** `{ nodes?: [...], minZoomLevel?, maxZoomLevel?, animation? }`.
  ["Default zoom constraints range from zero (excluded) through one."](https://visjs.github.io/vis-network/docs/network/)
- **Proven in this project, 2026-09-05, re-verified 2026-09-06:** confirmed live - a 6-node focused
  view fitted to exactly `scale: 1.000` with no `maxZoomLevel` set, never magnifying past 1:1 even
  though the nodes occupied a fraction of the canvas. `maxZoomLevel: 2.0` produced `scale: 1.33` on
  the same fixture. This was the second of two independent causes behind the "focused view too small
  to read" defect - fixing the first cause (see DataSet above) alone left the view pinned at 1.0, not
  magnified. Re-confirmed in isolation in `validate-framework.html` on a fresh 3-node graph:
  `fit()` with no `maxZoomLevel` capped at exactly `1.0000`; `fit({maxZoomLevel: 3.0})` reached
  `3.0000` on the identical graph.
- **Superseded in this app, 2026-09-06:** `fit()` is no longer called directly for a narrowed view.
  `fitCurrentView()` now computes position and scale itself from `getBoundingBox()` and
  `getPositions()` and calls `moveTo()`, because `fit()` has no way to exclude the on-canvas panel
  overlays from its bounding-box calculation (see `visibleRegion()` below). `fit()` is still used
  unmodified for the whole-hub view, where no panel-exclusion is needed.

## Network.getBoundingBox()

- **Documented:** `getBoundingBox(nodeId)` (single id, not an array) returns
  `{top, left, right, bottom}` in canvas units, **"including node labels."**
- **Proven in this project, 2026-09-06:** measured directly on the hub - a node with a three-line
  wrapped label extended ~69 canvas units below its own centre against ~17 above. Framing that used
  `getPositions()` (centres only) instead of `getBoundingBox()` left such labels clipped at the
  canvas edge even though every node *centre* was correctly inside the viewport - this is why
  "verify centres are on-screen" was not sufficient verification for a defect the user could see.
  Re-confirmed in isolation in `validate-framework.html`: a node with a one-character label had a
  bounding-box vertical span of 28px; an otherwise identical node with a long wrapped label had a
  span of 196px, on the exact same font/scale settings - the label is what accounts for the
  difference, not node size or position, and `getPositions()` for both returned only a bare `{x, y}`
  point with no size information at all.
- **In use:** `fitCurrentView()`'s content box is now the union of every visible node's
  `getBoundingBox()`, not of `getPositions()` centres, specifically because of the above.

## Network.getPositions() / getScale() / getViewPosition() / moveTo()

- **Documented shapes**, all confirmed matching this app's usage: `getPositions([ids])` ->
  `{id: {x,y}}` in canvas units (empty object for an unknown id, not a throw); `getScale()` -> a
  number, `1.0` = 100%, approaching `0` = infinite zoom-out; `getViewPosition()` ->
  `{x, y}` canvas-unit camera centre; `moveTo({position, scale, offset, animation})` - "you will have
  to define at least a scale, position or offset."
- **In use:** `fitCurrentView()`'s own framing math - not `fit()` - computes `scale` from the ratio of
  usable canvas area to content bounding-box area, then `moveTo()`s to the content centre offset by
  half the obscured width in canvas units (`obscuredPx / scale`, converting screen pixels to canvas
  units by dividing by the scale being moved *to*, not the current one).

## Events

- **`stabilizationIterationsDone` - documented behaviour:**
  ["Fired when the 'hidden' stabilization finishes. This does not necessarily mean the network is
  stabilized; it could also mean that the amount of iterations defined in the options has been
  reached."](https://visjs.github.io/vis-network/docs/network/) Stabilization itself can finish
  early on convergence, well under the configured `iterations` cap - it is not a fixed-duration
  wait.
- **Reproduced mechanism, 2026-09-06 - corrects an earlier draft of this document.** An initial
  draft attributed the unreliability to a vis.js GitHub issue
  ([visjs/vis#3468](https://github.com/visjs/vis/issues/3468)) describing the event firing only once
  across repeated physics runs on one `Network` instance, found via web search rather than tested.
  Built a standalone harness (`Bucket/inert-shelf-harness/validate-framework.html`) against the
  pinned `vis-network@10.1.1` build to check it directly, and the real mechanism is narrower and more
  precise than that issue describes:
  - Data supplied to `new vis.DataSet(...)` **at Network construction time** stabilizes and fires the
    event reliably - confirmed on a freshly constructed instance that had never stabilized before.
  - Data supplied afterward via `nodes.clear(); nodes.add(...)` on an **existing** `Network` instance
    does not fire the event - confirmed on the very first such cycle, not only after repetition. A
    15-round loop of clear/add cycles fired the event 0/15 times.
  - This exactly explains this app's own behaviour: `nodes`/`edges` are seeded via the `DataSet`
    constructor before `new vis.Network(...)` is called, so the page's own first load stabilizes
    correctly - matching what was observed live throughout this project. `applyFilters()` then
    redraws every focus change via `nodes.clear()`/`nodes.add()` on that same already-constructed
    instance, which is precisely the case proven not to fire.
  - visjs/vis#3468 is left linked above as related corroboration that this event is known upstream to
    be unreliable in some multi-run scenarios, not as the explanation - its specific claim (fires once
    across many runs) does not match what was measured here (fires zero times, from the first
    reused-instance cycle onward).
- **Proven in this project, 2026-09-05:** measured directly on the hub - a device focus left the
  view at whole-hub scale in a ~3x8px cluster, meaning `fitCurrentView()` never ran at all, consistent
  with the mechanism above. The fix is a bounded `setTimeout` fallback (1500ms) guarded so it and the
  real event handler cannot both fire.
- **`afterDrawing`** - documented as firing after every canvas render, receiving the canvas context;
  used only to draw the inert-node shelf divider line, in the network's own coordinate space.
- **`blurNode`/`hoverNode`/`click`** - documented shapes match usage; not otherwise notable.

## What "the free area to draw in" actually requires (not a vis-network API)

The panel-overlap defects (content running under the legend, the controls menu, or an open flow/
external-systems/pivot panel) are not a vis-network behaviour at all - vis-network has no concept of
DOM overlays sitting on top of its canvas. `visibleRegion()` in `apps/automation_map.groovy` computes
this app-side: it walks the legend, controls menu and every panel via `getBoundingClientRect()`,
decides which side of the canvas centre each occupies by its own midpoint, and returns the
`{left, right}` bounds still free. Two behavioural facts this depends on, both proven live rather
than assumed:

- Panels are populated *after* being shown (`bringToFront()` then a separate `*Load()` call), so a
  panel measured at the moment it opens is measured empty and its later growth is invisible to a
  one-shot calculation. Geometry is now watched with a debounced `ResizeObserver` on every overlay
  rather than recalculated only at open/close.
- A narrowed view's own settle was visibly watched flying apart before being corrected - not a
  vis-network limitation, but a consequence of rebuilding the DataSet with physics live rather than
  running the settle hidden. The canvas is now hidden with `opacity` (never `display:none`, which
  collapses `clientWidth` to zero and breaks the framing calculation that reads it) for the duration
  of a narrowed-view settle, and revealed once framed.

## Verification protocol for the next change here

In order, cheapest first:

1. **Check this document.** If the behaviour is listed as proven, it holds for `10.1.1`; if listed
   as documented-only, treat it as a hypothesis, not a fact.
2. **Check the real docs**, linked above, for anything not yet covered here.
3. **If still uncertain, build a standalone harness** - `Bucket/inert-shelf-harness/` is the
   precedent - loading the exact pinned `vis-network@10.1.1` build, reproducing only the specific
   mechanism in question, and printing real measured numbers. Not a mock of the library; the actual
   UMD build the app loads.
4. **Only then change the app**, and verify the change on the actual hub before calling it done -
   `groovyc`/`validate.ps1` prove the Groovy compiles, neither touches the embedded JavaScript at
   all, and a harness proves the library's behaviour in isolation, not that this app's markup and
   panels interact with it correctly. Twice on 2026-09-06 a fix was verified against node *centres*
   and shipped while the visible defect was in the *label*, which centres do not cover - measure the
   thing a user would actually see, not the thing the code computes.

## Still assumed, not yet proven - flagged for whoever touches this next

- `ResizeObserver` availability is checked (`typeof ResizeObserver === 'undefined'`) but the
  degraded behaviour with no observer - panels simply never trigger a reframe on resize - has not
  been exercised on an actual old-Chromium hub browser.
- The 1500ms `stabilizationIterationsDone` fallback timeout is sized from observed settle times on
  this app's own graphs (up to a few hundred nodes). Not verified against a much larger hub's worth
  of nodes, where genuine stabilization could plausibly still be running at 1500ms.
- `visibleRegion()`'s left/right split by panel midpoint has only been exercised against the panels
  that exist today, all of which are either genuinely left- or right-anchored. A panel spanning both
  halves of the canvas is unhandled and untested.
