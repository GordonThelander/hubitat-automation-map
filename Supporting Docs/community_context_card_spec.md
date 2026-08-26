# Community Context Card for Automation Map

**Status:** Proposed specification, not yet authorized for implementation  
**Target:** Earliest release after v2.0.14 stabilization  
**Repositories:** `hubitat-automation-map` and `HPM_Manifest_Crawl`  
**Primary outcome:** Selecting an application in Automation Map shows relevant, evidence-labelled
information from Community Utilities without changing the discovered map.

## 1. User problem

Automation Map explains how an installed app relates to devices, rules, Hub Variables and external
systems. It does not consistently explain what an unfamiliar app is, who maintains it, where its
documentation lives, or whether Community Utilities has useful support and package evidence.

The user should not need to leave the map, open a general catalogue and manually repeat the app
name to obtain that context.

## 2. Proposed capability

Add a **Community information** card to the existing focused-app panel. When an app is selected,
the browser lazily downloads a dedicated Community Utilities context index, matches the selected
app locally, and renders the best supported result.

The card may show:

- identity classification: Hubitat built-in, HPM package, reviewed manual project, or community
  catalogue listing;
- package or project name;
- author or maintainer, when declared;
- match confidence and the evidence used;
- package/evidence health, with wording that does not imply the installed app is faulty;
- documentation, community support and source links;
- network-evidence classification, when the context index has a reviewed result;
- the Community Utilities snapshot date; and
- a link to the full Community Utilities record or Identity Resolver.

The card is informational. Community data must not create, remove, rename or reclassify graph
nodes or relationships.

## 3. User experience

### 3.1 Placement

The card appears inside the app details/focus panel, below Automation Map's own discovered facts.
It must be visually labelled **Community information** and **External community evidence** so it
cannot be mistaken for data read from the hub.

### 3.2 Loading behavior

1. No Community Utilities dataset is fetched during scan or initial map rendering.
2. The first app selection starts one asynchronous fetch.
3. The validated index is cached in browser memory for the lifetime of the map page.
4. Later app selections reuse it without another request.
5. The panel remains usable while the request is pending or if it fails.

### 3.3 Card states

The card has exactly these states:

| State | Display |
| --- | --- |
| Not requested | Small `Look up community information` action or automatic lazy-load indicator. |
| Loading | `Checking Community Utilities...` without blocking the panel. |
| Confident match | One evidence-labelled card and relevant external links. |
| Ambiguous | Two to five candidates, no candidate presented as confirmed. |
| No match | `No community information found for this app.` plus an Identity Resolver link. |
| Unavailable | `Community information is temporarily unavailable.` Automation Map continues normally. |
| Invalid data | Same user treatment as unavailable; diagnostic detail may be logged to the browser console. |

### 3.4 Example

```text
Community information                         External community evidence

LIFX Light Manager                            Exact app identity
HPM package · Gordon Thelander
Evidence health: Healthy
Network evidence: Local LAN integration

[Documentation] [Community support] [Source] [Open full record]
Catalogue snapshot: 26 Aug 2026
```

Health labels describe the public evidence/package record, not whether the user's installed app is
healthy, current or correctly configured.

## 4. Identity supplied by Automation Map

Matching uses definition identity, never the user-editable installed-app label alone.

For each app, Automation Map should retain:

```json
{
  "appId": "3071",
  "instanceLabel": "My renamed instance",
  "definitionName": "Visual Rule Builder 2.0",
  "namespace": "hubitat"
}
```

### 4.1 Required scanner change

`processAppRelationships()` already receives `installedApp.appTypeId`, but `installedApp` does not
expose namespace on current firmware. Fetch `/hub2/userAppTypes` once per scan and build a lookup by
definition ID. Join each installed app's `appTypeId` to that lookup and retain the matched
definition namespace. This is one bulk request, never one request per app. Preserve the existing
`type` value as `definitionName`; do not use the instance label as the primary identity.

The namespace and definition name may be embedded in the rendered graph for browser-local
matching. This feature does not require adding either field to the AI-friendly export. Any export
contract change requires separate approval.

### 4.2 Missing namespace

A missing namespace must not be invented. Exact-name matching may produce a candidate, but it is
confirmed only when the name uniquely identifies one authority record under the rules below.

## 5. Community Utilities data contract

Do not make Automation Map download the current general Identity Resolver index, which is about
1.9 MB uncompressed and includes driver material the card does not need. `HPM_Manifest_Crawl`
should generate a purpose-built, read-only projection:

```text
site/integrations/automation-map/community_context_index.json
```

Published URL:

```text
https://gordonthelander.github.io/HPM_Manifest_Crawl/integrations/automation-map/community_context_index.json
```

The Pages deployment must return this as static JSON. Automation Map must not load remote JavaScript.

### 5.1 Root schema

```json
{
  "schemaVersion": "1.0",
  "dataset": "automation-map-community-context",
  "snapshotGenerated": "2026-08-26T00:00:00Z",
  "records": []
}
```

### 5.2 Record schema

```json
{
  "id": "hpm:stable-package-or-definition-id",
  "authority": "HPM_PACKAGE",
  "kind": "APP",
  "definitionIdentities": [
    {
      "name": "LIFX Light Manager",
      "namespace": "Hubitat Integrations",
      "basis": "sourceIdentity"
    }
  ],
  "displayName": "LIFX Light Manager",
  "packageName": "LIFX Light Manager",
  "author": "Gordon Thelander",
  "summary": "Optional bounded public description.",
  "evidenceChecks": {
    "manifestFetch": "PASS",
    "sourceFetch": "PASS",
    "definitionIdentity": "PASS",
    "documentationLink": "PASS",
    "communityLink": "PASS",
    "httpsTransport": "PASS"
  },
  "qualityFlags": [],
  "lifecycleFlags": [],
  "networkEvidence": {
    "classification": "LAN",
    "reviewed": false
  },
  "links": {
    "record": "https://gordonthelander.github.io/...",
    "documentation": "https://...",
    "community": "https://community.hubitat.com/...",
    "source": "https://..."
  }
}
```

### 5.3 Allowed authorities

- `HUBITAT_BUILT_IN`
- `HPM_PACKAGE`
- `REVIEWED_MANUAL_PROJECT`
- `COMMUNITY_CATALOGUE_LISTING`

Authority labels remain distinct. A community catalogue listing must never be presented as an HPM
package or official Hubitat documentation.

### 5.4 Evidence semantics

The projection carries the six factual package-health check statuses unchanged. It must not invent
an aggregate `HEALTHY` score. The card may say `All published evidence checks passed` only when all
six statuses are `PASS`. Otherwise it lists the relevant non-passing checks using neutral wording.
`MISSING` documentation or community links describe catalogue completeness, not application
failure.

If `definitionIdentity` is `MISMATCH`, the record remains discoverable but must not produce a clean
confirmed identity card. The matcher presents it as `Identity requires review` and links to the
Identity Resolver. The projection exposes this as `qualityFlags: ["IDENTITY_MISMATCH"]` so the
browser does not have to infer the safety decision. Network classification uses only `LAN`,
`CLOUD`, `BOTH` or `INSUFFICIENT`.
`reviewed` is true only when the source dataset contains at least one reviewed evidence entry.

### 5.5 Projection constraints

- Include app definitions only, not driver-only records.
- Use stable IDs from the generating datasets.
- Bound `summary` to 300 characters of plain text.
- Limit links to `https` URLs from the reviewed source records.
- Include no hub inventory, user data, tokens or analytics identifiers.
- Generate atomically and validate before promotion.
- Publish the schema and fixtures in `HPM_Manifest_Crawl`.
- Set an initial uncompressed size gate of 750 KiB. If exceeded, reduce fields rather than silently
  removing the gate.

## 6. Matching rules

Normalization is limited to trimming surrounding whitespace and case-insensitive comparison.
Do not remove punctuation, rewrite words or infer identity from a similar-looking label.

Match precedence:

1. **Confirmed built-in:** exact definition name against a `HUBITAT_BUILT_IN` identity.
2. **Confirmed community identity:** exact definition name and exact namespace against one record.
3. **Confirmed unique name:** namespace is absent and an exact definition name resolves to exactly
   one record across all authorities.
4. **Ambiguous exact name:** exact name resolves to more than one record or authority.
5. **No match:** no exact identity match.

Fuzzy similarity must not create a confirmed card in version 1. It may be used only by the external
Identity Resolver after the user chooses to investigate.

If the exact matching ladder produces no result, one narrowly bounded compatibility retry is
permitted after removing a final whitespace-separated numeric version suffix, optionally prefixed
with `v`, from the definition name. For example, `Zigbee Map 3.0.4` may retry as `Zigbee Map`.
Names without that final numeric-version pattern remain unchanged. The retry must run through the
same deterministic identity ladder and must not introduce general fuzzy or substring matching.

If more than one identity inside the same package matches, show one package card and list the
matching definition identity once.

## 7. Security and privacy

- Matching happens entirely in the browser after downloading the public index.
- Do not send app labels, namespaces, hub IDs, device names, room names, tokens or map contents to
  Community Utilities.
- Do not place app identity or household data in query strings until a separately reviewed URL
  contract explicitly permits it.
- Parse remote content as JSON only. Never execute remote JavaScript or inject remote HTML.
- Render all remote strings with `textContent` or equivalent escaping.
- Accept links only when `new URL(value).protocol === 'https:'`.
- Open external links with `noopener,noreferrer` protections.
- Enforce schema version, dataset name, record-count and response-size limits before use.
- Use a bounded timeout and `AbortController`; a late response must not replace the card for a
  different currently selected app.

The feature reveals only a normal outbound request for the same public index to every user. The
request itself contains no selected-app identity.

## 8. Failure and staleness behavior

Community information is an online enhancement. A failed lookup does not affect:

- scanning;
- application/device enumeration;
- graph construction;
- registry matching;
- flow decoding;
- map rendering;
- AI export; or
- baseline comparison.

Display `snapshotGenerated` on every successful card. If it is older than seven days, add
`Community catalogue may be out of date`; continue showing the evidence.

Do not persist the remote dataset in Hubitat application state. It belongs in browser memory so a
large public catalogue cannot consume hub database/state space.

## 9. Implementation split

### 9.1 `HPM_Manifest_Crawl`

1. Add the projection builder and schema.
2. Derive records only from reviewed existing datasets.
3. Add deterministic matching fixtures for built-in, exact HPM, manual, community-only,
   duplicate-name and missing-namespace cases.
4. Add size and link-scheme validation.
5. Publish the JSON with Pages.
6. Add a stable full-record URL where one exists; otherwise link to the relevant utility page.

### 9.2 `hubitat-automation-map`

1. Fetch `/hub2/userAppTypes` once and join `installedApp.appTypeId` to its definition namespace
   without changing existing app identity or node IDs.
2. Carry `definitionName` and `namespace` to app nodes used by the browser.
3. Add one lazy index loader with in-memory promise/result caching.
4. Implement the exact matching ladder in section 6.
5. Add the card to the focused-app panel and make selection changes race-safe.
6. Keep all existing map and export paths independent from the remote result.

## 10. Tests and release gates

### 10.1 Unit and fixture tests

- exact built-in name;
- exact community name plus namespace;
- case and surrounding-whitespace normalization;
- missing namespace with one unique match;
- missing namespace with multiple matches;
- one package containing multiple matching definitions;
- malformed schema and unsupported schema version;
- oversized response and excessive record count;
- unsafe link schemes;
- remote strings containing HTML/script payloads;
- timeout, network failure and invalid JSON;
- rapid selection of app A then app B before the fetch completes;
- stale snapshot warning; and
- no mutation of graph nodes, edges or export payload after a match.

### 10.2 Live gates

On Gordon's dev hub, verify at least:

- one documented Hubitat built-in;
- one exact HPM-managed community app;
- one reviewed manual project;
- one ambiguous or namespace-missing app;
- one app with no result;
- Community Utilities unavailable while the map remains fully usable; and
- the browser network request contains no selected app or household data.

### 10.3 Performance gates

- Initial map display performs no context-index request.
- First-card loading does not freeze map interaction.
- One page session downloads the index at most once.
- Matching one app completes within 100 ms on a representative tablet after the index is parsed.

## 11. Observability

Browser diagnostics may record only:

- index requested/succeeded/failed;
- schema version and snapshot date;
- response byte count and record count; and
- match outcome category (`confirmed`, `ambiguous`, `none`).

Do not log the selected app label or namespace as part of remote-lookup diagnostics.

## 12. Out of scope for version 1

- device or driver cards;
- automatic update detection against the installed source version;
- automatic package installation, repair or replacement;
- changing Automation Map registry matches from Community Context results;
- sending the map or selected identity to an online API;
- AI-generated recommendations;
- automatic warnings based only on package age;
- embedding the Community Utilities website in an iframe; and
- including Community Context results in the AI-friendly export.

## 13. Acceptance criteria

The feature is complete when selecting an app can show a correctly authority-labelled Community
Context card from the purpose-built online index, ambiguous results remain explicitly ambiguous,
no private hub identity is transmitted, unsafe or unavailable remote data fails closed, and every
existing Automation Map function produces the same result whether the Community Utilities request
succeeds or fails.
