# Serving the map's page from File Manager

Status: design and feasibility study, 2026-10-13. Nothing in `apps/` changes with this document.

## Why

`apps/automation_map.groovy` is about 1.56 MB. The hub compiles the whole file on every save, and every
string literal in it ends up in the compiled class. The map page (its HTML, CSS and JavaScript) is one of
those literals. HAI already keeps its large pages out of its Groovy: the editor and the migration page live
in the hub's File Manager, and the app serves them only when their SHA-256 matches the hash it was built
with. This document measures what moving Automation Map's page the same way would gain, and how to do it.

## 1. What the file holds today

Measured on the 2.4.38 batch (`cloud/am-batch-2438`).

| Part | Bytes | Share of file | `${...}` interpolations | Static? |
|---|---:|---:|---:|---|
| Whole file | 1,563,328 | 100% | | |
| `buildMapHtml()` page string, total | 563,538 | 36% | 50 | |
| of which the main `<script>` | 475,964 | 30% | 40 | Mostly. 4 inject data, 26 are URLs and settings, 10 are empty `${''}` markers |
| of which the `<style>` block | 68,470 | 4.4% | 3 | Yes. 1 real (the font URL's branch), 2 empty `${''}` markers |
| of which the markup outside style and script | 18,493 | 1.2% | 7 | No: counts, dev badge, branch, seasonal image |
| of which a small offline-check `<script>` | 373 | | 0 | Yes |
| `scanButtonHtml()` | 13,840 | 0.9% | 11 | No |
| Other HTML in ordinary strings (settings pages) | about 7,800 | 0.5% | many | No |
| Groovy code and its comments | about 978,000 | 63% | | |

Points worth knowing:

- The `${''}` markers are not data. They split the page into several compiled string constants, because
  the JVM refuses a single string constant longer than 65,535 bytes. Every edit near a marker has to keep
  each piece under that limit.
- About 151 KB of the main script and about 34 KB of the style block are comments. Because they sit inside
  a Groovy string, the production builder's comment stripper (which removes Groovy comments only) leaves
  them in, and every map open sends them to the browser.
- The script contains no `\$` escapes and 34 backslashes, so extracting it is mechanical but not a plain
  copy: each backslash must be read as Groovy would read it.
- Interpolations in the script: `GRAPH`, `SCAN_META`, `RM_CONSTRUCT_VOCABULARY` and `HUB_NAME` (the data),
  about 20 endpoint URLs (`getLocalURL` and `getCloudURL`), `APP_VERSION` twice, the channel twice,
  `getLocalOrigin()` twice and `app.id` once.

A local compile with Groovy 2.4.21 (desktop JVM, not the hub) gives the scale of the effect:

| Build | Source bytes | Compiled class bytes | Compile time |
|---|---:|---:|---|
| As today | 1,563,328 | 3,865,934 | 7 to 15 s |
| Style block removed | 1,494,859 | 3,797,349 | 6 to 10 s |
| Whole page string removed | 999,901 | 3,300,599 | 7 to 10 s |

So the page is about a third of the source but only about 15% of the compiled class, and compile time on
the desktop is dominated by the Groovy code, not the strings. The real gain is memory: the page's string
constants are held for as long as each loaded copy of the class lives, and the hub has already failed a
save on Metaspace (2026-10-05). Moving the page out removes about 565 KB from every loaded copy, and the
page is then read only while someone has the map open. This is worth doing, but it is not a fix for slow
saves on its own; it should be measured on the hub before and after (see section 5).

## 2. The design

### The pattern, as HAI does it

- The page is a plain file in the repository (`engine/app/editor/editor.html`).
- The Groovy carries the file's name and its SHA-256 as constants. HAI's build writes the hash from a
  `//#sha256 NAME path` line, so it can never be stale.
- On each request the app reads the file with `downloadHubFile`, hashes it, and serves it only on an exact
  match. A missing file or a file of another build gets a short page saying so and what to do. A matching
  page is cached in a static map keyed by the hash, so the hub hashes 600 KB once per build, not per open.
- `upload-editor-page.ps1` uploads each file and reads it back through `/local/<name>` to check the hash.
  `deploy-hub.ps1` runs it before saving the app, so the file the new build expects is already there.

### What Automation Map does differently

Automation Map has no build step: the Groovy file in `apps/` is what HPM installs. So:

1. **Files.** The static parts move to `ui/` in the repository: `ui/map.css`, then `ui/map.js`. Each file
   is the text exactly as the browser receives it today (Groovy escapes resolved, `${''}` markers removed).
2. **Hash constants.** `@Field static final String MAP_CSS_SHA256 = '...'` in the app. A new
   `tools/ui_files.py --write` updates the constant from the file, and `--check` fails CI when they
   differ, the same way `release_guard.py` fails a missing version bump.
3. **Names per channel.** Dev and production can be installed side by side and share one File Manager, so
   the name carries the channel: `automation-map-dev-map.css` and `automation-map-map.css`, chosen through
   `isDevBuild()`. A shared name would let one channel's update break the other.
4. **Serving.** The CSS and JS are inlined by the app into the page it already builds, not linked. The page
   is also opened through the cloud URL, which cannot reach the hub's `/local/` path, and inlining keeps the
   page byte for byte what the browser gets today. Read the file once per build hash into a static map, as
   HAI does; a problem page is never cached.
5. **Data and settings.** The four data constants and the URLs stay in the Groovy, in one small inline
   `<script>` that sets `window.AM_BOOT = {...}` before the main script. The main script reads from it
   instead of from `${...}`. That turns the 40 script interpolations into one object and makes the script
   file fully static.
6. **Missing or wrong file.** The map shows a short page: "The map's style file is missing from the hub's
   File Manager, or is from another version. In Hubitat Package Manager, run Repair on Automation Map."
   The settings page shows the same line beside the map link, so nobody has to open the map to see it.
   Optional later step: the app fetches the file from its own channel's GitHub branch, checks the hash, and
   writes it to File Manager itself, once, only when the check fails. That covers manual installs and
   needs no user action, at the cost of one outbound request.

### Installing and updating through HPM

HPM's manifest supports a `files` list beside `apps`. Each entry has `id`, `name` (the File Manager name)
and `location` (the URL to download); `transferType: "binary"` is optional. HPM downloads every file
before installing anything, installs the apps, then writes each file with `uploadHubFile`. On a package
update it writes all files again, and Repair does the same. For the Dev manifest:

```json
"files": [
  {
    "id": "<new uuid>",
    "name": "automation-map-dev-map.css",
    "location": "https://raw.githubusercontent.com/GordonThelander/hubitat-automation-map/dev/ui/map.css"
  }
]
```

Two windows to cover, both handled by the hash check rather than by luck:

- Between HPM saving the new app and writing the new file, the app sees the old file. The hash differs,
  so the map shows the "run Repair" page for those seconds, never a wrong page.
- GitHub's raw cache can serve the app and the file from different commits for a few minutes after a
  merge. Same result: a clear page, and Repair fixes it. Pointing `location` at a release tag instead of a
  branch removes this window, but it means each release needs the tag before the manifest change.

Manual installs ("Install, From a URL") do not get the file. The README's manual install steps must add
"upload `ui/map.css` to File Manager as `automation-map-map.css`", or the self-fetch step above must exist
first.

### Deploying to the Dev hub

`deploy-hub.ps1` gains the step HAI's has: before saving the app, upload each `ui/` file under its Dev
name with `POST /hub/fileManager/upload`, read it back from `/local/<name>`, and stop if its SHA-256 is not
the one in the Groovy being deployed. Uploading first leaves only the short window between upload and save,
in which the old app sees a file of the new build and shows the "run Repair" page.

## 3. Why nothing moves in this change

The style block is the obvious first candidate: 68 KB, one real interpolation, and the Mulish `@font-face`
that holds it can stay inline. It was not moved, because doing it safely needs more than the page:

1. **The production builder would ship the Dev file.** `tools/production-builder/production-manifest.groovy`
   copies every manifest value unchanged except the paths on its allowlist. A `files` entry is not on it,
   so the production manifest would carry the Dev file name and the `dev` branch URL, and the production
   app would refuse that file (or, with a shared name, both channels would fight over it). The builder also
   does not produce a `ui/` file for `main`. Production is Gordon's release, so the builder must learn
   `files` (Dev name to production name, `dev` to `main` in `location`, copy the file) before Dev ships one.
2. **Manual installs** get a map with no styling until the README or the self-fetch step covers them.
3. **Three JS suites read CSS rules from the app** (`tests/flow-zoom-scroll.js`,
   `tests/flow-panel-resize.js`, `tests/map-labels-and-filters.js`). They must read `ui/map.css` too, in
   the same change.

None of these is hard, but each touches the release path, so they belong in one planned change rather than
an unreviewed first step.

## 4. Suggested order

1. Production builder: allowlist and transform `files`, copy `ui/` to the production package, with its own
   tests. No user-visible change.
2. `tools/ui_files.py` (hash write and check, in CI), the `deploy-hub.ps1` upload step, and the README's
   manual install line.
3. Move the style block (section 2, items 1 to 4 and 6), the Dev manifest `files` entry and the three
   suites, in one batch with its version bump.
4. Introduce `AM_BOOT` with the script still inline, so the interpolation change is reviewed on its own.
5. Move the main script to `ui/map.js`. Its comments can then be stripped at publish time if wanted.
6. Optional: the app fetches a missing or wrong file itself.

## 5. Hub cost

Today every map open builds the full page string from class constants already in memory. After the move,
the first map open per build reads one or two File Manager files (about 70 KB, then about 480 KB) and hashes
them once; later opens read the cached copy. Nothing runs per event, per poll or on a schedule, and nothing
grows with the number of devices, rules or apps. Saving the app compiles about 545 KB less source. The hub
checks to run at step 3, by a local session on the Dev hub: open the map and compare the served page with
the previous build's (same bytes); remove the file from File Manager and confirm the "run Repair" page;
then measure free memory and save time before and after the deploy.
