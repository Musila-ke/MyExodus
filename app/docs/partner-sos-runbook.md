# Partner SOS ("Reach Out") — Operational Runbook

*SOS plan §14.5 deliverable. Grounded in the deployed functions source (`functions/partner-sos.js`, `partner-sos-push.js`, `partner-sos-push-logic.js`, `partner-sos-scheduler.js`) and the live project `myexodus-53804`, verified 2026-10-31. Behaviour claims below are source reads, not assumptions; live-state claims were read back from the APIs on the date stated.*

**Current state (2026-10-31):** backend deployed and **LIVE** — `config/features.partnerSosEnabled` is **true** (read back from Firestore after the owner ruling below). Owner ruling 2026-10-31: SOS stays enabled for real users 24/7; the field was turned on and left on. No SOS-specific Cloud Monitoring alert policies exist yet (§8 records this honestly); the feature is the only one in this project whose alerts were deferred to its rollout step.

The whole feature has **one operational control**: the server-side kill switch. The client Remote Config flag was removed 2026-10-15 (ADR #158), and Remote Config itself is retiring (ADR #171) — do not look for a second switch.

All commands run from the repo root. Nothing here is run automatically.

---

## 0. Surface map

| Thing | Value |
|---|---|
| Callable | `sendNudge` (`us-central1`, `enforceAppCheck: true`, 15 s timeout) |
| Queue (root, client-denied) | `nudgePushQueue/{nudgeId}` |
| Dispatch trigger | `onNudgeQueueCreated` — Firestore `onDocumentCreated` on the queue, 60 s timeout |
| Repair sweep | `resumePendingNudges` — Cloud Scheduler job `firebase-schedule-resumePendingNudges-us-central1`, **every 5 minutes**, `SWEEP_LIMIT = 50`, `maxInstances 1` |
| Nudge record | `pairings/{pairId}/nudges/{nudgeId}` |
| Rate state | `pairings/{pairId}/nudgeState/{senderUid}` (cooldown + daily cap) |
| Recipient block | `pairings/{pairId}/nudgeSettings/{recipientUid}` → `blocked` |
| Device registry | `users/{uid}/push_installations/{installId}` (`token`, `pushEligible`, `platform`, `updatedAt`) |
| Kill switch | `config/features.partnerSosEnabled` (server-only; rules deny client read and write) |
| Client channel / tray entry | channel `partner_nudges_v3` (IMPORTANCE_HIGH), notification id `2005`, tag `partner_nudge` |
| Lease | 90 000 ms (`LEASE_MS`) |
| Attempt budget | 4 claims (`MAX_PUSH_ATTEMPTS`); backoff 60 s → 120 s → 240 s, ±25 % jitter |
| Retention | `expiresAt` on both the nudge and the queue; Firestore TTL ACTIVE on collection groups `nudges` and `nudgePushQueue` |

### Nudge statuses

| Status | Meaning |
|---|---|
| `pending` | Accepted, not yet finalized (in queue, possibly mid-retry) |
| `sent` | Every target was accepted by FCM in some attempt |
| `partial` | Some targets accepted; others exhausted the budget or were invalid |
| `failed` | No target accepted, or the payload was rejected, or the record expired before dispatch |
| `suppressed` | Not delivered by design: recipient blocked, or pair no longer active |
| `no_eligible_device` | Recipient had no registration with `pushEligible == true` at dispatch time |

Terminal statuses are exactly those six minus `pending`; a queue document is deleted in the same transaction that writes a terminal status.

---

## 1. Kill switch

**Semantics.** `sendNudge` reads `config/features.partnerSosEnabled` *inside* its transaction on **every** request — no cache, no TTL. The check is `!== true`, so an absent document, absent field, or any non-`true` value is OFF (fail-closed). When off, the callable returns `FAILED_PRECONDITION` with `feature_disabled` and writes nothing.

**What it stops:** new acceptance (`sendNudge`).
**What it does not stop:** dispatch/retry of nudges accepted *before* the flip. The repair sweep deliberately does not consult the switch (proven 2026-10-18, mapping §5.4) — already-accepted work drains or expires. This is the plan's intent (§13.3): rollback stops new acceptance, it does not erase or cancel accepted work.

**Client effect:** the Reach out control is structural now (Home + a live pair); flipping the switch produces a clean refusal in the sender UI, not a hidden button.

### Flip OFF (rollback / emergency)

```bash
# Token minting with no gcloud: exchange the firebase-tools refresh token, using the
# CLI's own public OAuth client (read from the installed package — never commit it).
FBT=$(cd "$(dirname "$(command -v firebase)")/../lib/node_modules/firebase-tools" && pwd)
TOKEN=$(curl -s -X POST https://www.googleapis.com/oauth2/v4/token \
  --data-urlencode "client_id=$(node -p "require('$FBT/lib/api.js').clientId()")" \
  --data-urlencode "client_secret=$(node -p "require('$FBT/lib/api.js').clientSecret()")" \
  --data-urlencode "refresh_token=$(jq -r .tokens.refresh_token ~/.config/configstore/firebase-tools.json)" \
  --data-urlencode "grant_type=refresh_token" | jq -r .access_token)

curl -s -X PATCH \
  "https://firestore.googleapis.com/v1/projects/myexodus-53804/databases/(default)/documents/config/features?updateMask.fieldPaths=partnerSosEnabled" \
  -H "Authorization: Bearer $TOKEN" \
  -H "x-goog-user-project: myexodus-53804" \
  -H "Content-Type: application/json" \
  -d '{"fields":{"partnerSosEnabled":{"booleanValue":false}}}'
```

**Always read back** — the PATCH response echoes the merged document; confirm `partnerSosEnabled` is `false` and the sibling `partnerAccountabilityEnabled` field was not disturbed:

```bash
curl -s \
  "https://firestore.googleapis.com/v1/projects/myexodus-53804/databases/(default)/documents/config/features" \
  -H "Authorization: Bearer $TOKEN" -H "x-goog-user-project: myexodus-53804" \
  | jq -r '.fields | to_entries[] | "\(.key)=\(.value)"'
```

To turn ON, same PATCH with `true`, then verify. ON is the production state (owner ruling 2026-10-31); the old "flip back to `false` after a test window" constraint is retired. Flip OFF only for rollback/emergency (§2).

The token recipe mints from firebase-tools' stored refresh token; never print or persist the token. No `gcloud` exists on this machine.

---

## 2. Rollback

1. **Primary — flip the switch off** (§1). Takes effect on the very next callable; no deploy, no app update, no cache window. Already-accepted nudges remain in Firestore and either drain or expire by TTL.
2. **Backend code rollback (only if the switch is insufficient).** Flip the switch off **first**, then redeploy the last tagged functions version:
   ```bash
   firebase deploy --only functions:sendNudge,functions:onNudgeQueueCreated,functions:resumePendingNudges
   ```
   run from the checkout of the last known-good tag.
3. **Never delete the collections** (`nudges`, `nudgePushQueue`, `nudgeState`, `nudgeSettings`) to roll back application code — §13.3 forbids it; TTL owns retention.
4. **Does not recall a push** the FCM provider already accepted. Delivery is best-effort by nature; the in-app card (Firestore) is the source of truth for the recipient.
5. **No client rollback lever exists.** Older APKs do not understand the card but still receive a generic push where FCM delivers; there is no flag to ship or flip.

---

## 3. Locating stuck queue work

### Queue document schema

| Field | Meaning |
|---|---|
| `pairId` | Owning pair (the path to the nudge record) |
| `createdAtMs` | Queue creation epoch ms — the age anchor |
| `expiresAt` | Nudge expiry; a queue that outlives it is finalized `failed`/`expired`, never sent |
| `nextAttemptAtMs` | Due time; the sweep query is `nextAttemptAtMs <= now`, ascending, limit 50 |
| `attemptCount` | Claims spent (incremented inside the claim transaction) |
| `leaseId`, `leaseUntilMs` | Live dispatcher ownership; unexpired lease = do not intervene |
| `targetInstallIds`, `sentInstallIds` | Dispatch ledger (multicast targets; delivered set) |

### Query due work (REST)

```bash
curl -s -X POST \
  "https://firestore.googleapis.com/v1/projects/myexodus-53804/databases/(default)/documents:runQuery" \
  -H "Authorization: Bearer $TOKEN" -H "x-goog-user-project: myexodus-53804" \
  -H "Content-Type: application/json" \
  -d '{"structuredQuery":{
        "from":[{"collectionId":"nudgePushQueue"}],
        "where":{"fieldFilter":{"field":{"fieldPath":"nextAttemptAtMs"},"op":"LESS_THAN_OR_EQUAL","value":{"integerValue":"'"$(date +%s)000"'"}}},
        "orderBy":[{"field":{"fieldPath":"nextAttemptAtMs"},"direction":"ASCENDING"}],
        "limit":50}}'
```

The same query in the Firestore console: collection `nudgePushQueue`, filter `nextAttemptAtMs` `<=` now, order ascending — exactly what the sweep runs.

### Diagnosis table

| Observation | Meaning | Action |
|---|---|---|
| Due, no lease, `attemptCount < 4` | Normal backlog; the next 5-minute sweep picks it up | Wait one sweep; if it persists across two sweeps, check the job (§5) and function logs |
| Due, `leaseUntilMs` in the future | A dispatcher owns it | Do not touch. A live lease expires in 90 s; a crashed one also expires in 90 s and the sweep reclaims it |
| Due, `attemptCount >= 4` | Budget exhausted; the **next** claim finalizes it terminal (and logs `nudge_stuck`) | None — it will clear itself; investigate the `errorCode` in `nudge_push_result` |
| `nextAttemptAtMs` far in the future | Backoff after transient failures (max 240 s + jitter) | Wait |
| Queue missing, nudge `pending` | Invariant violation (TTL raced a stalled retry). Read as `nudge_queue_missing` in logs when a dispatch is invoked for it | Recreate the queue document (§5 manual repair) |
| Queue exists, nudge terminal | Stale queue awaiting cleanup | The next dispatch deletes it (`terminal_cleaned`) |

### What a stuck nudge looks like end to end

- The **nudge** `pairings/{pairId}/nudges/{nudgeId}` still has `status: pending`, `pushAttempts` > 0, `lastPushErrorCode` set.
- The **queue** is due but not advancing (`attemptCount` unchanged across sweeps).
- The recipient sees no card until the nudge reaches terminal; the card is built from the nudge document, not the queue.

Suspected stuck work is read-only to diagnose; the system is designed so no manual mutation is required. The only sanctioned manual write is the missing-queue repair in §5.

---

## 4. Inspecting a hashed nudge ID

Structured logs never carry raw identifiers. Every nudge reference is `nudgeHash` = first 12 hex characters of `SHA-256(value)` (`hashForLog`, `functions/partner-sos.js`). Pair IDs use the same function (`pairHash`).

```bash
printf '%s' "$NUDGE_ID" | shasum -a 256 | cut -c1-12
```

- Hashes are one-way: always start from the raw ID (a user report, a Firestore document, a device log) and hash it to search. You cannot recover an ID from a log line.
- Raw IDs live only in Firestore paths: `pairings/{pairId}/nudges/{nudgeId}` and `nudgePushQueue/{nudgeId}`.
- To see a nudge's full lifecycle, filter logs on the computed hash and read the events in timestamp order (§9).

Privacy: envelope content is ciphertext end-to-end and never logged; `pair_id` rides the FCM data payload but not logs.

---

## 5. Manually invoking the push repair function

The sweep is a Cloud Scheduler–driven function; there is no public HTTP endpoint. Trigger the **same** function manually through the scheduler job (verified present and `ENABLED`, schedule `every 5 minutes`, last attempt 2026-10-31T06:02:04Z):

**Console:** Cloud Scheduler → `firebase-schedule-resumePendingNudges-us-central1` → **Run now**.

**REST:**

```bash
curl -s -X POST \
  "https://cloudscheduler.googleapis.com/v1/projects/myexodus-53804/locations/us-central1/jobs/firebase-schedule-resumePendingNudges-us-central1:run" \
  -H "Authorization: Bearer $TOKEN" -H "x-goog-user-project: myexodus-53804" \
  -H "Content-Length: 0"
```

An empty `{}` response means the run was **queued**, not that the sweep finished. Confirm in logs: one `nudge_resume` line per due item plus the summary `nudge_resume {event:"sweep_complete", due, resumed, skipped, errored}`.

What one sweep does: claims each due queue document through the same lease-protected dispatch engine (overlap with the trigger or a previous sweep is harmless — the lease decides ownership), up to 50 documents per run. One poison document cannot stop the sweep; it is logged and the next tick retries the rest.

### Repairing a missing queue document (the one sanctioned manual write)

When a nudge is `pending` but `nudgePushQueue/{nudgeId}` is gone, recreate the document with the raw nudge ID as the document ID. The creation trigger dispatches immediately; the sweep is the fallback. Preserve the attempts already spent so repair is not a fresh budget:

```json
{
  "fields": {
    "pairId":          {"stringValue": "<pairId>"},
    "createdAtMs":     {"integerValue": "<now-ms>"},
    "expiresAt":       {"timestampValue": "<copy from the nudge doc expiresAt>"},
    "nextAttemptAtMs": {"integerValue": "<now-ms>"},
    "attemptCount":    {"integerValue": "<nudge.pushAttempts>"},
    "leaseId":         {"nullValue": null},
    "leaseUntilMs":    {"nullValue": null}
  }
}
```

`PATCH` or `POST` to
`https://firestore.googleapis.com/v1/projects/myexodus-53804/databases/(default)/documents/nudgePushQueue/{nudgeId}`
with `?documentId=` on create. Never create a queue for a nudge that is already terminal — the engine deletes it (`terminal_cleaned`).

---

## 6. Interpreting FCM errors

The dispatcher strips the `messaging/` prefix and classifies the remainder (`partner-sos-push-logic.js`). The **first** error code of an attempt is what lands on the nudge (`lastPushErrorCode`) and in `nudge_push_result.errorCode`; per-target counts are aggregate.

| Class | Codes | Behaviour |
|---|---|---|
| `invalid_target` | `registration-token-not-registered`, `installation-id-not-registered`, `invalid-registration-token`, `invalid-recipient` | The registration is deleted (`users/{uid}/push_installations/{installId}`) and that target is terminal. An eligible registration with an empty `token` rides the same removal path. |
| `quota` | `message-rate-exceeded`, `device-message-rate-exceeded`, `topics-message-rate-exceeded`, `topics-subscription-rate-exceeded` | Transient; retried with the standard backoff (≥60 s floor). Recurring quota errors over ~10 min deserve investigation (§14.3 alert). |
| `payload` | `invalid-payload`, `invalid-data-payload-key`, `payload-size-limit-exceeded`, `invalid-argument`, `invalid-options`, `invalid-package-name` | **Engineering defect.** Never blind-retried: the nudge fails immediately and `nudge_push_payload_invalid` is logged at ERROR. Treat as a release blocker. |
| transient (everything else) | `server-unavailable`, `internal-error`, auth errors, unknown | Retried until the 4-claim budget is exhausted, then terminal `partial` (some landed) or `failed`, with `nudge_stuck` at WARN. |

Retry schedule: claim 1 failure → ~60 s, claim 2 → ~120 s, claim 3 → ~240 s, each ±25 %; claim 4 that still has pending targets finalizes terminal.

Where to look:

- **Nudge document:** `status`, `pushAttempts`, `lastPushErrorCode`, `lastPushAtMs`, `targetDeviceCount`, `sentDeviceCount`.
- **Logs:** `nudge_push_result` (every attempt: `attempt`, `targetCount`, `sentCount`, `transientCount`, `invalidCount`, `errorCode`, `outcome`), `nudge_status_final` (terminal), `nudge_stuck`, `nudge_push_payload_invalid`.

`installation-id-not-registered` has a known historical cause on this rig: FID-targeted sends from legacy-registered clients (D13, mapping) — the dispatcher now sends to **registration tokens**, so recurrence means a genuinely stale token, not the old bug.

---

## 7. Removing stale registrations

**Automatic.** A confirmed `invalid_target` deletes that installation document inside the dispatch path. The client re-registers on the next foreground (`MainActivity.onResume` → sync pass), on `onNewToken`, and after the notification-permission result — so deletion is self-healing, not destructive.

**Manual audit** (owner-scoped; rules allow only the owner to read/write their own `push_installations`):

```bash
curl -s -X POST \
  "https://firestore.googleapis.com/v1/projects/myexodus-53804/databases/(default)/documents:runQuery" \
  -H "Authorization: Bearer $TOKEN" -H "x-goog-user-project: myexodus-53804" \
  -H "Content-Type: application/json" \
  -d '{"structuredQuery":{
        "from":[{"collectionId":"push_installations",
                 "allDescendants":true}],
        "orderBy":[{"field":{"fieldPath":"updatedAt"},"direction":"DESCENDING"}],
        "limit":50}}'
```

Judge a row stale by `updatedAt` age and `pushEligible`:

- `pushEligible` is computed on every registration write as **app notifications enabled AND `partner_nudges_v3` exists AND its importance ≠ NONE**. A user who switched notifications off is *expected* to be ineligible; that is not a stale row.
- A row whose `token` is empty or whose `updatedAt` is days old while the user is active is suspect. Deletion is safe — the next foreground re-creates it.
- Never delete another user's registration: the rules refuse it anyway, and every recipient's eligibility is their own device's statement.

Device-side check for a recipient who "never gets pushes":

```bash
adb shell dumpsys notification --noredact | grep -E "partner_nudges_v3|mImportance"
adb shell cmd appops get com.example.myexodus POST_NOTIFICATION
```

The banner/eligibility truth on-device is `NotificationAccess.canPost` + channel importance, which is exactly what `pushEligible` reports.

---

## 8. Reading queue age metrics

**Honest state 2026-10-31:** no Cloud Monitoring metric or alert policy is wired for SOS queue age (the alert-policy list contains none for SOS). The §14.3 policies are part of the rollout (§13.2 item 7) and remain to be created when Step 15 is authorized. Until then, queue age is read from data:

- **Per queue document:** `age = now - createdAtMs`.
- **Per sweep item:** every `nudge_resume` line carries `ageMs` (null if `createdAtMs` is missing), plus `attempt` and `outcome`.
- **Sweep summary:** `nudge_resume {event:"sweep_complete", due, resumed, skipped, errored}` — counts only, no identifiers.

Golden signals and their §14.3 thresholds:

| Signal | Source | Threshold |
|---|---|---|
| Queue age p95 | `createdAtMs` on due queue docs; `ageMs` in `nudge_resume` | > 15 min → investigate/alert |
| `no_eligible_device` rate | `nudge_status_final.status` | trend, no absolute threshold |
| Partial/failed rate | `nudge_status_final.status` | trend |
| Invalid-target rate | `invalidCount` in `nudge_push_result` | > 30 % of attempted targets over 24 h |
| Stuck (budget exhausted) | `nudge_stuck` | any occurrence |
| Payload invalid | `nudge_push_payload_invalid` | any occurrence |
| Queue missing | `nudge_queue_missing` | any occurrence |
| Callable error rate | `nudge_rate_limited`/`feature_disabled`/response codes in Cloud Run metrics | > 2 % over 10 min with ≥ 20 requests; p95 > 5 s over 15 min |

Billing budget alerts at 50 %/90 % already exist (§13.2 item 7 satisfied: verified 2026-10-31 — two budgets, thresholds 0.5/0.9).

### Log queries

Logs Explorer filter (console):

```
jsonPayload.message="nudge_push_result"
jsonPayload.message="nudge_stuck" OR jsonPayload.message="nudge_push_payload_invalid" OR jsonPayload.message="nudge_queue_missing"
jsonPayload.message="nudge_resume"
jsonPayload.nudgeHash="<first 12 hex of the nudge id>"
```

API equivalent:

```bash
curl -s -X POST "https://logging.googleapis.com/v2/entries:list" \
  -H "Authorization: Bearer $TOKEN" -H "x-goog-user-project: myexodus-53804" \
  -H "Content-Type: application/json" \
  -d '{"resourceNames":["projects/myexodus-53804"],
       "filter":"jsonPayload.message=\"nudge_stuck\"",
       "pageSize":50,"orderBy":"timestamp desc"}'
```

When Step 15 is authorized, create the §14.3 policies on these exact log filters (log-based alerting) and the queue-age p95 policy from a computed metric over `nudge_resume.ageMs`.

---

## 9. VPN diagnostic procedure

**Design.** The DNS-sinkhole VPN (`DnsSinkholeVpnService`) excludes two packages from its tunnel: the app itself (`BuildConfig.APPLICATION_ID`) and GMS (`com.google.android.gms`, added at SOS Step 6). Consequence: the sender's callable (HTTPS through the app) and FCM delivery through GMS both work while Strict Mode's sinkhole blocks everyone else. The **recipient's** own VPN state is irrelevant to delivery — the dispatcher only uses FCM.

Use when: a reach-out fails while Strict Mode / the blocker is on, or FCM delivery looks broken under VPN.

### Sender side

1. **Confirm the tunnel is actually up.**
   ```bash
   adb shell ip addr show tun0
   adb shell ip route show table all | grep tun0
   ```
   No `tun0` → the blocker isn't running; start Strict Mode before diagnosing delivery.
2. **Confirm ordinary blocking still works** (the exemption must not weaken the boundary): open a blocked domain in the browser — it must stay blocked; a control domain must resolve. If blocked traffic gets through, the VPN exemption is wrong — stop and treat as a security regression, not a delivery issue.
3. **Send the reach-out.** The callable path is app-excluded, so it must succeed. A `FAILED_PRECONDITION feature_disabled` is the kill switch, not the network; `UNAVAILABLE`/timeout points at connectivity under the tunnel.
4. **Read the function's answer** in the logs (`nudge_accepted` / `nudge_rate_limited` / `nudge_suppressed`) and the nudge document's `status`.

### Recipient side

1. **Device eligibility:** notifications enabled, `partner_nudges_v3` exists and is not muted (§7 commands). Ineligible devices are excluded by design (`pushEligible`).
2. **Send a host FCM data push** (proven recipe, mapping §5.4): FCM v1 `projects/myexodus-53804/messages:send` to the newest paired installation's `token`, data-only `{type:"partner_nudge", nudge_id:"<uuidv4>", pair_id:"<live pair>"}`, `android.priority: "high"`. FCM acceptance (a `messages/…` name) proves provider-side delivery; the tray record (`id=2005`, tag `partner_nudge`, channel `partner_nudges_v3`) proves the client path.
3. **If the push is accepted but nothing posts:** check that the recipient's VPN is not force-stopping the app; check the notification channel state; capture `adb logcat -s MyExodusPartnerTest PartnerMessagingService` (the wake path logs are local, no payload content).
4. **Known rig facts:** the X6532/X6532C both delivered under an active VPN during §12.4 (mapping §5.3); heads-up capture on XOS requires plain screencaps (`screenrecord` suppresses the banner). The XOS app-level `appops set … POST_NOTIFICATION` is a silent no-op — do not try to drive notification-off through shell on that skin.

### Restore

The SOS feature never changes VPN state itself. If the diagnostic turned Strict Mode off/on, restore the user's prior state in the same command chain — this project treats device state changes as borrowed, not owned.

---

## 10. Log event reference

| Event | Emitted by | Key fields |
|---|---|---|
| `nudge_accepted` | `sendNudge` | `nudgeHash`, `pairHash` |
| `nudge_replay` | `sendNudge` | idempotent replay of the same `nudgeId` |
| `nudge_rate_limited` | `sendNudge` | `reason` (cooldown/daily cap), `retryAfterSeconds` |
| `nudge_suppressed` | `sendNudge`, dispatch | `reason`: `recipient_blocked` / `pair_inactive` |
| `nudge_dispatch_claimed` | dispatch | `nudgeHash`, `attempt` |
| `nudge_push_result` | dispatch | `attempt`, `targetCount`, `sentCount`, `transientCount`, `invalidCount`, `errorCode`, `outcome` |
| `nudge_status_final` | dispatch | terminal status, counts, `reason` (`expired`, etc.) |
| `nudge_stuck` | dispatch | budget exhausted with targets still pending |
| `nudge_push_payload_invalid` | dispatch | payload-class FCM failure (engineering defect) |
| `nudge_queue_missing` | dispatch | pending nudge whose queue vanished; repair path |
| `nudge_resume` | scheduler | per item: `nudgeHash`, `attempt`, `ageMs`, `outcome`; summary: `event:"sweep_complete"`, counts |
| `nudge_state_conflict` | `sendNudge` | transactional state conflict |

All lines carry hashes, counts, statuses and codes only — never envelopes, tokens, raw uids or pair ids.

---

## 11. Current operational state (read back 2026-10-31)

| Check | State |
|---|---|
| Kill switch `config/features.partnerSosEnabled` | **false** (inert) |
| Repair scheduler job | `firebase-schedule-resumePendingNudges-us-central1`, `every 5 minutes`, `ENABLED`, last attempt 2026-10-31T06:02:04Z |
| Functions deployed | `sendNudge`, `onNudgeQueueCreated`, `resumePendingNudges` (us-central1, nodejs22) |
| SOS alert policies | none (deferred to Step 15 rollout, §8) |
| Billing budgets | present — 50 %/90 % thresholds |
| Two-device delivery proof | mapping §5.2 (2026-10-04); FAB-tap + repair-sweep single-device proof §5.4 (2026-10-18); named data-only push §5.4 (2026-10-23) |
| VPN legs | mapping §5.3 (2026-10-04) — sender + recipient under active tunnel |
| Full OEM matrix (Pixel/AOSP/Samsung/Xiaomi/non-GMS) | **not in the rig** — honest gap recorded in mapping §5.3 |
| Production enablement (Step 15) | **owner-gated** |
