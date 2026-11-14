# Reliable Partner Event Delivery — Operational Runbooks

*Plan §13.9 deliverable (three runbooks: transport/App Check outage, signing/recipient key loss, liveness false-positive). Every control and behaviour below is a source read of the shipped code on 2026-11-12, not an assumption. Customer-facing content never appears in these logs: events carry an event name plus a coarse bucket only (Decision 12).*

---

## 0. Control surface

| Control | Where it lives | Default | What it does |
|---|---|---|---|
| `delivery_ingest_v1_enabled` | Firestore `config/features` (server) | absent = enabled | Explicit `false` makes `ingestPartnerEvents` return retryable `unavailable / MAINTENANCE`. Client rows persist and retry. |
| `partner_notify_v1_enabled` | Firestore `config/features` (server) | absent = enabled | Explicit `false` pauses dispatch. Mailbox documents and queued notification jobs stay intact. |
| `device_liveness_alerts_v1_enabled` | Firestore `config/features` (server) | **absent = OFF** (worker returns `LIVENESS_V1_OFF`; an explicit `true` is required — plan §13 item 6 gates partner alerts on the overnight wake-probe tests passing) | While off, contact is still stored and enforcement notices still flow; no probes, absence alerts or reconnect notices are evaluated. |
| `config/device_liveness_incident` | Firestore document | absent = no incident | Declared fleet incident; absence-only alerts are suppressed with `suppressionReason = FLEET_INCIDENT`. |
| `partnerAccountabilityEnabled` | Firestore `config/features` | absent = enabled | Explicit `false` rejects legacy `appendUserEvent` with `FEATURE_DISABLED` (ADR #207 global kill switch). |

`config/features` is read fresh per function invocation. **The three client-side Remote Config flags (`delivery_producer_rules_v1_enabled`, `delivery_client_sync_v1_enabled`, `partner_push_registration_enabled`) were retired 2026-11-13 under #177 (ADR #171):** push registration, service-outbox sending and the staged producer are unconditional in every build from this removal onward, so the server switches above are the only operational controls. Older builds still installed keep whatever Remote Config values they last activated; those values are ignored by current builds. The pre-removal type trap (a String-typed parameter silently reading the in-app default) died with the RC SDK.

**Rollback rules (plan §13 item 7, non-negotiable):** pause transmission or dispatch only. Never disable security checks, delete the outbox, purge legacy aliases, or fabricate liveness state. Rows paused by any switch above are retained and resume unchanged.

---

## Runbook A — Transport or App Check outage, and rollback

### Detect

| Signal | Where | Reading |
|---|---|---|
| `partner_delivery_phase rule_producer=staged` | Crashlytics, one per enrolled periodic pass | Current producer stage; a constant `staged` since #177. A `legacy` sample can only come from a build older than the removal. |
| `partner_outbox_pending age=<bucket>` | Crashlytics | Any enrolled pass with a row still PENDING fires this; absent event = healthy. Sustained `over_24h` on online devices is the §14 alert condition. |
| `partner_waiting_recipient_key age=<bucket>` | Crashlytics | Oldest `WAITING_FOR_RECIPIENT_KEY` age; sustained growth is the §14 recipient-key-stale alert condition. |
| `partner_recipient_key_regenerated` | Crashlytics | One event per successful stale-key re-encryption (count metric). |
| `partner_t1_ms bucket=<bucket>` | Crashlytics | Write-attempt to Firestore-confirmed latency, versus the <1 s / <3 s targets. |
| `legacyUserEventAccepted` | Cloud Logging (`functions/event-append.js`) | `senderHash` + `installationIdentified`; counts legacy-path usage for the §13.5 compatibility window. |
| `partnerNotify` outcome logs | Cloud Logging (`functions/partner-notify.js`) | Claim/send/reconcile outcomes; `SEND_FAILED` and lease reclaims show FCM trouble. |
| App Check quota | Alert policy `MyExodus: App Check attestation volume above 70% of Play Integrity quota` | Rolling 24 h total of `firebaseappcheck.googleapis.com/services/verdict_count` across all services, threshold 7,000 (70 % of the 10,000/day Play Integrity quota) — fires on the first day over the line, which is stricter than the plan's three-consecutive-day review; the three-day rule is the escalation criterion, not the alert trigger. Baseline 2026-11-12: 3,237/day (32 %). |

### Immediate action for an App Check or callable outage

1. Confirm the outage is transport-side (every device calling the same endpoint fails) before touching switches. One device failing is a device problem, not an outage.
2. Leave Firestore/callable App Check enforcement **on**. Enforcement is the security boundary; disabling it turns an availability incident into a security incident.
3. Reduce quota pressure first: everything paused by the server switches in the rollback sequence keeps local recording and resumes losslessly.
4. If sustained quota above 70 % for three days, evaluate the documented options in plan §11 (a longer App Check TTL up to seven days per Firebase, quota-eligibility / Play listing, or an approved provider change) instead of allowing silent lockout.

### Rollback sequence (least disruptive first)

All remaining controls are server-side `config/features` switches (ADR #207). There is no client-side remote switch: a defective client build is recovered by fix-forward through the signed GitHub Releases channel (higher versionCode, same signing key), or by a signed `HALT` policy if it must stop being offered (ADR #171, `app/docs/app-update-channel.md`).

1. `config/features.delivery_ingest_v1_enabled = false` — transport-side pause; every client sees retryable `MAINTENANCE`, rows persist and retry, nothing is dropped.
2. `config/features.partner_notify_v1_enabled = false` — dispatch pause; mailbox documents and queued jobs are untouched.
3. Only if the global partner feature must stop: `partnerAccountabilityEnabled = false` (ADR #207, documented in `MyExodus Monitoring.md` §7). It stops new legacy sends only; nothing is deleted.

### Restore order

`delivery_ingest_v1_enabled` and `partner_notify_v1_enabled` on → confirm `partner_t1_ms` and outbox-age buckets return to healthy on the next pass. `partner_delivery_phase` reads `rule_producer=staged` on every enrolled pass from #177 onward, so it is no longer a rollout indicator; a `legacy` sample means that device runs a build older than the removal.

---

## Runbook B — Signing or recipient-key loss, and migration recovery

### Signing key lost, invalidated or mismatched

Symptom: the installation is marked `REENROLL_REQUIRED`; `PartnerEventDrain.run` returns `registrationRequired = true` from background passes. Every pending row is retained and re-signed after re-enrollment; no row is dropped or re-encrypted.

- **Recovery is automatic and foreground-only.** The next foreground drain (`allowRegistration = true`) registers a fresh key, calls `resumeAfterReenroll`, and the rows deliver. Background passes never register (§8.3.1).
- A user who never opens the foreground app holds rows safely; they are not lost.
- If key invalidation repeats (OS credential change, restore from backup), it surfaces as the same explicit re-enroll state, never a silent identity swap.

### Recipient key lost or rotated (partner re-enrolled)

Symptom: the server answers `RECIPIENT_KEY_STALE`, or rows park in `WAITING_FOR_RECIPIENT_KEY` and `partner_waiting_recipient_key` fires.

- Automatic: the drain re-encrypts from the retained sealed source **for the server's current authorized key set only** (a definitively no-effect rejection; §8.1). Empty authorized set parks the row `WAITING_FOR_RECIPIENT_KEY` and never re-encrypts for a revoked key.
- If the re-encryption reproduces exactly the key set the server just rejected, the row parks in `WAITING_FOR_RECIPIENT_KEY` (ADR #307) instead of resending the same answer. `SourceUnavailable` and regeneration failures park the same way. This bounds a futile stale loop to one probe per 900 s and is visible in `partner_waiting_recipient_key`; a sustained count therefore means "the key set is not converging", not "the phone is retrying".
- Waiting rows re-probe once per drain (900 s backoff). When the partner's new key appears, the row regenerates, re-signs and delivers in the same pass; `partner_recipient_key_regenerated` counts it.
- A row whose sealed source is gone (`SourceUnavailable`) cannot mint new bytes; it parks WAITING and a probe only succeeds if the seal becomes readable again.
- Sustained waiting-age growth on an online pair means the partner never re-registered a key: check their device's foreground enrollment, not the sender.

### Migration checkpoint and crash recovery (§13.4)

- Migration runs only when the vault is already open through the normal foreground unlock (`AppRulesStoreFactory.open()` throws `VaultLockedException` while the app-lock gate is closed). A locked vault never migrates; the mailbox document stays unreceipted and retries after unlock.
- Stable identity: the journal row's UUID becomes the service-outbox event id; a re-run after a crash re-reads the same row and the recipient feed's `(pairId, eventId)` primary key makes any redelivery a no-op.
- Originals stay in the journal until the corresponding service-outbox row is ACKed; the retention policy then cleans them. Do not hand-delete journal rows while debugging; a row with `hasEvent` already on the legacy chain is healed by deleting the journal copy without consulting the transport.

### Attestation root and revocation maintenance (linked, not duplicated)

Attestation verification, trust-root pinning and revocation follow the device-attestation decisions in `MyExodus Architecture Decisions.md` (attestation entries; enrollment/rotation validates the full chain, per-event verification reads cached validated keys). Platform references: Android Keystore attestation and security key attestation are linked in the plan §14 reference block. A root transition or revocation must land as a verifier update first; client behavior then follows through the signed GitHub Releases channel (fix-forward, or a signed `HALT` policy) since the client flags were retired (#177).

### Lost-device procedures (linked, not duplicated)

- **Lost sender device:** its keys stay on the device and are never re-derivable; a replacement install does not inherit them (restoration detection: `allowBackup="false"`, database/prefs/datastore excluded from cloud backup and device transfer). The pair must be torn down from the surviving side; bilateral unpair is leg 5 of `blocked-two-device-runbook.md`.
- **Lost partner device:** block or reconfigure nudges per pair in `partner-sos-runbook.md`; mailbox documents remain encrypted to the lost key and undecryptable without it (by design).
- **Account deletion** is the only path that removes mailbox, push-registration and outbox data server-side; see `MyExodus Identity & Account Transition.md`.

---

## Runbook C — Liveness false-positive and fleet incident response

### Thresholds in force (plan §11)

Evaluation every five minutes **while `device_liveness_alerts_v1_enabled === true`** — the shipped default is OFF (`functions/device-contact.js` returns `LIVENESS_V1_OFF`), so liveness evaluation and partner alerts stay dormant until the rollout explicitly enables them after the overnight wake-probe tests (plan §13 item 6). Contact storage is independent of the flag. Probe threshold 90 minutes of stale contact; probe response window 20 minutes; default partner-alert grace 240 minutes (360 in configured sleep windows). A false alert requires one probe miss past the full grace window.

### Diagnose before acting

1. Read the pair's contact state; a device that answered its probe inside 20 minutes produces no outage notice, so a false alert means the grace math was crossed incorrectly or contact writes stopped fleet-wide.
2. Fleet-wide contact loss at the same time is a service incident, not partner behavior. Check function logs for `device-contact` / `submitDeviceContact` errors before anything else.
3. Normal recovery is automatic: verified later contact produces one `DEVICE_RECONNECTED`.

### Declare a fleet incident (suppresses absent-device alerts)

1. Write `config/device_liveness_incident` with the incident window (see `functions/device-contact.js` `INCIDENT_DOC` validation). While present, absence-only decisions suppress with `suppressionReason = FLEET_INCIDENT`; a recovery that happened before dispatch is recorded as `RECOVERED_BEFORE_DISPATCH`.
2. To stop dispatch entirely during the window, set `config/features.device_liveness_alerts_v1_enabled = false`. Contact is still stored, so nothing downstream can mistake the pause for tamper or absence.
3. Never fabricate `DEVICE_RECONNECTED` or edit partner-visible state to "undo" an alert. The incident document and the pause flag are the supported controls; the next real contact closes the episode honestly.
4. After the window: remove the incident doc, re-enable the flag, and verify the next contact updates state and produces at most one reconnect notice.

### DLQ handling (`dms_notification_outbox`)

- Jobs are claimed with a lease; a killed process leaves the row LEASED and the every-minute sweeper reclaims it after lease expiry.
- After `MAX_ATTEMPTS = 8` (`functions/partner-notify-logic.js`) the job becomes `FAILED_PERMANENT` and is never re-armed automatically; the next real event creates a new job. Terminal rows stay for diagnosis and are removed by account deletion.
- Inspect with a `status == 'FAILED_PERMANENT'` query on `dms_notification_outbox`, correlate the `partnerNotify` outcome logs by `jobId`, and confirm the partner's push registration is current before assuming a delivery bug.
- A duplicate dispatch is harmless: notice ids are deterministic and recipient-side duplicate suppression keys on them; do not hand-delete LEASED rows to "retry faster" — that is what the lease expiry does.

---

## Compatibility window (§13.5)

- **No legacy purge in this release.** Legacy aliases, endpoints and the legacy `appendUserEvent` path stay deployed. Old APK installs that omit `installationId` remain accepted; a sender that identifies a registered installation is refused on the legacy path with `failed-precondition / SIGNED_TRANSPORT_REQUIRED` (§13.3), so a migrated installation never moves backward.
- **Scope of the staged producer today:** every partner-visible producer stages through the signed transport (commit `f93f3e0`, 2026-11-12): rule lifecycle (`RULE_CHANGE_ATTEMPT`), activity rows — streak updated/shared/renamed/deleted, relapse, milestone, coaching step, motivational message, streak and check-ins share revokes — as `ACCOUNTABILITY_UPDATE`, block attempts as `BLOCK_ATTEMPT`, and availability snapshots as `MONITORING_UPDATE`. `PartnerEventProducer` never returns `LEGACY` once wired; the legacy chain branch remains only as an unreachable-by-DI fallback. The legacy `appendUserEvent` path stays deployed for pair-protocol traffic (unpair request, approval request/response, sync request) and for old APK installs.
- **Documented window (project policy):** the window closes for a given kind only after (a) the staged producer has been the shipped transport for every build that carries it (unconditional since #177, 2026-11-13), and (b) the `legacyUserEventAccepted` log shows no unmigrated senders for 30 consecutive days. Cleanup of legacy aliases then ships as a separate release, never as a flag flip.
- **Server metric:** `legacyUserEventAccepted` with `senderHash` and `installationIdentified` is the compatibility-window counter. `installationIdentified = true` on an accepted legacy event is a routing bug; investigate immediately.
