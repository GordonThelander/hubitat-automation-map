# Amnesia

A running record of avoidable mistakes, wasted effort, and lessons that should be carried forward when working on this repository.

The purpose is simple: when a mistake costs time or creates unnecessary friction, record it here so the same pattern is not repeated.

## 2026-08-14

### Overcomplicated a simple `handoff.md` edit

**What happened**

The review of dev build 1.8.5 had already been completed and the full review content was available in the conversation. When asked to add it to `handoff.md`, I unnecessarily re-read large parts of the file, fetched additional repository content, explored GitHub tool capabilities, and started reconstructing the document instead of performing the requested edit directly.

**Why this was wasteful**

- The content to add already existed.
- The target file and branch were already known.
- No further investigation was required to decide what to write.
- Re-fetching and re-analysing repository state added latency without improving the result.
- The user had asked for a simple file edit, not another review.

**Rule going forward**

When the user asks to add already-known content to a known repository file:

1. Treat it as an editing task, not a research task.
2. Reuse the content already available in the conversation.
3. Fetch only what is strictly required by the write API, and only once.
4. Do not re-run analysis, repository searches, comparisons, or source review unless the requested edit itself depends on new information.
5. Complete the write before doing any optional validation.

### Do not substitute snippets when the user expects the complete artefact

**What happened**

When asked to add a URL to a message, I responded with replacement snippets instead of returning the complete revised message.

**Rule going forward**

For this project, when revising a message, post, handoff section, release note, or other prose artefact, return the complete revised artefact unless the user explicitly asks for a snippet, diff, or isolated paragraph.

### Use the repository as source of truth for current implementation

**Lesson**

Automation Map changes quickly. Historical notes, prior messages, and README text can lag the current `dev` source.

**Rule going forward**

- For implementation questions, inspect the current branch/source when freshness matters.
- Do not re-check GitHub when the exact current content has already been fetched during the same task and has not changed.
- Distinguish current code from stale documentation rather than averaging the two.

---

Add future entries when a workflow mistake is identified. Keep each entry concrete: what happened, why it wasted time or reduced quality, and the operational rule that prevents recurrence.
