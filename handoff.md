# Automation Map - Development Handoff

Prior contents (TheBearMay reverse-engineering notes, the dev branch review at
`e72a738`, and the first JimB reassessment) were cleared 2026-08-15 at Gordon's
request. They remain in git history:

    git show 4f4dd33:handoff.md

---

## Open investigation: JimB's scan-start failure

### The symptom

Pressing **Scan relationships now** on the app settings page produces:

    Could not start the scan: Unexpected token '<', "<!DOCTYPE "... is not valid JSON

Reported by JimB (community thread 165524). Reproduced on his hub across app
versions 1.8.5, 1.8.6 and 1.8.7, **byte-for-byte identical each time**. Not
reproducible on Gordon's hub at any version.

### What that error actually is

It is the browser's generic response to being handed HTML where it expected
JSON. `JSON.parse` hits the `<` of `<!DOCTYPE html>` and throws. It says
nothing whatsoever about *who* sent the HTML, which is the entire question.

At least four distinct failures produce this identical string:

| Actual cause | What answered |
|---|---|
| OAuth token missing/rejected | Hubitat auth error page |
| Hub Login Security intercepting | Hubitat login page |
| Page served from a cloud/remote origin | Cloud portal page |
| Exception inside the endpoint | Hubitat's own exception page |

One symptom, four causes, no discriminating evidence. That is the core problem
this investigation kept running into.

### The reasoning chain

**1. The unchanged error string is the strongest signal available.**

1.8.6 and 1.8.7 were both server-side changes to the error path. 1.8.7 wrapped
`startScan()` in a try/catch that returns `{ok:false, error:...}`. If the
request were reaching `scanMapping()` at all, a genuine failure would now
produce *a different message* naming the real exception. It did not change by
one character.

**2. That leaves exactly two possibilities**, and separating them is the whole
task:

- **(a)** The request never reaches `scanMapping()` - rejected by auth or
  routing before dispatch, so no handler of mine can ever run.
- **(b)** The request reaches it, but the handler itself fails while trying to
  report the failure.

**3. Possibility (b) turned out to be real, and was mine.**

The 1.8.7 catch passed a Groovy **Map** to `render(data:)`. Every other
`render()` call in this file passes a serialised **String** (`externalsJson()`,
`scanStatusJson()`, `buildMapHtml()`, and a literal JSON string in
`externalsSaveMapping`). If Hubitat does not coerce a Map there, the handler
throws inside its own catch, Hubitat renders its HTML error page, and the
caller sees the exact parser error the handler existed to prevent.

**So 1.8.7 may never have had a fair test.** Its diagnosis could have been
sound while its implementation silently defeated it. Caught by external review
(OpenAI), verified here against every `render()` call site in the file.

**4. Hence 1.8.8: stop theorising, instrument.**

Ranking hypotheses without discriminating evidence is not progress. 1.8.8 does
two things:

- Fixes the `render()` Map/String bug, so the handler can no longer fail while
  reporting a failure.
- Makes the client read the response as **text** and parse it itself, reporting
  **HTTP status, final URL after redirects, content-type, and the first 200
  characters of the body**. Those four fields discriminate all four causes in
  the table above in a single observation.

**5. The hub log is a better test than anything in the app.**

`scanMapping()`'s catch calls `log.warn`. So:

- Warning appears in Logs when Scan is pressed -> the request **reached** the
  app, and the log names the real exception. Possibility (b).
- Log stays completely silent while the on-screen error still appears -> the
  request **never reached** the app. Possibility (a), and no app-side fix can
  ever address it.

This is binary, version-independent, and requires nothing from the diagnostic
build. It should be the first thing asked for.

### Verified facts

- **`getLocalURL()` returns a RELATIVE path**, not an absolute URL. Verified
  empirically against the live rendered page:
  `EXT_URL = '/apps/api/2976/externals?access_token=...'`. The `URL_PATTERN`
  regex strips scheme and host and keeps only group 1 (the path). External
  review claimed the opposite; the claim is wrong.
  - Consequence: every such fetch resolves against **whatever origin the page
    is currently served from**. Correct when the page comes from the hub.
    Broken if the page is served from a cloud/remote origin, where the path
    prefix differs.
- **JimB's scanning itself works.** His map built: 302 nodes, 1022
  relationships, 111 apps. Only *starting* a scan from the button fails.
- **Firmware differs**: JimB on C-8 / 2.5.1.152, Gordon on 2.5.1.147.
- **Gordon's hub has Hub Login Security OFF.** JimB's is unknown and is a
  prime suspect, because `amStartScan()` uses `credentials: 'omit'`
  deliberately (sending the session cookie makes Hubitat treat the call as part
  of the UI transaction, and `runIn()` then schedules nothing). With the cookie
  omitted, a hub with Login Security on may answer with a login page.

### Corrections to earlier claims in this investigation

Recorded because each was stated with more confidence than the evidence
supported, and the pattern is worth not repeating:

- **"1.8.7 makes this failure structurally impossible."** False. A request
  rejected before dispatch never reaches the handler, so the guarantee only
  ever covered exceptions *inside* `scanMapping()`.
- **"Confirmed the cause"** (relative URL + cloud origin). Overstated. The
  relative URL is verified; that it is JimB's actual cause is a hypothesis. He
  has never stated the URL in his address bar.
- **An early theory that something between the tablet and hub was intercepting**
  (carrier block page). Inconsistent with the fact that he loads the hub's own
  settings page fine - if he can reach the hub for that, he reaches it for the
  fetch.

### Open hypotheses

Deliberately unranked by probability - there is one user, one error string, and
no discriminating observation yet. Assigning percentages to this would be false
precision.

1. Auth rejected before dispatch (OAuth token, or Hub Login Security
   interacting with `credentials: 'omit'`).
2. A genuine `startScan()` exception that 1.8.7's defective handler failed to
   report.
3. Origin mismatch - page served from cloud/remote, relative fetch resolves to
   the wrong host.
4. Platform behaviour difference on 2.5.1.152.

### What settles it

| Test | Distinguishes |
|---|---|
| Hub log silent vs. warning during Scan | Whether the request reaches the app at all |
| 1.8.8 diagnostic output | Names which of the four responders answered |
| Does the map link open on that tablet? | Isolates a token problem (same auth path) |
| Does Production v1.2 scan on that tablet? | Isolates the Dev install from the device |
| Is Hub Login Security on? | Directly tests the leading hypothesis |
| Full URL in the address bar | Directly tests the origin hypothesis |

### Known remaining gap

**Two other client-side fetches still call `r.json()` unguarded** and will fail
the same opaque way if they ever receive HTML:

- the map page's scan-status poll
- the External systems panel load (`EXT_URL`)

If JimB's cause turns out to be auth or origin, both of these are failing for
him too, silently. Worth applying the same text-first, report-what-arrived
treatment once the root cause is known.

### Design principle worth keeping

A response that might not be JSON must never reach a JSON parser without
something capturing what it actually was. The parser's error message is the
least informative artefact in the entire failure path, and it is the one the
user ends up reading.

---

## 2026-08-15 review corrections before asking JimB to test 1.8.8

These points supersede two statements above and tighten the forum diagnosis.

### The successful first scan is stronger evidence than first described

Calling it an "auto-scan" is imprecise. On a normal new installation the first
successful scan is started directly by `installed() -> startScan()`. It does
**not** go through the browser `/scan` endpoint. The page auto-scan is only a
fallback for a completed installation that still has no graph.

That means JimB's completed 302-node / 1022-relationship / 111-app map is good
evidence that the **scan engine itself works on his hub**. The fault is much more
narrowly associated with starting a later scan through the browser-facing
`/scan` OAuth mapping.

Forum wording should therefore say "initial installation scan" rather than
"auto-scan".

### Chrome + Edge does not literally eliminate the browser layer

Both are Chromium-based on Android and share much of the same OS networking
stack. The repeated result makes a browser-specific quirk unlikely, but the
accurate statement is:

> Unlikely to be browser-specific - same result in Chrome and Edge.

### The current log test is not yet truly binary

The existing 1.8.8 `scanMapping()` only logs when `startScan()` throws. The
success-side `scanStatusJson()` and `render()` occur after that catch. If one of
those throws, the endpoint has definitely been reached but Hubitat could still
return an HTML exception page **without the warning appearing**.

So the earlier statement "silent log means the request never reached the app"
is too strong with the code as currently committed.

Make the test actually binary by logging immediately on entry, for example:

    log.warn "${app.label}: 1.8.8 diagnostic - /scan endpoint reached"

and keep the success render inside the same try/catch. Then:

- entry log present -> Hubitat dispatched `/scan` into Automation Map
- entry log absent -> request was rejected/routed elsewhere before app dispatch

This is a stronger discriminator than waiting for the catch warning.

### Important security defect in the 1.8.8 diagnostic

`getLocalURL()` constructs the request with:

    ?access_token=${state.accessToken}

The 1.8.8 client diagnostic currently includes `r.url` verbatim in the message,
and the forum draft asks JimB to post the **full error text**. That can expose
his live Automation Map OAuth token publicly on the Hubitat forum.

**Do not ask JimB to test the current diagnostic until this is fixed.**

The response URL should be reduced to origin + pathname, or the query string
should at minimum have `access_token` redacted. The body preview should also be
sanitised before display in case a Hubitat error/login page echoes the requested
URL.

Safe client pattern:

```javascript
var where = '(no response URL)';
try {
  var u = new URL(r.url);
  where = u.origin + u.pathname;
} catch (ignore) {}
```

The diagnostic still reveals whether the responder was the local hub, a hub
hostname, or a cloud/remote host, without leaking credentials.

### The map link is useful, but it is not the same authentication path

The earlier table says that a working map link isolates the token because it is
the "same auth path". That is not strictly true.

`amStartScan()` deliberately uses `credentials: 'omit'`, so the `/scan` request
must authenticate with the access token alone. Clicking **View Automation Map**
is normal browser navigation and can carry the Hubitat login session cookie as
well as the token in the URL.

Therefore:

- if **View Automation Map also fails**, OAuth/token handling becomes much more
  likely;
- if the map opens but Scan fails, that is useful evidence but does **not**
  completely clear OAuth/HLS interaction, because Scan intentionally removes
  the session cookie.

Forum question 4 should reflect that distinction.

### Production v1.2 remains one of the best controls

The production build uses the same browser `/scan` mechanism with
`credentials: 'omit'`. If Production can manually rescan successfully from the
same Pixel, on the same Wi-Fi, against the same C-8 while Dev 1.8.8 cannot, that
removes most of the tablet/network/HLS/firmware explanations and focuses the
investigation on the Dev installation, its OAuth token/state, or a Dev-specific
code path.

### Revised decisive-test table

| Test | What it actually tells us |
|---|---|
| Explicit `/scan endpoint reached` entry log | Whether Hubitat dispatched the request into the app |
| Sanitised 1.8.8 status/type/final-host/body-prefix diagnostic | Which layer actually answered |
| View Automation Map on the same tablet | Corroborates token/auth problems, but does not fully clear them if it works |
| Production v1.2 manual rescan on the same tablet | Separates Dev-instance behaviour from tablet/network/platform behaviour |
| Hub Login Security on/off | Tests the interaction with `credentials: 'omit'` directly |
| Full address-bar URL/host | Tests local-hub versus cloud/remote-origin resolution |

### Immediate action before JimB tests again

1. Redact the access token from the 1.8.8 displayed diagnostic.
2. Add an unconditional entry log at the first line of `scanMapping()`.
3. Put the successful `scanStatusJson()` / `render()` path inside the same
   try/catch.
4. Update the forum wording from "auto-scan" to "initial installation scan".
5. Soften "not your browser" to "unlikely to be browser-specific".
6. Correct the map-link question so it does not claim identical auth context.

After those changes, the next report from JimB should finally be discriminating
rather than another variation of the same parser symptom.
