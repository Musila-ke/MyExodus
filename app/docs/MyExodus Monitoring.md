# MyExodus Monitoring — Partner Pillar §14 Realization

*Plan section 14 (partner-pillar-implementation-plan-v2.md lines 568-580) lists eleven monitoring
requirements; §17 line 639 adds the budget alert + `maxInstances` acceptance item; §18 line 649
requires the dashboards to be live with one day of staging data. This document records, per
requirement, exactly what exists in code, what exists as server log rails, and what is console
configuration that still needs doing — with its blocker named.*

Status as of 2026-09-19: client + server instrumentation COMPLETE and test-verified
(664/0/0 JVM, 30/30 functions). **Console configuration DONE (task #34, 2026-09-19)** — created
via the Google APIs using the firebase-tools OAuth token (no console clicks needed once the
OQ-5 figure — $5/month — was supplied by the owner): 1 email notification channel, 3 alert
policies, 3 log-based metrics, 1 dashboard, 1 budget alert; every artifact listed and verified
in §4 (all enabled, all wired to the channel).

**2026-09-28 (task #37, ADR #101): the pairing-ceremony plan's own §14 (six bullets) is
realized too — see §5.** Added to the live project: 2 log-based metrics
(`pairing_transcript_mismatch_count`, `pairing_idempotency_replay_count`), 1 alert policy
(429-throttling, wired to the same channel), 4 dashboard tiles + a text-map update, and the
client `pairing_ceremony_outcome` rail. The dashboard tile text also had a stale figure
corrected ($5 → $2 budget).

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

**Send acceptance is not delivery or display (Step 9, ADR #203).** The DMS outbox field
`provider_accepted` records the provider's acceptance of a send and nothing more; no metric,
log line, or UI may read it as delivery or display. Client sync health is observed in-app
(`PartnerAlertHealth` states and sync-pass logs), and display truth is the user-triggered test
alert plus the OS notification state — the same separation T3 states above. A missing display
on the receiver never moves a server metric, and a successful send never claims one.

## 3. §17 line 639 — maxInstances (configured + deployed) and budget alert (DONE)

- `maxInstances` is set and deployed (functions deploy 2026-09-18): `registerPairing` 10
  (index.js:247), `relayMailboxMessage` 10 (relay.js:72), `retryDispatchSweep` 1 (sweep.js:46),
  `cleanupMailboxes` 1 (cleanup.js:24). AD-20a's load estimate (OQ-1) remains the bound's
  justification; the values are placeholders per AD-20a until OQ-1 is answered. (Line refs
  refreshed 2026-10-17; the earlier `dispatchSweep` name was wrong — the exported/deployed
  name has always been `retryDispatchSweep`.)
- **Budget alert: DONE 2026-09-19 (task #34).** OQ-5 answered by the owner: **$2/month**
  (owner revised from $5 the same day; budget PATCHed via the Billing Budgets API).
  Budget `MyExodus monthly budget (OQ-5: $2)` ($2.00, project-scoped to myexodus-53804)
  with thresholds at 80% ($1.60) and 100%, notifying the monitoring email channel *and* the
  billing admins (default IAM recipients kept). Pre-existing separate budget "Firebase Project
  myexodus-53804" ($1, Firebase-auto-created) left untouched.

## 4. GCP configuration (task #34 — DONE 2026-09-19, created via APIs)

Created with the firebase-tools OAuth token against the existing project (`myexodus-53804`;
Firebase IS the GCP project — no separate product). Verified by list-and-read after creation:

1. **Notification channel:** `MyExodus owner email` — `projects/myexodus-53804/notificationChannels/18347258884556077313`, email → the project owner (`project-owner@example.invalid`, confirmed via `getIamPolicy` `roles/owner`), enabled. If Google sends an email-channel verification note, clicking it is the only remaining step.
2. **Alert policy — server errors:** `MyExodus: Cloud Functions server errors (HTTP 5xx, 15m)` — policy `7634493270550799513` (same policy, conditions replaced 2026-10-28, ADR #224). One condition: `resource.type="cloud_run_revision" AND metric.type="run.googleapis.com/request_count" AND metric.labels.response_code_class="5xx"`, ALIGN_SUM over 900 s, REDUCE_SUM, `COMPARISON_GT 2` (fires at ≥3 5xx in 15 m), duration 0 s, one email channel (`18347258884556077313`), severity ERROR, `autoClose 1800s`.
   - **Why the old error policy was retired (2026-10-28).** The previous policy (`… error rate > 5% (15m)`, later AND-ed with a ≥2-errors floor) counted `cloudfunctions.googleapis.com/function/execution_count` with `status="error"` — but every thrown `HttpsError` is an "error" execution, including expected client behavior: App Check 401 rejections, `RELATIONSHIP_UNAVAILABLE` 403s from a phone whose pair was removed, and 400 invalid-argument refusals. It produced **56 closed incidents** and zero real defects. Evidence from the 7-day window read on 2026-10-28: `submitHeartbeat` 119 error vs 489 ok executions, dominated by 401/403; the `readPartnerStatus` 403 entries were plain Cloud Run 4xx request logs from carrier IPs; the only `5xx` series held 9 requests with 0 s latency and no matching stderr entry (edge transport, never handler code). A 4xx-first alert policy cannot page honestly on this surface.
   - **Standing semantics.** 5xx (`response_code_class`) is the only signal that says "the function failed." 4xx stays visible in the dashboard's request-by-response-code tiles and CF logs, and the App Check / relationship rejection paths keep their own client-facing behavior; they are not incidents. A genuine deploy-wide break shows 5xx across routes; a client-transport blip shows 0 s-latency 500s with no stderr entry.
   - **2026-10-21 incident class (index builds, historical):** 19 incidents across Oct 15–21; the Oct 16–18 ones were `retryDispatchSweep` and the Oct 21 ones `dmsDeadlineWorker` failing with gRPC code 9 `FAILED_PRECONDITION … index is currently building`. Fixed at the source: scheduled handlers defer through index-build windows via `functions/index-build-guard.js` (ADR #202). Under the 5xx policy those ticks would no longer page regardless — the guard is still the correct behavior, the alert is now just honest about it.
3. **Alert policy — sweep failure (R-03):** `MyExodus: dispatch sweep not running (R-03)` — conditionAbsent on `retryDispatchSweep` execution_count for 30 min, severity WARNING (scheduler failure/delay signal).
   - **2026-10-18 refinement (success series):** the condition filter now also carries `metric.labels.status = "ok"` (ADR #180). Without it, the 2026-10-17 billing incident was anchored to the `status="error"` series; when executions resumed as `status="ok"` that series never returned, and Google's absent-data semantics close such an incident only after `autoClose + 24 h` (1 h + 24 h), so the recovery surfaced as a fresh-looking warning ~25 h after the fact. Pinning `status="ok"` also means a sweep that only errors (no success points) trips the alarm. `autoClose` remains 3600 s.
     - **Closure rules, verified against official docs (read 2026-10-18):** a time series is the metric type + one combination of label values, so `status="ok"` and `status="error"` are distinct series; an alert opens per series whose condition is met, one open alert per series. A metric-absence alert closes when **an observation occurs** for that series, or otherwise when **no observations arrive for 24 h after the policy's auto-close duration expires**. `alertStrategy.autoClose` defaults to **7 days** (minimum 30 min); a disabled policy's open alerts stay until it expires; snoozing the policy closes them; acknowledging an alert does not close it. Sources: "Behavior of metric-based alerting policies" and "Incidents and events" at `docs.cloud.google.com/monitoring/alerts/concepts-indepth` and `/alerts/incidents-events`.
4. **Alert policy — terminal delivery:** `MyExodus: relay gave up on a delivery (terminal)` — user log metric `sweep_terminal_count` sum over 1 h > 0, severity ERROR (a push permanently undelivered after the 8-attempt cap).
5. **Log-based counter metrics:** `sweep_terminal_count` (`jsonPayload.terminal > 0` on `dispatch sweep`), `relay_rate_limit_rejections` (label-extracted `pair_id`, `relayStatus="RATE_LIMITED"`), `dispatch_failures` (labels `terminal`, `provider`).
   - **2026-10-03 correction (ADR #131):** three of the five user log metrics — `dispatch_failures`, `relay_rate_limit_rejections`, `sweep_terminal_count` — were created filtering `resource.type="cloud_function"`, which **no gen2 log entry ever carries**. Platform metrics dual-emit under `cloud_function`, but log *entries* from gen2 functions are all `cloud_run_revision` — so those three could never match an entry, and the terminal-delivery alert they feed (item 4) was permanently blind. All five filters now read `resource.type="cloud_run_revision"` (the two pairing metrics already did) and were each verified by API re-read. Live filters as of 2026-10-03:
     - `dispatch_failures`: `resource.type="cloud_run_revision" AND jsonPayload.message="dispatch failed"`
     - `relay_rate_limit_rejections`: `resource.type="cloud_run_revision" AND jsonPayload.message="mailbox rejected" AND jsonPayload.relayStatus="RATE_LIMITED"`
     - `sweep_terminal_count`: `resource.type="cloud_run_revision" AND jsonPayload.message="dispatch sweep" AND jsonPayload.terminal > 0`
     - `pairing_idempotency_replay_count` / `pairing_transcript_mismatch_count`: unchanged (already `cloud_run_revision` + `service_name="registerpairing"`).
   - **Silent-when-empty is by design, not drift:** `retryDispatchSweep`'s `dispatch sweep` log line sits *after* the empty-queue fast path (`functions/dispatch-logic.js` returns before the `logger.info`), so a sweep with nothing stale emits no entry at all. Zero `dispatch sweep` entries across the project's history with an empty queue is correct; the R-03 absent-condition policy (item 3, platform metric) is what catches a genuinely dead sweep.
6. **Dashboard:** `MyExodus — §14 Monitoring` (`projects/913808045052/dashboards/1cf1a456-177d-4132-a078-5b98bed816e0`) — CF executions by status, terminal sweeps/hour, rate-limit rejections per pair, dispatch failures, plus a text tile mapping every §14 metric to its rail (Crashlytics client events, Firestore queries, this dashboard, billing).
7. **Budget alert:** see §3.
8. **Alert policy — DMS fleet late-burst breaker (Step 8, ADR #201):** `MyExodus: DMS fleet late-burst breaker opened (Step 8)` — log-based condition on `resource.type="cloud_run_revision" AND jsonPayload.message="dms circuit breaker opened: fleet-wide late burst"`, severity ERROR, routed to the owner email channel above. Policy `projects/myexodus-53804/alertPolicies/12571310519408075705`, enabled and read back 2026-10-21. `alertStrategy` is `autoClose: 86400s` + `notificationRateLimit.period: 300s` — **a log-based policy is rejected at creation without an explicit rate limit** (learned live), and the 24 h auto-close means a persistent condition fires about once a day rather than continuously. The alert is the page; the worker also writes `config/dms.dispatch_hold=true`, and that hold persists until an operator releases it — acknowledging the alert is not releasing the fleet. The condition was verified enabled, not fired (no real fleet burst induced).

§18 line 649 (dashboards live with one day of real staging data) has no staging environment in
this project — the honest reading is: dashboards can go live on the production project
(single-user scale, real data), and the "staging day" criterion is a DoD item to be met or
explicitly deferred with the rest of §18.

## 5. Pairing-ceremony plan §14 (task #37, DONE 2026-09-28 — ADR #101)

*The pairing plan's own §14 (partner-pairing-implementation-plan.md lines 441–449, six bullets)
is a separate requirement set from the partner-pillar-v2 §14 above — it instruments the ceremony
(`registerPairing` + `PairingCeremonyScreen`), not the sync pillar. Wired per §7 step 16: after
the functional flow passed the emulator suite (ADR #100).*

| §14 bullet | Rail | Where |
|---|---|---|
| 1. Session outcomes by terminal state (+ CANCELLED reason) | Client event `pairing_ceremony_outcome outcome=<x>` per ceremony terminal, via `CrashReporting.nonFatal` (one issue class, metadata only). Terminals: `paired`, `sas_mismatch`, `expired`, `token_used`, `cancelled`, `failed`. Exactly-once per attempt: every path routes through one `reportOnce()` seam (`AtomicBoolean` CAS, reset on attempt start) so a double-tap on Cancel cannot double-count. Wire strings pinned by `PairingCeremonyTelemetryTest`. | `PairingCeremonyTelemetry` + `PairingCeremonyScreen` |
| 2. Cross-session per-reporting-UID mismatch counter (manual review, never a lock) | Log-based metric `pairing_transcript_mismatch_count`, labels `uid` (the reporter whose proof disagreed) + `pair_id`; emitted by the `registerPairing rejected reason=TRANSCRIPT_MISMATCH` warn line. 24h rolling view = the metric in the explorer with a 24h alignment; "distinct partners" = distinct `pair_id` values per `uid`. | metric: `logging.googleapis.com/user/pairing_transcript_mismatch_count` |
| 3. CF invocation counts / error rates / throttling | Native `run.googleapis.com/request_count` per `registerpairing` (labels `response_code`, `response_code_class`, `route`); dashboard tile "requests by response code" (ALIGN_SUM 300 s). 429 = `maxInstances` throttling → its own WARNING alert policy (fires on first 429 in 5 min, auto-close 30 min). Server-error coverage is the fleet-wide 5xx policy (§4 item 2 — `run.googleapis.com/request_count`, `response_code_class="5xx"`; the old `cloud_function` error-count policy was retired 2026-10-28 because 4xx client rejections counted as errors, ADR #224). | dashboard + policy `projects/myexodus-53804/alertPolicies/1164734849334613422` |
| 4. Idempotency cache-hit rate | Numerator: log-based metric `pairing_idempotency_replay_count` (the deployed `registerPairing idempotent replay` line). Denominator: bullet 3's request count. Dashboard tile shows the numerator; a rising ratio = client retry bug, not network. | metric: `logging.googleapis.com/user/pairing_idempotency_replay_count` |
| 5. Cold-start latency distribution | Platform metric `run.googleapis.com/container/startup_latencies` (DELTA distribution; verified carrying live data before the tile was built). Dashboard tile: p50 + p95. **No code was added** — a hand-rolled `bootMs` log line would measure process init, not container startup, and would be the dishonest version. | dashboard tile "cold start latency (p50/p95)" |
| 6. No SAS/tokens/signatures/key material in any label or log line | Contract enforced in code: `PairingCeremonyTelemetry` reports only `outcome=<enum>` (KDoc records Decision 12; the exact-string test would fail on any added field); server log lines carry `reason`/`pairId`/`uid` only, never protocol material; `failed` deliberately folds specific rejection causes into one token so they stay in logcat/CF logs and don't leak into metric labels. | reviewed in #37; pinned by test |

Vocabulary reconciliation (recorded, not silent): the plan's cancel reasons
`user_cancelled` / `superseded` / `process_restart_required` predate the shipped token-doc design —
`user_cancelled` is the only shipped reason; the other two have no counterpart (no single-active-
session lock to supersede; pending keys are in-memory so a restart drops the attempt and nothing
survives to require). A `CANCELLED`-reason breakdown metric is therefore unnecessary — there is
exactly one reason.

### 5.1 Honest limits
- Crashlytics gives **counts and issue grouping, not rates with denominators** — the outcome
  tile is console-side event counting; "paired rate" = `paired` count over total outcome count,
  computed by reading the console, not stored.
- The 429 policy is a **"the cap engaged" signal, not a saturation curve** (threshold >0 on a
  5-min sum): one throttled request pages, and no distinction is drawn between a single shed
  request and sustained shedding.
- Cold-start latency is **service-wide**: deploy-triggered instance starts are included alongside
  user-triggered ones. With containerConcurrency 80 a cold start serves many requests, so the
  distribution is the honest platform view of what a cold request might pay, not a per-request
  average.
- The mismatch counter's consumer is a **human reading a dashboard** (per §5.19: manual review,
  never an automatic lock) — the rail deliberately stops at "indexed and queryable."

## 6. Honest limits (recorded, not hidden) — partner-pillar-v2 §14 rails

- Crashlytics gives counts and issue grouping, **not percentiles** — T1/T3 "distributions"
  land as bucketed counts; p50/p95 targets are approximated by bucket shares.
- T3 is provider- and OS-side (FCM delivery → OS posting); no client or server clock pair can
  measure it honestly outside a controlled harness.
- XOS (the physical test device) dispatches no WorkManager jobs — the periodic telemetry rail
  only samples on OEMs that run WorkManager; noted for metric interpretation.

## 7. Partner-accountability operator kill switch (Step 12, ADR #207)

One boolean on the existing operator document — the same document the SOS switch lives on, no new dependency:

```
config/features.partnerAccountabilityEnabled   // absent doc or absent field = ENABLED; only explicit false = OFF
```

Flip with the Firestore REST recipe in the GCP API notes:

```
PATCH https://firestore.googleapis.com/v1/projects/myexodus-53804/databases/(default)/documents/config/features?updateMask.fieldPaths=partnerAccountabilityEnabled
{"fields":{"partnerAccountabilityEnabled":{"booleanValue":false}}}   // restore: true
```

What an explicit `false` stops, at three boundaries — all server-side, so it covers every installed build without a client release:

1. **New client sends** — the mailbox create rule denies every new envelope write (`firestore.rules`, "Step 12 operator gate"). The relay is `onDocumentCreated`, so it never triggers for a denied write.
2. **DMS notifications** — `dmsDeadlineWorker` returns `{ disabled: 'PARTNER_ACCOUNTABILITY' }` before any deadline transition or dispatch; nothing is written, nothing is pushed.
3. **In-flight pushes** — `retryDispatchSweep` returns before listing retryable records, so messages created before the flip are not retried while the feature is off.

What it does **not** do: it does not delete or rewrite anything (pairings, mailbox messages, acks, client outbox rows, DMS state and outbox rows all stay), it does not affect the SOS path (`partnerSosEnabled` is separate), and it cannot recall a push the provider already accepted.

Verification that a flip took effect (live-verified 2026-10-22):

- flag `false` → next 5-minute worker pass logs `dms worker disabled: partner accountability switched off`; next even-minute sweep pass logs `dispatch sweep disabled: partner accountability switched off` (the pass that ran before the flip logged `dms worker pass`).
- flag `true` → the next worker pass logs `dms worker pass` again.
- Client side: a denied send leaves its outbox row pending with jittered exponential backoff capped at 6 h; it drains after re-enable. A denied send does **not** raise the OPD #30 re-pair card (that card only observes pairing-document read denials, which this switch does not touch).

Rollback evidence and the measured rollout criteria are in plan Appendix S12; the decision record is ADR #207.
