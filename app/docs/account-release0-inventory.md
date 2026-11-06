# Release 0 Repository Inventory — Optional Accounts / Encrypted Backup

Status: 2026-10-29. Deliverable of `app/plans/MyExodus_Revised_Implementation_Plan.md` §7
Release 0 step 1 / §8.1, extended with the step 2 implementation record (§10), the step 3
rules-hardening record (§11), the step 4 compatibility record (§12), and the step 5 deploy /
step 6 anonymous-cleanup records (§13–§14). Release 0 is deployed.

## 1. Plan-to-repository name map

The plan was written without the repository (§3). These are the shipped names; the plan's
names are implemented as compatibility adapters, not parallel collections (§4.3 permits this).

| Plan (§4.3) | Shipped in this repo | Note |
|---|---|---|
| `pairs/{pairId}` | `pairings/{pairId}` | Created only by `registerPairing` (Admin SDK). `members` array is the membership source. |
| `pairs/*/event_chain` | `mailboxes/{recipientUid}/messages/{messageId}` | Client-created today; relay trigger + per-installation acks + scheduled cleanup. |
| `pairs/*/system_events` | none | Backend-only notices exist as separate surfaces (`nudges`, DMS). A deterministic `system_events` subcollection is new in Release 0. |
| `pairInvitations/{id}` | `pairing_tokens/{sha256(token)}` | SAS/QR rendezvous; possession of the 256-bit token is the read capability. |
| `users/{uid}` profile | **absent** | Names live only in the local `UserProfile` entity (`AppRulesStore`) and `EnrollmentRecord.partnerLabel`. |
| `deviceTokens/{hash}` | `users/{uid}/push_installations/{installationId}` | Owner-write, relay-read. |
| `backupControl`, `backupManifests`, `backupReservations`, `guestTransitions`, `deletionJobs`, `eventRateLimits`, `serverFeatureGates`, `clientCompatibility` | **absent** | New in Releases 0–3. |

## 2. Verified build/deployment facts

| Item | Value | Evidence |
|---|---|---|
| Firebase project | `myexodus-53804` | `app/google-services.json` |
| Firebase Android BoM | 34.18.0 (auth, firestore, functions, messaging, config, crashlytics, appcheck-playintegrity; appcheck-debug in debug/androidTest) | `gradle/libs.versions.toml` |
| Room / SQLCipher | Room 3.0.3 · SQLCipher 4.18.0 | `gradle/libs.versions.toml` |
| Cloud Functions | `firebase-functions` 7.4.0 · `firebase-admin` 14.4.0 · Node 22 · region `us-central1` · 2nd-gen `onCall`/`onSchedule` | `functions/package.json`, `functions/index.js` |
| Function inventory | `registerPairing` (enforceAppCheck), `sendNudge`, `submitHeartbeat`, `setAvailabilityConsent`, `readPartnerStatus`, `recordIngestionOutage`, relay + DMS + cleanup schedules | `functions/index.js` |
| App identity | `com.example.myexodus` · minSdk 26 · targetSdk 36 · compileSdk 37 | `app/build.gradle.kts` |
| App version | `versionCode = 1`, `versionName = "1.0"` — **never bumped across builds** | `app/build.gradle.kts:22-23` |
| Signing | **No `signingConfig`**; release variant is unsigned, shipped installs are debug-signed | `app/build.gradle.kts` |
| Rules source | `firestore.rules` (244 lines, 2026-10-22). Deployed ruleset not compared live in this step. | repo |
| Firebase CLI | logged in (project-owner account); no `.firebaserc` — project comes from `google-services.json` | local CLI |
| Firestore cache | **memory-only already configured** (`MemoryCacheSettings`) | `AccountabilityModule.kt:189-192` |
| Legacy Firestore disk cache | shipped APKs before B-10 (2026-10-15) used default disk persistence; no purge exists yet | module comment |
| Storage | **no dependency, no usage** (Release 2 starts from zero) | `app/build.gradle.kts`, grep |
| Android backup | `allowBackup=false` + full database/sharedpref/file exclusions | `AndroidManifest.xml:59-61`, `res/xml/*` |
| App Check | Firestore enforcement ON (2026-09-19); `registerPairing` enforceAppCheck; identitytoolkit unenforced by design | project memory, `functions/index.js` |

## 3. Local storage inventory (matters for Release 1)

| Store | File | Engine | Contents |
|---|---|---|---|
| `AppRulesStore` v21 | `myexodus_rules_<uidHash>.db` (`myexodus_rules.db` retained 7 days as rollback copy) | Room + SQLCipher (Keystore-wrapped DEK) | rules, habits, recovery, journal, usage, user profile, partner-activity journal/outbox projections |
| `AccountabilityDatabase` v14 | `myexodus_accountability_<uidHash>.db` (`myexodus_accountability.db` retained) | **plain Room (FBE only)** | chain events/heads, enrollments, approvals, **E2EE outbox** |
| Activity log | `myexodus_activity_<uidHash>.db` (`myexodus_activity.db` retained) | raw SQLCipher | capped event queue (1,000) |
| Legacy combined | `myexodus.db` | SQLCipher | pre-split data; handled by `DatabaseMigration` |

Two consequences the plan assumes differently: the E2EE outbox is **not** in SQLCipher today
(plan §8.7 says it is), and Release 1's UID-scoped migration must cover **three** stores plus
Keystore aliases — not one database. **Done in step 7** (`LocalDatabaseMigration`, ADR #233):
scoped names/key aliases derive from a SHA-256 of the uid; the migration is journalled,
idempotent and crash-safe, and device-proven on the X6532 (Release 0 build → scoped files,
journal committed, legacy copies retained).

## 4. Current Firestore write surface (the complete client-write list)

| Path | Writer | Rules gate |
|---|---|---|
| `mailboxes/{recipientUid}/messages/{messageId}` | `MailboxOutboxDrainer` → `.set()` (`AccountabilityModule.kt:407-410`) — **Release 0: now `appendUserEvent` callable** | active pair, senderUid == auth uid, both members, field allowlist, payload ≤ 8192 chars |
| `.../messages/{id}/acks/{installationId}` | receiver (`AccountabilityModule.kt:560-561, 970-971`) | recipient only, `ackedAt == request.time` |
| `pairings/{pairId}` update | `PairingExchange.kt:281`, `BilateralDeletion` | member, active → terminated only, status field only |
| `pairing_tokens/{sha256(token)}` | `PairingExchange.kt:84, 173, 257` | any authenticated client; creatorUid pinned; expiry checked; single-use update |
| `users/{uid}/push_installations/{installationId}` | `PushInstallationRegistrar` | owner only, field allowlist, ≤ 4096-char token |
| `pairings/{pairId}/nudges/{nudgeId}` update | nudge inbox ack (`AccountabilityModule.kt:308-309`) | recipient only, ackedAt only |
| `pairings/{pairId}/nudgeSettings/{uid}` | `AccountabilityModule` | owner-member, active pair, two fields |

Everything else is denied explicitly or by default: `relay-dispatch`, `rate-limits`,
`idempotency`, `dms_install_state`, `dms_notification_outbox`, `dms_ingestion_outages`,
`nudgePushQueue`, `config/**`, `pairings/*/nudgeState`.

## 5. E2EE event/wire format (must be preserved)

- One outbox row per event; `messageId` (UUID) **is** the mailbox document id; retries send
  identical envelope bytes; document creation is create-only (replay = idempotent by id).
- Outer fields: `senderId` (sender chain key id), `senderUid`, `pairId`, `payload` (base64 of
  the serialized envelope, rules cap 8192 chars), `keyEpoch`, `timestamp == request.time`.
- Delivery is pull-based: watermark query on `timestamp`, per-message ack under
  `acks/{installationId}`, relay rejection marker `relayStatus` read back by the author,
  cleanup after all acks or 30 days.
- Release 0 `appendUserEvent` must accept this exact document shape (Admin SDK write) rather
  than inventing an `event_chain` format. §8.8's "couple with the existing E2EE handshake".

## 6. Pairing ceremony (must not be replaced)

`create pairing_tokens/{sha256(token)}` (initiator, enrollment payload) → partner single-use
`update` with `partnerUid/partnerPayload/partnerSignature` → both devices call
`registerPairing`, which verifies both proofs + hardware attestation and writes
`pairings/{pairId}` (`members`, `status=active`). Before Release 0 there was **no**
invitation-accept callable and **no** terminate callable; Step 2 added both (§10).

## 7. Gaps against the Release 0 gate

1. **Direct event writes allowed** — mailbox `create` is the live client path. Must move to
   `appendUserEvent` and deny direct create.
2. **Pair lifecycle not callable-mediated** — status flip is a direct client update; no
   `terminatePair`, no `system_events`.
3. **Invitation issuance is client-side** — no server 24-hour cap, no per-UID invitation
   rate limit, no SHA-only server record beyond the token-id hash.
4. **No profile documents** — `users/{uid}` root is absent and default-denied. The plan's
   "owner-read-only profile" and `appendUserEvent`'s ACTIVE-profile requirement need
   `initializeUserProfile` + first-launch/backfill in Release 0, or an explicit recorded
   deviation.
5. **No client compatibility surface** — and `versionCode=1` for every build makes a minimum
   version meaningless until a real version scheme is introduced.
6. **No server token buckets for event append** — only the relay's existing rate window.
7. **Deployed ruleset was unknown** — resolved in Step 5: the hardened rules were released
   2026-10-29 (§13); their postures are verified by the rules-test suite, not by a live
   direct-write probe (the App Check-enforced surface rejects an un-attested REST probe
   before rules evaluate).

## 8. Conflicts to resolve before their release

- **App Check:** the plan's operational gate (§17 #30, §10.17) says enforcement remains
  disabled; this project already enforces App Check on Firestore and on `registerPairing`.
  Do not disable it in Release 0. Record the deviation or reconcile the gate in Release 3.
- **Release signing:** the plan requires a "signed Release 0 APK"; there is no release
  signing config, and the direct-APK channel installs debug builds. Needs an owner decision
  before Step 5 (deploy), not before Step 2.
- **Outbox encryption:** Release 1's migration assumes a SQLCipher outbox; today it is plain
  Room under FBE.

## 9. Step 1 baseline test evidence (2026-10-29)

- `rules-test` → 53/53 pass, including four new deny-path tests:
  - default deny across `users/{uid}`, `backupControl`, `backupManifests`,
    `backupReservations`, `guestTransitions`, `deletionJobs`, `serverFeatureGates`,
    `event_chain`, `system_events`;
  - no-auth denial across pairing/mailbox/token/push paths;
  - `push_installations` owner-only writes + field allowlist;
  - `pairing_tokens` no-auth/expired denial + creator pinning.
- `functions` rules suite → 20/20 pass.

Commands:
`cd rules-test && npm test` · `cd functions && npm run test:rules`

## 10. Step 2 implementation (2026-10-29)

Server (`functions/`):

| Surface | Source | Behavior |
|---|---|---|
| `createPairInvitation` | `pair-lifecycle.js` | Admin write of `pairing_tokens/{sha256}`; ACTIVE-profile + deletion-tombstone gate; 24 h expiry ceiling; per-UID 10/hour window; identical replay is idempotent and uncharged; conflict → `already-exists INVITATION_ID_CONFLICT` |
| `respondToPairInvitation` | `pair-lifecycle.js` | Single-use, expiry, self-response and ACTIVE-profile gates; writes partner fields |
| `terminatePair` | `pair-lifecycle.js` | Member-only idempotent `active → terminated`; deterministic `pairings/{pairId}/system_events/pair_terminated`; deletion tombstone denied; missing pair = terminal success |
| `appendUserEvent` | `event-append.js` + `event-append-logic.js` | Only user-event write path. Exact outer-key allowlist, `USER_ENCRYPTED` record kind, 512 KiB base64 cap; replay-first (identical immutable fields → `replayed`, mismatch → `EVENT_ID_CONFLICT`); active pair, ACTIVE profile, no deletion tombstone, senderUid = auth uid; per-UID + per-pair token buckets charged in the same transaction |
| `initializeUserProfile` | `pair-lifecycle.js` | Idempotent ACTIVE profile create; never resurrects after a deletion tombstone |
| `getClientCompatibility` | `pair-lifecycle.js` | Reads `clientCompatibility/current`; deliberately unauthenticated and App Check-free so an outdated client can read it |
| `cleanupEventRateLimits` | `cleanup.js` | Daily schedule, deletes `eventRateLimits/*` untouched for 24 h |

Token buckets (`event-buckets.js`): per-UID 30 events / 1 MiB burst, 2 events/s, 10 MiB/min;
per-pair 60 / 2 MiB, 4 events/s, 20 MiB/min. Exhaustion → `resource-exhausted RATE_LIMITED`
with bounded `retryAfterMs` (≤ 60 s). Bucket documents live at `eventRateLimits/user_{uid}`
and `eventRateLimits/pair_{pairId}` (server-only).

Client:

- `MailboxOutboxDrainer` writes through `AccountCallables.appendUserEvent`; callable errors
  are translated into `FirebaseFirestoreException` codes so the drainer's existing
  classification (already-exists = sent, permission-denied = leave pending, else backoff) is
  untouched (`AccountCallables.kt`, `AccountabilityModule.kt`).
- `BilateralDeletion.deliverRequest` also goes through `appendUserEvent`.
- `PairingExchange` registers/responds/terminates through the three lifecycle callables;
  token reads/deletes stay direct (document id = SHA-256(token) is the possession capability).
- `PairUidResolver.ensureUid` calls `initializeUserProfile` once per uid per process
  (best-effort, retried on the next call) before returning a uid.

Deviations from §8.8, recorded deliberately:

1. **Token minting stays client-side**, 12 Crockford chars (60 random bits) with a 10-minute
   TTL, instead of a server-minted ≥128-bit/24 h token. Keeps the shipped QR/typed-code UX;
   the server enforces the 24 h ceiling, per-UID rate limit, single-use and pinning.
2. **`event_chain` is the existing mailbox collection** (`mailboxes/{recipient}/messages`);
   the plan's own §4.3 adapter rule permits this, and the wire format is unchanged.
3. **`system_events` currently holds only `pair_terminated`** — there is no other
   client-initiated system event in Release 0; all writes are Admin-only.
4. **App Check stays enforced** on every mutating callable and on Firestore (plan §17 #30
   says disabled; conflict recorded in §8 rather than disabling the existing control).
5. **No direct-write fallback** in the new APK: the callables must be deployed before or
   with the Release 0 APK, or outbox rows stay pending until they are.
6. **Invitation acceptance requires an ACTIVE profile, not a persistent/verified account** —
   Release 0 ships before accounts exist (Release 1), so the only enforceable account state
   today is the profile. The persistent/verified-account clause is inherited by Release 1's
   auth gate.

Evidence: `functions npm test` 166/166 · `npm run test:append` 9/9 ·
`npm run test:lifecycle` 15/15 · Android JVM suite 2,078/2,078.

## 11. Step 3 — rules hardening (2026-10-29)

`firestore.rules` now enforces the account plan §4.3/§8.2 client access matrix:

| Path | Client access |
|---|---|
| `users/{uid}` (profile root) | owner read; **no write** (server callables only) |
| `users/{uid}/push_installations/*` | unchanged owner read/write (shipped collection, not plan `deviceTokens`) |
| `pairings/{pairId}` | participant read; **create/update/delete denied** — the direct `active → terminated` flip moved to `terminatePair` |
| `pairings/{pairId}/system_events/*` | participant read; no client write (Admin SDK writes `pair_terminated`) |
| `mailboxes/{recipientUid}/messages/{messageId}` (event_chain) | recipient/author read; **create/update/delete denied** — `appendUserEvent` is the only append path |
| `mailboxes/.../acks/*` | unchanged recipient ack, author read |
| `pairing_tokens/{tokenId}` | possession-gated read/delete unchanged; **create/update denied** — minting and the partner response are callable-mediated |
| `clientCompatibility/current` | public read; no write |
| `deletionJobs/**`, `eventRateLimits/**` | explicit deny (Admin SDK only) |
| all other account/backup collections | default deny |

The partner-accountability kill switch (ADR #207) lived in the mailbox create rule; because
direct creates are now denied, the same live gate moved into `appendUserEvent` before the
profile/deletion gates (`permission-denied FEATURE_DISABLED`), after the replay check so a
committed event still replays while the switch is off.

Emulator evidence 2026-10-29: `rules-test` 46/46 (rewritten for the new postures) ·
`functions npm run test:rules` 10/10 · `npm test` 167/167 · `test:append` 10/10 ·
`test:lifecycle` 15/15 · Android JVM 2,081/2,081 in 230 classes.

## 12. Step 4 — compatibility publishing and the startup gate (2026-10-29)

- `versionCode = 2`, `versionName = "1.1"` — the first meaningful version; the compatibility
  gate compares against `versionCode`.
- New client behavior (plan §8.10): `MainActivity` reads `getClientCompatibility` once at
  startup (retryable from the screen). Below `minimumVersionCode` it shows the
  server-published `updateMessage` (local fallback if blank) on a blocking
  `UpdateRequiredScreen`. A failed read never unlocks an already-blocked client; the gate is
  a UX signal only — rules and callables enforce independently.
- Publication: `clientCompatibility/current` = `{ minimumVersionCode: 2, updateMessage }`
  written as an operator step at deploy (Admin SDK / console); clients never write it.
- Old clients that cannot read the gate fail closed: their direct mailbox creates, pair
  status flips and token mint/response writes now evaluate to permission-denied.

## 13. Step 5 — deployment record (2026-10-29)

Order followed (ADR #230): functions → APK → compatibility record → rules.

1. **Functions deployed** (`firebase deploy --only functions`, project `myexodus-53804`).
   Created: `appendUserEvent`, `createPairInvitation`, `respondToPairInvitation`,
   `terminatePair`, `initializeUserProfile`, `getClientCompatibility`,
   `cleanupEventRateLimits`; updated: 19 existing functions. The first attempt aborted with
   “User code failed to load … Timeout after 10000” (load-time CPU contention); the retry
   alone succeeded — the same source packaged and deployed.
2. **APK 1.1 (versionCode 2)** built, `install -r` on the X6532 (data preserved; never
   uninstalled). Post-install callable evidence from run request logs:
   `initializeUserProfile` 200, `submitHeartbeat` 200 (×2), `getClientCompatibility` 200; the
   home screen rendered normally with no update gate.
3. **`clientCompatibility/current`** written as the operator step:
   `{ minimumVersionCode: "2", updateMessage: "This version of MyExodus is no longer
   supported. Install the latest APK over this version, then tap Check again." }`.
4. **Rules released** (`firebase deploy --only firestore:rules`): “rules file firestore.rules
   compiled successfully” → “released rules firestore.rules to cloud.firestore”. The
   postures are covered by `rules-test` 46/46 (emulator); no live denial probe was run —
   Firestore is App Check-enforced, so an un-attested REST probe is rejected before rules
   evaluate.

App Check: the API-37 AVD's debug token was (re-)registered mid-run — its previous
registration did not match the AVD's actual secret (“Decoding App Check token failed”), which
accounted for 108 of the HTTP 401 rejections behind the old Cloud Functions “error rate”
alerts. Live registration:
`projects/913808045052/apps/1:913808045052:android:5fe57aa09fd14a279e3879/debugTokens/MzU5NzNiZmItNzEwNS00NGIxLTliNGItZTYzNTU0Y2ExNDJh`
(display name “AVD MyExodus_API37 (correct secret, 2026-10-29)”); a direct `exchangeDebugToken`
replay with the app's API key and signing cert returned a 942-char App Check token, and a
second registration listing on 2026-10-29 confirms the superseded entry is gone. App-side
re-verified at 19:13–19:14 after `install -r` of the 1.1 APK on `emulator-5554`: cold start
logged no “Failed to exchange debug token”, and an App Check–protected callable reached the
server (`submitHeartbeat` answered with the application-level `INSTALL_NOT_REGISTERED`, not a
401), which proves the in-app exchange now succeeds. That alert class is already retired at
the policy level (5xx-only, ADR #224); the registration removes the background 401 noise
itself.

Post-deploy observations:

- One `terminatePair` 403 (`NOT_A_PAIR_MEMBER`) per app entry from an orphaned local unpair
  row whose pair document was already `terminated` and whose members no longer include the
  rotated anonymous uid (ADR #125 identity rotation). Benign, one call/day; the row is
  re-tried by design and is Release 1 identity territory (unpair-journal cleanup).
- The APK update briefly unbound the accessibility service and fired a false
  “Enhanced Mode is off” alert (ADR #231); fixed with a delayed re-check and re-proven on
  the X6532: the 1.1 update posted 1004 once, the fixed rebuild installed at 18:44:51 posted
  none (events log, zero `notification_enqueue` for 1004).
- Cold-start ordering on a freshly installed AVD (19:14:13): the first `submitHeartbeat`
  ran one second before push-installation registration completed and got
  `INSTALL_NOT_REGISTERED`. The server requires `users/{uid}/push_installations/{installId}`
  (`functions/heartbeat.js:59`), while the client's guard only checks the local install id —
  so the first launch after install can spend one heartbeat on this. The next 15-minute beat
  should succeed once registration landed; not worth a client change in Release 0.

## 14. Plan §13.1 item 6 — monitoring before Release 1 (2026-10-29, ADR #232)

- **Firestore rules denials:** there is no server-side signal — Firestore writes no log entry,
  metric, or audit record for a write a rule denies. The client is therefore the only observer:
  the six direct client writes (nudge settings, nudge acks, push-install set/delete, mailbox
  acks, unpair acks) now report `firestore_denied cause=<op>` through the existing Crashlytics
  non-fatal rail before rethrowing; only `PERMISSION_DENIED` is reported, transport failures are
  not. Read it in the Crashlytics console (one issue class). Evidence: Robolectric
  `FirestoreTaskDenialTest` 2/2; full JVM 2,084/0/0 (2026-10-29).
- **Pair operation failures:** every callable already logs `<callable> rejected` with `reason`.
  Log-based counter metric `logging.googleapis.com/user/callable_rejections` (filter
  `jsonPayload.message=~"rejected$"`, label `reason`) now counts those handler-level
  rejections; App Check 401s are structurally excluded because they never reach a handler.
  Alert policy `projects/myexodus-53804/alertPolicies/9334481852107082372` — WARNING, fires at
  ≥20 rejections in 15 min, `autoClose 1800s`, owner email channel. Threshold is set above
  known-benign traffic (the daily `terminatePair NOT_A_PAIR_MEMBER` orphan; one
  `INSTALL_NOT_REGISTERED` per fresh install), so it detects storms/loops, not isolated
  failures; single rejections stay visible in CF logs, the dashboard and the in-app recovery
  surfaces. Verified: metric created via API and listed; its filter replayed against 8 real
  entries (including the 2026-10-23 `RATE_LIMITED` burst of seven in one second); a live AVD
  cold start produced a handler-level rejection at 16:31:47Z after the metric existed.
- **Honest limit:** the client event is a count on a best-effort rail (opt-out honored), not a
  rate; and a metric-based alert policy cannot carry `notificationRateLimit` (the API rejects
  it), so repeat emails are bounded by `autoClose` and one-incident-per-series.

## 15. Step 6 — anonymous auto-cleanup check (2026-10-29)

- `identitytoolkit` admin config for the project: `signIn.anonymous.enabled = true`, with no
  `autoDeleteAnonymousAccounts` setting — auto-cleanup is **off**, matching the plan's
  assumption that anonymous accounts persist.
- No Identity Toolkit admin-activity audit entries exist in the retained Cloud Logging
  history (other services' audit entries do, so audit logging is active). There is no
  evidence auto-cleanup was ever enabled, and nothing scheduled for deletion can currently
  be enumerated. Caveat: absence of evidence is bounded by the log retention window.
