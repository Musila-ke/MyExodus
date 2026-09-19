# MyExodus Monitoring — Partner Pillar §14 Realization

*Plan section 14 (partner-pillar-implementation-plan-v2.md lines 568-580) lists eleven monitoring
requirements; §17 line 639 adds the budget alert + `maxInstances` acceptance item; §18 line 649
requires the dashboards to be live with one day of staging data. This document records, per
requirement, exactly what exists in code, what exists as server log rails, and what is console
configuration that still needs doing — with its blocker named.*

Status as of 2026-09-19: client + server instrumentation COMPLETE and test-verified
(664/0/0 JVM, 30/30 functions). GCP console configuration NOT done (task #34) — blocked on the
OQ-5 monthly figure and console access.

---

## 1. Client instrumentation (code, done)

All client events ride the existing `telemetry/CrashReporting` rail (one `NonFatalEvent`
class, so every §14 event groups under one crash-console issue), metadata-only per
Decision 12, opt-out honored, best-effort (`runCatching`). Reporting happens **once per
periodic sync pass** (`PartnerSyncWorker`, 15-min cadence) — the dashboard's manual
"Check now" does *not* report, so a user's taps cannot inflate counts.

| §14 metric | Event | Fired when | Where |
|---|---|---|---|
| `CHAIN_INCOMPLETE` count | `chain_incomplete` | a chain reaches the neutral terminal (gap range unrecoverable) | `ChainStateStore.markChainIncomplete` via `onSecuritySignal` seam |
| `TAMPER_SUSPECTED` count | `chain_tamper_suspected` | repair-active verification failure (either escalation path: `flagFork` hash mismatch, `flagTamperIfRepairing` signature failure); emitted once per event, never for ordinary noise | same seam |
| Outbox age distribution | `partner_outbox_pending age=<bucket>` | end of a periodic pass **only if** a row is still PENDING; buckets `under_15m` / `under_1h` / `under_24h` / `over_24h` | `PartnerSyncTelemetry.afterPass` + `OutboxDao.oldestPendingCreatedAt()` |
| Notification-permission-denied rate among active pairs | `partner_notifications_denied` | enrolled pass where `NotificationAccess.canPost` is false | same class |
| T1 latency (§11) | `partner_t1_ms bucket=<bucket>` | per successfully written outbox message: attempt → Firestore confirmed; buckets `under_1s` / `under_3s` / `over_3s` (the §11 targets as bucket edges) | `MailboxOutboxDrainer.onSendLatency` seam, wired in `AccountabilityModule` |
| Data-wipe-detection trigger count (Step 17) | `local_wipe_detected` | pre-existing (Step 17) | `MyExodusApp.onCreate` |

Denominator note for the denied rate: only enrolled devices report, so the console-side
approximation is `partner_notifications_denied` event count over the active `pairings`
collection count.

## 2. Server-side rails (code, done — queryable, console charts pending)

| §14 metric | Rail | Query shape |
|---|---|---|
| Invocation count / error rate / duration | Cloud Functions built-in metrics (no code needed) | Console / Logging metrics; alert policy per §14 (see §4) |
| Dispatch outcome per installation, by provider | `dispatch-record.js` writes `DISPATCHED` / `FAILED_RETRYABLE` / `FAILED_TERMINAL` on `/relay-dispatch/{pairId}/installations/*` | Firestore collection-group count over `status`, grouped by `provider` |
| Sweep: retried per run, terminal after exhausting attempts | `sweep.js` logs `dispatch sweep {scanned, redispatched, terminal}` | Log-based counter metric on the `terminal`/`redispatched` fields |
| Rate-limit rejection count per pair | `relay.js` logs `mailbox rejected {messageId, pairId, relayStatus}` with `relayStatus=RATE_LIMITED` (Step 18; the author-read side surfaces it to the sender in-app) | Log-based counter metric keyed on `pairId` |
| T2 latency (§11: Firestore write confirmed → provider acceptance) | derivable from relay-dispatch records: mailbox `createdAt` → installation record `lastAttemptAt` at `DISPATCHED` | Firestore query; not a log metric |
| T3 latency (§11: provider acceptance → notification posted) | **not client-measurable** — would require the receiver's clock; cross-device clock skew makes it dishonest | staging/harness only (ADR #46 virtual partner) |
| T4 acknowledgment records | out of scope per plan (§14 "Optional"); ack records exist (Step 14) but are not aggregated | — |

## 3. §17 line 639 — maxInstances (configured + deployed) and budget alert (blocked)

- `maxInstances` is set and deployed (functions deploy 2026-09-18): `registerPairing` 10
  (index.js:49), `relayMailboxMessage` 10 (relay.js:80), `dispatchSweep` 1 (sweep.js:26),
  `cleanupMailboxes` 1 (cleanup.js:24). AD-20a's load estimate (OQ-1) remains the bound's
  justification; the values are placeholders per AD-20a until OQ-1 is answered.
- **Budget alert: BLOCKED, named not dropped.** §14 requires it at 80% of the monthly figure
  set in §16 (OQ-5) — that figure is an unanswered product/finance decision, and the GCP
  console/gcloud access side is likewise still to be set up. Task #34 tracks it.

## 4. GCP console configuration (task #34 — not code, needs user)

To be done in the Firebase/GCP console (project `myexodus`):

1. **Alert policy — error rate:** Cloud Function error rate > 5% over 15 minutes (§14 line 570).
2. **Alert policy — sweep failure (R-03):** scheduler execution failure/delay signal.
3. **Log-based counter metrics:** the four log rails in §2 (sweep terminal count, relay rate-limit
   rejections per pair, plus dispatch failures if charted from logs).
4. **Dashboards:** one per §14 bullet, wired to the metrics above or to Firestore queries.
5. **Budget alert at 80%** of the OQ-5 monthly figure (blocked on that figure).

§18 line 649 (dashboards live with one day of real staging data) has no staging environment in
this project — the honest reading is: dashboards can go live on the production project
(single-user scale, real data), and the "staging day" criterion is a DoD item to be met or
explicitly deferred with the rest of §18.

## 5. Honest limits (recorded, not hidden)

- Crashlytics gives counts and issue grouping, **not percentiles** — T1/T3 "distributions"
  land as bucketed counts; p50/p95 targets are approximated by bucket shares.
- T3 is provider- and OS-side (FCM delivery → OS posting); no client or server clock pair can
  measure it honestly outside a controlled harness.
- XOS (the physical test device) dispatches no WorkManager jobs — the periodic telemetry rail
  only samples on OEMs that run WorkManager; noted for metric interpretation.
