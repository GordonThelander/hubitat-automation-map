Automation Map (Dev) UI assessment
Date: 2026-09-07, Australia/Perth

Scope
- Opened the relationship graph from the live Hubitat app and exercised the main graph, Quick Search, focus behavior, relationship filters, Full legend, Insights, External systems, Pivot tables, Device icons, AI-friendly export, and Hubitat release activity.
- Reviewed at the normal 1534 x 847 desktop viewport and at 1024 x 768.
- Reviewed the current GitHub dev branch at commit 9c1f9fc71ce4667edb53c1ba3eabdc0df4bf8c6e, timestamped 2026-09-07 17:53:51 +08:00. The live page identifies itself as Dev 2.2.4. Exact deployed-source hash was not verified.
- Repository validation passed: "Validation clean: Automation Map (Dev) v2.2.4 on branch dev".
- No app-origin browser warnings or errors appeared during the exercised paths. Two console errors came from an AdGuard userscript inside the sandboxed release-activity iframe, not from Automation Map, and are excluded from the findings.

Overall verdict
The major workflows operated, panels loaded, the AI export completed, and no fatal client-side error was found. The strongest issues are misleading self-app status text, hidden wait states during filtering, incomplete search coverage, ambiguous duplicate search results, and accessibility gaps. Several lower-severity polish problems remain.

Findings

1. HIGH - Automation Map describes its own active installation as having no schedule

Observation:
- The settings page reports that the app runs an automatic daily scan.
- Focusing the Automation Map app in the graph displays: "Nothing at all: no children, no schedule, no subscriptions. Either it is not configured yet, or it is left over from something that has been removed."
- That is internally contradictory and could lead an administrator to think an active, configured app is abandoned.

Source evidence:
- apps/automation_map.groovy:5311 assigns the special self-family label "reads the whole hub, drives nothing".
- apps/automation_map.groovy:8987 emits the generic "no schedule" fallback whenever the collected facts, parent, children, and schedJobs are empty.

Recommendation:
- Give Automation Map family nodes a truthful self-description and do not apply the abandoned-app fallback to them.
- Alternatively, ensure the app's own scheduled job is represented in schedJobs before the fallback is evaluated.

2. MEDIUM - Narrowing the relationship type can blank the entire graph with no progress indication

Observation:
- Selecting "External systems only" immediately produced an empty canvas.
- The filtered nodes appeared roughly 1.5 seconds later.
- During that interval there was no spinner, status text, disabled state, or other indication that layout was still running. The screen looked like a valid empty result.

Source evidence:
- apps/automation_map.groovy:8227-8239 deliberately sets the network opacity to zero for a narrowed physics-based layout.
- apps/automation_map.groovy:8249-8262 waits for stabilization and uses a 1500 ms fallback before revealing it.

Recommendation:
- Keep the old graph visible under a short "Laying out filtered view" indicator, or show an explicit busy state while opacity is zero.
- Reveal immediately when the stabilization event fires and retain the fallback only as a safety net.

3. MEDIUM - Quick Search does not search every node type

Observation:
- The popup input says "search everything...", but external-system nodes cannot appear in its results.
- The live map reports 386 nodes while Quick Search reports 364 searchable items. Not all of that difference should be inferred to be external systems, but source confirms that external systems are excluded by construction.
- External systems are first-class graph nodes elsewhere, including the legend, relationship filter, panel, and Pivot tables.

Source evidence:
- apps/automation_map.groovy:12089-12113 allow only app, device, hubVariable, and localVariable groups.

Recommendation:
- Add externalSystem to the grouped search and dispatch it through a suitable focus path.
- If omission is intentional, replace "search everything..." with wording that matches the actual scope.

4. MEDIUM - Duplicate labels create indistinguishable search choices

Observation:
- Quick Search displayed more than one pair of rows with identical visible labels and type decoration.
- The underlying IDs differ, but no ID, room, parent, or other discriminator is shown. A user cannot reliably know which duplicate object will be selected.

Source evidence:
- apps/automation_map.groovy:9700-9752 filters and renders the visible title/optionText while retaining ID only as hidden selection state.
- apps/automation_map.groovy:12089-12107 prefixes the group but adds no guaranteed unique discriminator.

Recommendation:
- Add contextual secondary text only when labels collide, for example room for devices and parent plus app ID for apps.
- Preserve readable labels in the normal case and expose the stable ID in the tooltip or detail line.

5. MEDIUM - Panels are not exposed as accessible dialogs and their close controls are named only "x"

Observation:
- Insights, External systems, Pivot tables, Device icons, flow/details, and release activity are visually modal-like panels over the graph.
- Accessibility inspection exposes their close buttons as "x", not "Close Insights" or an equivalent useful name.
- The panel containers have no dialog role or accessible label relationship, and focus remains on the launching control rather than moving into the opened panel.
- The graph canvas itself has no keyboard-accessible node structure. Quick Search partly compensates for navigation, but not for relationship exploration.

Source evidence:
- apps/automation_map.groovy:7400-7404 uses title="Close" and an x glyph, without aria-label.
- No role="dialog", aria-labelledby, or panel focus-management implementation was found in the current source.

Recommendation:
- Add unique aria-label values to close buttons, role="dialog" plus aria-labelledby to panels, move focus to the panel heading or first control on open, and restore focus on close.
- Provide a keyboard-readable relationship list or equivalent alternative for the canvas graph.

6. MEDIUM - The supported 1024 x 768 layout leaves too little useful graph space

Observation:
- At 1024 x 768 the fixed 375 px legend and roughly 300 px control rail leave only a narrow central strip for a 386-node map.
- The graph remains technically operable, but nodes and edges become a compressed overview with unreadable labels, and the inert-node shelf passes behind overlay regions.
- The app does not switch to its small-screen message until 820 px, so 1024 px is treated as a supported graph layout.

Source evidence:
- apps/automation_map.groovy:6777-6804 fixes the status/legend at 375 px and controls at 300 px.
- apps/automation_map.groovy:7010-7020 hides the graph only below 820 px.

Recommendation:
- Collapse the legend by default below a desktop breakpoint such as 1100 or 1200 px, or offer a compact control rail.
- Consider using overlay toggles so only one side panel consumes width at a time.

7. LOW - Full legend contains a visibly incomplete sentence

Observation:
- The Full legend row reads: "Private Boolean - rule sets another rule's".
- It omits the object of the sentence and is inconsistent with the compact legend, which correctly says "Private Boolean - rule sets the Private Boolean of another rule".

Source evidence:
- apps/automation_map.groovy:7350 contains the truncated text exactly as rendered.

Recommendation:
- Change it to "Private Boolean - rule sets another rule's Private Boolean" or reuse the compact legend wording from a shared definition.

8. LOW - Panel action styling is inconsistent and some controls are too small

Observation:
- The primary graph rail uses large rounded controls, but External systems and Device icons render Save, backup/restore, add/override, and related actions as small browser-default buttons.
- Pivot-table presets and Export CSV use another compact visual treatment.
- At the normal desktop viewport, table text and row actions in External systems are notably small compared with the rest of the interface.

Source evidence:
- apps/automation_map.groovy:6828 styles buttons only under #controls.
- Panel-specific table/action CSS does not establish a shared button hierarchy for these actions.

Recommendation:
- Reuse a shared small, secondary, and primary button system inside panels, with a minimum practical hit target and consistent focus styling.

9. LOW - The initial whole-map view is an overview, not a readable map

Observation:
- On a 1534 x 847 desktop viewport the full 386-node graph is fitted into a relatively small central cluster because the side overlays and outliers consume the available bounding area.
- Individual labels are not readable until the user searches, focuses, or zooms. The onboarding copy explains that the map is busy, but the initial view does not strongly direct the eye to the search-first workflow after that hint has been dismissed.

Recommendation:
- Make Quick Search the primary emphasized action, especially after the first-run hint is dismissed.
- Consider a short persistent cue such as "Search or select a node to inspect relationships" near the control rail.

What worked well
- Relationship-type filtering eventually produced the correct narrowed data rather than leaving unrelated nodes on screen.
- Quick Search filtering and selection worked, and focused details opened successfully.
- Insights, External systems, Pivot tables, Device icons, and release activity loaded successfully.
- The release activity embed rendered correctly after its network load.
- The AI-friendly export completed and the control returned from "Exporting..." to its normal state.
- Panel sizing kept large panels clear of the right control rail at the normal desktop viewport.

Suggested order of work
1. Correct the self-app status contradiction.
2. Add an explicit busy state for filtered-layout stabilization.
3. Fix Quick Search coverage and collision disambiguation.
4. Add panel semantics, focus management, and meaningful close labels.
5. Correct the legend sentence and consolidate panel button styling.
6. Improve the 1024 px layout and initial search guidance.
