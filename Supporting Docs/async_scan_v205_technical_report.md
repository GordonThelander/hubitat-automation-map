# Technical Report: Automation Map v2.0.5 Async Scan Architecture and Validation

**Date:** 22 August 2026  
**Status:** Development-hub candidate; normal path validated, production promotion not yet approved

## Executive summary

Automation Map discovery was changed from a largely serial sequence of per-device and per-app HTTP calls into a two-phase, bounded asynchronous pipeline. The update also removes redundant work: the hub's bulk device list supplies labels, rooms, driver names, and driver identifiers in one request, and device capabilities are fetched once per distinct driver type instead of once per device.

The first asynchronous implementation reached approximately 17–19 seconds but was reverted after its durable result contained more app entries than its own counters could explain. Review found accounting and publication defects capable of causing incomplete or stale results: dispatch reservations could leak when `asynchttpGet` threw synchronously, retries could be miscounted, callbacks and recovery could retire the same request, completion checks were too weak, and final publication could occur in an async callback execution.

The revised v2.0.5 design separates concurrent collection from durable publication. Async callbacks write only to scan-specific concurrent accumulators. Claims, attempt tokens, atomic counters, bounded retries, a missing-callback reaper, strict invariants, scheduled finalization, and a fail-closed watchdog control the transition between phases.

Four controlled development-hub scans completed in 21.1–25.5 seconds versus a 134-second same-day serial baseline and produced equivalent graph data after one independently verified hub-inventory change was excluded. This supports continued development-hub soak testing. It does not yet measure resource pressure or dynamically exercise every recovery path in the full application, so it is not by itself approval for production deployment.

## 1. Previous scan method

The historical scan discovered devices and walked hub endpoints serially. Earlier optimization work retained synchronous execution but reduced request volume through `/hub2/devicesList` and per-driver capability reuse. That safe subset reduced one measured scan from roughly 124 seconds to about 101 seconds.

Measurements occurred at different times and hub inventories, so they must not be treated as a single controlled benchmark series. The controlled v2.0.5 development test used a fresh scan of the preserved pre-install source as its baseline and recorded 134 seconds. That 134-second run is the proper comparator for the four v2.0.5 runs reported here.

The main cost drivers were:

1. repeated device detail requests for fields already available in a bulk listing;
2. repeated capability retrieval for devices sharing the same driver;
3. serial app status requests to `/installedapp/statusJson/{id}`;
4. waiting for each network operation before beginning the next.

## 2. Updated discovery flow

### 2.1 Compatibility and bulk enumeration

`startScan()` first verifies that the hub returns usable JSON from its internal installed-app endpoint. It then calls `/hub2/devicesList` once.

The bulk response seeds every device ID and label, room name, driver name, and `deviceTypeId`. The driver identifier groups devices that can share one capability fetch. On the tested hub, 194 devices collapsed to 34 representative driver groups while preserving all 194 device nodes.

### 2.2 Device phase

The device phase places representative device IDs into a `ConcurrentLinkedQueue` and permits at most eight asynchronous requests at once. Each request fetches `/device/fullJson/{representativeId}`. Capabilities returned for the representative are applied to every device in its driver group.

The scan-local accumulator holds pending work, current claims, atomic in-flight and processed counts, a progress timestamp, capabilities by device, unreadable IDs, type-group membership, and separate guards for scheduling finalization and owning publication.

The callback does not read or write Hubitat durable `state`. When all exact invariants hold, it schedules a separate finalizer rather than publishing inline.

### 2.3 App phase

Installed app IDs are enumerated from the hub's bulk app listing, including apps that reference no device. The app phase fetches `/installedapp/statusJson/{id}` concurrently, again with at most eight requests in flight.

Each callback passes the response JSON into the existing relationship parser. Parsed app information, discovered labels, rule counters, unsupported-engine names, and error counts remain in the scan-local accumulator until the phase is complete.

### 2.4 Registry and graph construction

After the scheduled app finalizer publishes the complete app-phase result, registry retrieval and graph construction run as separate scheduled executions. `finishScan()` builds and publishes the graph, stamps its schema, derives final relationship and inert-node counters, and marks the scan complete.

## 3. Concurrency correctness model

### 3.1 Reservation before dispatch

Before calling `asynchttpGet`, a dispatcher atomically reserves an in-flight slot, removes one pending item, and installs a claim containing the item ID, attempt token, dispatch timestamp, and attempt count.

The request is issued inside `try/catch`. A synchronous exception removes the exact claim, releases the slot, and either requeues the item or records a terminal failure. This matters because Hubitat was shown to throw synchronously while coercing malformed async request parameters. Without rollback, the queue can empty while `inFlight` remains nonzero forever.

### 3.2 Bounded retries

Attempt counts travel with requeued items. They are not recovered from the active claim because the claim is deliberately deleted whenever an attempt retires. The cap is two attempts.

The refill loop is iterative, so repeated synchronous rejection cannot grow the call stack.

### 3.3 Single owner for each attempt

A callback, synchronous rollback, and claim reaper may all attempt to retire work. Only the execution that successfully performs the following operation owns that attempt and may decrement `inFlight`, requeue it, or mark it processed:

```groovy
claims.remove(itemId, exactClaimObject)
```

An attempt-token check rejects callbacks from older attempts. The reaper snapshots the exact claim object, not only the item ID, so it cannot accidentally retire a newer retry.

### 3.4 Missing-callback recovery

The claim reaper runs every 10 seconds and examines claims at least 25 seconds old. The deadline exceeds the longest application request timeout of 20 seconds plus scheduling margin. A reaped attempt is retried once and then recorded unreadable if the second claim also expires.

The worst recovery envelope is approximately:

```text
2 attempts × (25s deadline + up to 10s polling delay) = 70s
```

Both phase watchdogs are 130 seconds, leaving a further 60-second margin. Hubitat's loader rejected computed cross-field `@Field static final` constants, so the watchdog values are literals with the calculation documented beside them.

### 3.5 Exact completion and separate finalization

The device phase requires:

```text
pending == 0
inFlight == 0
claims == 0
processed == total
capability results + failed representatives == total
```

The app phase additionally requires:

```text
appInfo.size == total
decoded + unreadable == total
```

The terminal callback or reaper only schedules finalization. The scheduled handler rechecks every invariant. A second CAS guard then grants exactly one execution permission to publish.

This separation is the core persistence fix: no terminal async callback writes scan results into durable state and then risks a later stale callback execution committing an older state snapshot.

### 3.6 Fail-closed behavior

If a watchdog reaches an incomplete scan, it acquires the finalization guard, removes the volatile accumulator, records a scan error, and does not publish the partial phase as complete.

The current implementation clears the previous graph at scan start to limit peak Hubitat state usage. Therefore “fail closed” means that an incomplete replacement is not published; it does not mean the previous graph remains available. Double-buffering was deliberately deferred pending memory measurement.

This choice is supported by the same hub's earlier history. On 13 August 2026, with 193 devices and 74 apps, retaining the previous graph while the replacement accumulated and storing flowcharts in both `appInfo` and `graph.flows` pushed state to approximately 244KB. The scan stopped two apps from completion without a logged exception or successor job. Dropping the previous graph at scan start and removing the duplicate flow storage reduced state to approximately 183KB and allowed an 84-second scan to complete. v2.0.5 preserves both fixes.

## 4. Isolated harness

The curated harness is stored at `Bucket/AsyncDispatchTest.groovy`, with its runbook and evidence under `Bucket/async_dispatch_harness/`.

Its authoritative 35-item run validated 20 real loopback successes, deterministic synchronous dispatch exceptions and rollback, genuine async timeout callbacks, bounded retry, deterministic missing-callback recovery through two reap cycles, exact accounting, and exactly-once finalization. It completed in 36.401 seconds without a watchdog or invariant violation.

The narrow callback-versus-reaper race did not occur dynamically because successful and timeout callbacks completed well before the reap deadline, while missing items had no callback by construction. That interleaving is protected by atomic ownership and remains inspection-validated rather than timing-forced.

## 5. Production-shaped development test

The controlled test targeted only Automation Map (Dev), code ID 1189 and installed instance 3050. Production code ID 1188 and instance 3047 were not modified. The pre-test source was preserved in `Bucket/dev_hub_backups/`.

| Build/run | Duration | Devices | Apps decoded | Errors |
|---|---:|---:|---:|---:|
| Preserved serial baseline | 134s | 194 | 106 | 0 |
| v2.0.5 run 1 | 23.5s | 194 | 105 | 0 |
| v2.0.5 run 2 | 21.1s | 194 | 105 | 0 |
| v2.0.5 run 3 | 22.3s | 194 | 105 | 0 |
| v2.0.5 run 4 | 25.5s | 194 | 105 | 0 |

The app-count difference was traced to app 3052, “Internal Endpoint Tester,” disappearing from a fresh direct `/hub2/appsList` query between baseline and v2.0.5 measurements. It was not dropped by async discovery.

Normalized graph comparison found:

- identical device node IDs for all 194 devices;
- only app node `a3052` present in the baseline;
- exactly one baseline-only edge, explained by that app;
- identical hub-variable, external, and inert counts of 3, 15, and 21.

After run 1, status was checked again 140 seconds after completion. Counts, heartbeat, and graph version were unchanged, demonstrating that the obsolete 130-second watchdog did not mutate a completed scan.

No dispatch exception, claim reap, ownership loss, watchdog, or invariant warning occurred during the four clean application runs. Consequently, the application normal path is dynamically validated; full-application recovery paths remain supported by the isolated harness and code inspection.

## 6. Hubitat-specific implementation findings

Live installation found two restrictions not caught by local compilation:

1. Hubitat rejected `AtomicLong` even though `AtomicInteger` is allowed. The progress marker now uses a plain `Long` value inside `ConcurrentHashMap`; it is diagnostic overwrite-only data and needs no compare-and-set operation.
2. Hubitat rejected cross-field `@Field static final` expressions and an `as int +` expression shape. Watchdog constants were replaced with documented literals.

These findings reinforce that Hubitat loader validation is a required gate even when local Groovy parsing succeeds.

## 7. Evidence boundaries and remaining risks

The following remain unproven or unmeasured:

- hub CPU and free-memory behavior before, during, and after an async scan;
- application recovery from a real lost callback;
- application recovery from a synchronous dispatch exception caused by environmental failure;
- representative-device capability failure and whole-driver-group unreadable marking;
- forced callback/reaper contention;
- reboot or code reload during a scan;
- peak and transient state usage during the current 105-app async scan, which was not instrumented;
- retention of a previous known-good graph, which is not implemented.

The historical 74-app failure occurred on this same hub, not a larger separate hub. The successful v2.0.5 test therefore exercised a larger app inventory—approximately 105 apps with 194 devices—while retaining the earlier memory fixes. This is positive scale evidence, but it does not replace direct CPU and memory measurements of the new concurrent pipeline.

## 8. Recommended release gates

Before production promotion:

1. Keep v2.0.5 on the development instance for a bounded soak of at least 24 hours and several scans.
2. Compare normalized graph nodes and edges after each run, allowing only independently verified inventory changes.
3. Check status beyond the 130-second watchdog horizon.
4. Record available CPU/free-memory diagnostics before, during, immediately after, and several minutes after a scan.
5. Confirm there are no invariant, ownership-loss, stale-generation, claim-reap, or watchdog warnings under ordinary operation.
6. Test reboot/code-reload behavior only with explicit authorization and a rollback plan.
7. Keep `.gitignore` and unrelated Santa UI cleanup out of the async implementation commit.

## Conclusion

The speed improvement came from both fewer requests and controlled concurrency. The correctness improvement came from treating each phase like a transaction: collect into a volatile scan generation, account for every item exactly once, recheck exact invariants in a separate execution, and only then replace durable phase data.

The development evidence is compelling for the normal path and shows a roughly five-to-sixfold improvement against the same-day baseline. The design is materially safer than the reverted async attempt, but production promotion should wait for soak and resource evidence rather than infer safety from speed and four clean runs alone.
