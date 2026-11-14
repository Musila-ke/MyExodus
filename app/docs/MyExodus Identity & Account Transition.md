# MyExodus Identity & Account Transition

**Status:** Planning inventory — recorded 2026-10-23 at the owner's request. No auth code is
implemented by this document; it is the checklist the authentication/identity work must satisfy.
**Revised 2026-10-29:** owner restated the feature (Google + email/password, optional accounts,
perks, Firebase-sourced partner names, uid preservation, deletion); gap audit added A9 (email
verification/password lifecycle), A10 (provider availability), D6 (name distribution), the G5
backup-scope classification, §8 (perk candidates), and corrected the ground-truth anchors.
**Owner mandate (2026-10-23, gist):** once Google or email/password sign-in exists alongside the
anonymous default, "certain things in the application need to change, certain structures … need to
change"; find what those are, what is affected, what must be modified or added, and do not miss
anything.
**Canonical companions:** OPD #6 (account identity & comms), #30 (identity-loss diagnosis),
#36 (sign-in/account model), #37 (name-the-person cases); ADR #125 (identity rotation guards).
**Release 0 status (2026-10-29):** plan steps 2–4 are implemented and test-green but **not
deployed** — server callables (`appendUserEvent`, invitation create/respond, `terminatePair`,
`initializeUserProfile`, `getClientCompatibility`), server-owned event token buckets, the
client routed through them, hardened rules (profile owner-read, direct event/pair/token
writes denied, system events read-only), the kill switch moved into `appendUserEvent`,
`versionCode = 2` / `versionName = "1.1"`, and the startup update-required gate. Deploy
(step 5) and the anonymous-cleanup check (step 6) are still open. Record: ADR #229, ADR #230
and `app/docs/account-release0-inventory.md` §10–§12.
**Release 1 status (2026-10-30):** step 7 (UID-scoped databases, ADR #233) is implemented,
JVM- and emulator-verified, and device-run on the X6532. Step 8 (auth core, ADR #234) is
implemented and JVM-verified: `AuthCoordinator` + Credential Manager Google + email/password,
verification and reauthentication, surfaced as Settings → Account. Step 9 (guest transition,
ADR #235) and step 10 (session fencing / Firestore–FCM teardown / one-time legacy purge,
ADR #236) are implemented, JVM-verified and device-run on the API-37 AVD. Step 11 (encrypted
quarantine + additive logical guest import with a crash-safe journal, ADR #237) is implemented,
JVM-verified (2,187 tests) and device-run on the API-37 AVD (IMPORT and DISCARD both proven
end-to-end; evidence in ADR #237). Release 2 (backup/restore) is
implemented and JVM-verified (ADRs #238/#241/#242). §6.2.8's import-vs-backup conflict
machinery — the obligation ADR #237 recorded for #38/#42/#37 — is implemented (ADR #246):
every import path now checks the target's cloud pointer first, refuses to merge into a
database that is not the current snapshot, and surfaces IMPORT_PENDING (restore first, empty
target) or the §6.4 item 8 choice when the target holds local rows — restore then merge both,
merge into this phone only, or decide later — with a hard stop on rollback/equivocation.

---

## 1. Ground truth: the identity model today

| Concern | Today's mechanism | Code anchor |
|---|---|---|
| Identity | Silent Firebase **anonymous** uid; single-flight so one install mints exactly one account | `PartnerSyncCoordinator.kt` `PairUidResolver.ensureUid()` |
| Membership | `/pairings/{pairId}.members` is an **array of uids**; rules gate reads to members | `FirestoreSchema.kt` |
| Mailbox | `/mailboxes/{uid}/messages/{id}` — uid is the address | `FirestoreSchema.kt` |
| Push | `/users/{uid}/push_installations/{installationId}` | `FirestoreSchema.kt`, `PushInstallationRegistrar` |
| Nudges | `/pairings/{pairId}/nudges/{nudgeId}`, settings at `/pairings/{pairId}/nudge_settings/{uid}` | `FirestoreSchema.kt` |
| Callables | Every function requires `request.auth`; anonymous counts as authenticated | `functions/dms.js:36`, `partner-sos.js:40`, `heartbeat.js:48`, `index.js:72` |
| Pairing trust | Signed enrollment transcript + **Android Key Attestation** (TEE/StrongBox, Google roots) verified **server-side** | `functions/attestation.js`, `PairingProof.kt`, `AttestationVerifier.kt` |
| Device keys | Non-exportable Keystore keys; signing key is user-authentication-gated today (NFR4; owner ruling 2026-10-23 supersedes this for routine signing — see task #64); agreement keys auth-free | `AccountabilityKeyStore.kt` |
| Identity loss | Reinstall / clear-data / abandoned token → new uid → every pair orphans; guards detect and diagnose, never recover | ADR #125, `PairingReachabilityStore`, OPD #30 re-pair card |
| Key invalidation | `IdentityMigration.migrate(rotateKeys=true)` destroys keys, bumps epoch, severs all pairs | `IdentityMigration.kt` |
| Names | **Self-chosen labels only.** My own name = `user_profile.name` (default "You"), sent as my pairing label; the partner's name = a label **I typed**, stored on-device in `EnrollmentRecord.partnerLabel` (renamable via `updatePartnerLabel`). **The server stores no names.** | `RulesRepository.updateProfileName`, `PairingFlow.initiate/join(myLabel)`, `PartnerNotifications.displayNameOf` |
| Local profile | Fixed row `user_profile` id `"local"`, name `"You"`, email `""` — a placeholder, never linked to the uid; also carries the permission flags | `blocking/RulesRepository.kt:148-177`, `data/db/entity/UserProfile.kt` |
| Behavioral data | Rules, usage, streaks, enrollments are **device-local** (Room/SQLCipher). Only consented partner events travel (signed relay + DMS snapshots) | `AppRulesStoreFactory.kt`, partner sync |
| Deletion | Account-level deletion exists (ADR #244) and now reaches the partner's copy: the server's `terminationReason` on the pair document drives the partner device to shred and show Ended (ADR #280, OPD #43). Pair-level bilateral deletion remains the in-app unpair path | `DeletionCoordinator`, `BilateralDeletion.kt`, `PartnerSyncCoordinator` |

Consequences that follow directly: identity is device-bound, not person-bound; a reinstall is an
identity loss; the partner cannot verify who the uid belongs to beyond the pairing transcript.

## 2. Invariants any account model must preserve

1. **uid continuity on upgrade.** `linkWithCredential` upgrades an anonymous account in place — the
   uid is preserved, so pair memberships, mailboxes, installations and nudges all survive. The
   transition must use it; it must never mint a fresh identity as the "upgrade" path.
2. **No uid merging.** Firebase cannot merge two existing accounts. Signing into an account that
   already owns pairs on a device whose anonymous uid owns other pairs is a *choose one* operation;
   it must be explicit, never silent.
3. **Device attestation stays the pairing trust anchor.** An account proves continuity of the
   person; it must not replace hardware key attestation for pair registration or message signing.
4. **No plaintext behavioral-data egress.** The owner-ruled direction (2026-09-30, OPD #6) is an
   **app-encrypted backup blob** under the account's uid for logical data (scope in G5: habits,
   streaks, milestones, day log, recovery content, settings — never block rules or device state);
   the server never sees plaintext. Backup is not a by-product of sign-in: it needs its own design
   (and the open key-recovery piece), and runtime accounting stays device-local exactly as today.
5. **High-risk actions stay gated.** Approve/deny, unpair, sharing-scope changes keep the
   biometric gate (owner ruling 2026-10-23). Routine updates become automatic after consent
   (task #64) — an account must not become a second way to impersonate the device key.
6. **Single identity per install.** One active identity at a time (guest or signed-in); switching
   is an explicit, destructive-after-confirmation operation. No background account switching.
7. **Names never expose a uid, email or phone** to a partner — only a display name.
8. **No core capability requires an account.** Guest mode stays fully functional (pairing,
   blocking, metering, streaks, SOS, recovery); account perks are additive, never gates.

## 3. Change inventory (checklist for the auth build)

Every item must be resolved with a decision when auth work starts: Required / Deferred / Rejected
with a reason. "Where" names the primary surfaces, not every call site.

### A. Auth core (client)
- **A1. Auth state model replaces anonymous-only sign-in.** Add explicit guest / linked / signed-in
  states with provider + verification status. `ensureUid()` must stop minting an anonymous uid
  before the user has chosen "continue anonymously". *Where:* `PartnerSyncCoordinator.kt`
  (`PairUidResolver`), `di/AccountabilityModule.kt`, new auth repository.
  **IMPLEMENTED (R1.8, 2026-10-29, ADR #234):** `AuthState` (Initializing / SignedOut /
  GuestActive / PersistentActive / Authenticating / ReauthRequired / AuthError) is driven by
  `AuthCoordinator` over an `AuthOperations` seam (`FirebaseAuthOperations` in production).
  Pair operations still mint an anonymous uid lazily; guest remains the default and no pane
  implies an account is required.
- **A2. Upgrade path = link, with collision handling.** Anonymous → email/Google via
  `linkWithCredential`; on `credential-already-in-use`, offer sign-in-to-existing vs cancel and
  state that cancelling keeps the guest identity. Never silently orphan either side.
  **Link ≠ sign-in:** linking keeps this install's data and pairs (uid preserved). Signing into an
  existing account from a guest install replaces the guest identity — an explicit choose-one whose
  local consequence is the G2 wipe; the guest's pairs remain reachable only by its old uid and are
  otherwise abandoned. Never silent.
  **IMPLEMENTED (R1.8, 2026-10-29):** guest + email/password or Google = `linkWithCredential`
  (uid preserved, proven in `AuthCoordinatorTest`). Collision surfaces the plan's neutral copy
  via `AuthEvent.Collision`; the coordinator never signs out, unlinks or mutates the guest on
  a collision (JVM-proven). Signing in to a *different* account from a guest is refused with
  `TRANSITION_REQUIRED` until the R1.9 guest-transition coordinator sets the switch allowance.
  **IMPLEMENTED (R1.9, 2026-10-29, ADR #235):** the switch path is live — the guest pane's
  sign-in/Google opens the plan's disclosure (Import my progress / Discard guest progress /
  Stay a guest); only a confirmed choice calls `GuestTransitionCoordinator`, whose encrypted
  journal is written before the prepare callable and replayed (same id/capability) across a
  lost response, process death, a typo'd password or a reauth detour. Pairs stay untouched
  until finalize runs under the target session. Device-proven both branches (IMPORT and
  DISCARD) on the API-37 AVD with server state FINALIZED and cleanup jobs QUEUED.
- **A3. Sign-out semantics.** Confirmation must list consequences: partner updates stop, pending
  outbox stops, local sensitive data is wiped after confirmation (G2), push installation is
  deregistered (E8); server-side pairs and any encrypted backup remain for a later sign-in. The
  next partner operation mints a fresh guest uid (ADR #125 path), so the old identity is
  recoverable only through the account.
  **IMPLEMENTED (R1.10, 2026-10-30, ADR #236; plan §13 item 13 supersedes the G2 wipe as the
  default):** ordinary sign-out retains the encrypted local database by design. `beginTransition`
  fences every remote operation (`LocalSessionManager` / `SessionFence`), flushes pending
  Firestore SDK writes (refusal → explicit discard-and-retry), increments the session generation,
  persists the signed-out choice, and deletes the push mapping while the old UID still owns the
  token; completion cancels account-scoped work and restarts the process. A signed-out install
  never mints a guest; signing in again reopens the same retained scope. Explicit local-data
  removal with unsent/unbacked-up confirmation is **IMPLEMENTED (R3.16, 2026-10-30, ADR #244)**:
  the signed-in pane offers "Remove local data"; account deletion additionally offers "Also
  delete all data on this device" (both credential paths), which marks every scope instead of
  only the account's. The signed-out pane offers removal whenever a launch would adopt any local
  scope: the switch pointer, the migration journal, or any scoped store found on disk
  (`DeletionLocalData.hasLocalData()`), and the action purges all of them. Pointer-only detection
  was a real defect: the deletion purge clears `active_scope_uid_hash`, so a live journal-named
  store was hidden while the app reopened it at the next launch (X6532, 2026-11-07). The journal
  hash is this install's original local/guest store, never "your last account". The dialog names
  unsent outbox events and the last known backup revision before the destructive button.
  Sign-out deletes no key; only this removal action deletes the encrypted database and its
  UID-scoped Keystore alias.
- **A4. Account switching.** Recommended: single account per install; switching wipes local data and
  re-syncs from the account. Multi-account profiles would require a Room schema change and are
  rejected unless a real need appears.
  **IMPLEMENTED — server + client half (R1.9, 2026-10-29, ADR #235):** the prepare/finalize/cancel
  callables (transactional lease, rate window, kill switch, capability hash, idempotent replay)
  and the client coordinator/UI are live and device-proven. The switch teardown is R1.10
  (ADR #236): the old session is fenced, flushed, generation-bumped, its push mapping removed,
  and the process restarts; the previous scope's database and key alias are **retained** on disk
  under their own scope, so the new UID can never open them (device-proven: a guest-only marker
  habit was absent after switching into the account). The additive guest import (R1.11, #40) is
  what moves guest data into the account; until it lands, finalize terminates pairs and queues
  the cleanup job without moving data.
- **A5. Dead-token recovery diverges by identity type.** ADR #125's rotate-to-new-anonymous-uid
  stays guest-only; a signed-in user is sent to re-authentication instead of rotating. The
  re-pair card gains a "Sign in" remedy (C2).
- **A6. Reauthentication for sensitive actions.** Account deletion (E2) and identity-changing
  actions require a fresh credential; biometric signing gates remain independent and unchanged.
- **A7. Provider setup for the direct-APK channel.** Email/password + Google enabled in the
  Firebase project; email verification; password policy; email-enumeration protection; OAuth
  client IDs for a non-Play install. Verify against official Firebase docs before implementation.
  **OPEN — deployment checklist (R1.8, state verified via the Admin APIs 2026-10-29):**
  client code is ready; the providers themselves are console state. Confirmed live: Email/Password
  and Anonymous providers enabled; email-enumeration protection on
  (`emailPrivacyConfig.enableImprovedEmailPrivacy = true`); action links resolve on the Hosting
  domain (`mobileLinksConfig.domain = HOSTING_DOMAIN`, default site `myexodus-53804`;
  authorized domains `localhost`, `myexodus-53804.firebaseapp.com`, `myexodus-53804.web.app`);
  a Firebase **web app `auth-action-web`** (`1:913808045052:web:fef25b7e24e2f54b9e3879`) exists so
  the action-link handler has a web client — verification links previously had no valid action
  target. Still owner-side: **(a)** `GOOGLE_WEB_CLIENT_ID` is blank in `app/build.gradle.kts`, so
  Google stays hidden and email/password is the only live provider until a Web application OAuth
  client is registered (SHA-1 for the signing certificate) and pasted in; **(b)** notification
  email still uses Firebase's default shared sender (`notification.sendEmail.method = DEFAULT`) —
  verification mail lands in **Gmail Spam**; a custom SMTP relay on an owned domain is a
  **release blocker for the verification flow**, not a code change; **(c)** confirm the
  "one account per email address" linking policy recorded in `account-release0-inventory.md`'s
  console checklist.
- **A8. Offer timing.** Anonymous remains the default (OPD #6 direction). The account offer belongs
  before pairing (highest value) and in Settings as "Secure your identity" (OPD #36).
  **PARTIAL (R1.8):** Settings → Account exists (first row of the Settings index, state-derived
  subtitle). The pre-pairing offer has not been built.
- **A9. Email verification and password lifecycle.** Decide what an unverified email may do
  (recommend: all core use allowed, verification prompted, required for email change and account
  deletion) and for how long. Password reset while signed out (email link/code, generic copy so
  the flow cannot enumerate accounts) vs change while signed in (recent-login reauthentication,
  then invalidate other sessions). Firebase-hosted templates/action pages; a direct-APK build
  needs no app deep link for reset, but the sending domain and templates must be configured.
  Email change is Deferred unless the plan calls for it; if added it requires recent
  reauthentication and re-verification.
  **IMPLEMENTED (client, R1.8):** verification send/confirm (confirm reloads the Auth record and
  force-refreshes the token before any protected call) and reset with generic copy — a reset for
  an unknown address reports success, so the flow cannot enumerate accounts. Unverified accounts
  keep full core use; verification is prompted in the pane. Password *change* while signed in
  and email change remain unbuilt (recent-login reauthentication exists in `AuthCoordinator`).
  **END-TO-END VERIFIED 2026-11-13** (Lean_API34, release v31 / 1.1.29 — the first live run; no
  prior test evidence existed): invalid text → inline "Enter a valid email address." with no
  network call; a valid-format address with no account → the neutral `account_reset_sent` card
  (no existence oracle); a real account's address from the app → Firebase delivered the reset
  email to a mail.tm inbox within seconds; the emailed `oobCode`, redeemed via
  `accounts:resetPassword`, answered `PASSWORD_RESET` for the account; the old password then
  returned `INVALID_LOGIN_CREDENTIALS` and the new one signed in with the same uid. Test user and
  mailbox deleted afterwards. Note for testers: while the request is in flight the pane swaps to
  the working card and the form returns reset to its defaults, so the confirmation card is the
  only on-screen evidence — it holds for 8 s at the top of the pane.
- **A10. Provider availability across OEMs.** The app targets every Android skin; devices without
  Google Play services cannot use Credential Manager Google sign-in. Detect availability, hide the
  Google option and fall back to email/password — never dead-end (OPD #6 already records that the
  email/password provider itself needs no Play services). Verify the exact Credential Manager +
  Firebase ID-token flow (web/server OAuth client id, no Play install referrer) against official
  docs before implementation.
  **IMPLEMENTED (R1.8):** Google is offered only when a Web Client ID was compiled in (today:
  never). A missing Play-services provider, a missing credential and any other picker failure
  return `PROVIDER_UNAVAILABLE` while email/password stays visible; user cancellation returns
  silently with no browser fallback. The nonce contract was verified against the official
  Credential Manager guide: 32 random bytes as unpadded base64url passed to
  `GetGoogleIdOption.setNonce`, and the returned ID token's `nonce` claim must be identical —
  Firebase does not check it, so a mismatch is rejected before Firebase sees the token.

### B. Device keys, attestation, recovery
- **B1. New-device enrollment protocol.** Signing in on a *new* phone restores the uid but not the
  device keys. Pair verification on the partner side names the **old** signing key, so a fresh
  device must re-enroll its attested key and the partner's device must accept it under an explicit
  rule (partner approval vs trust-on-first-use). Without this, "sign in on a new phone" restores
  membership but not working signatures.
- **B2. Multi-device policy.** Decide whether one account may run several devices. If yes:
  per-device enrollment records and key ids, and every partner-facing surface names the person,
  never the device (OPD #37). If no: the new device replaces the old one and the old device is
  revoked.
- **B3. Epoch and key-invalidation paths.** `IdentityMigration` must keep severing pairs when keys
  are invalidated; copy distinguishes "keys reset" from "signed out". A linked account must not
  skip the epoch bump.
- **B4. Key destruction on sign-out and account deletion.** `destroyAllKeys()` + epoch bump + a
  relationship-exit signal to the partner, so a removed device cannot sign anything afterwards.
- **B5. Attestation unchanged.** `functions/attestation.js` and `AttestationVerifier.kt` stay the
  gate; account identity may later add an *additional* proof, never substitute one.

### C. Pairing and consent
- **C1. Pairing flow.** Code + signed transcript stays uid-based and provider-agnostic; no change to
  `PairingProof` for accounts.
- **C2. Orphan diagnosis gains recovery.** `PairingReachabilityStore`'s re-pair card gets a
  "Sign in to restore" action for account holders; guests keep the re-pair flow. A uid mismatch after
  sign-in must explain *which* identity the pairing belongs to without leaking the raw uid.
- **C3. Consent copy names the person.** Pair-creation sharing panes identify the partner by name
  (account name > typed label), and per-pair sharing scopes (`pair_profile_sharing`) are unchanged.
- **C4. Unpair/deletion behavior.** Bilateral deletion semantics stay as today for both identity
  types.
- **C5. Accounts stay optional for pairing** (owner direction, OPD #6). Do not make an account a
  prerequisite.

### D. Names, profiles, partner-facing identity
- **D1. Account profile.** A `users/{uid}` profile document (display name; email kept private) with
  Firestore rules. The local `user_profile` row becomes its mirror (G1).
- **D2. Name precedence.** Partner-facing name = account display name → self-chosen pair label →
  short key id fallback. Renames propagate to partner surfaces; the generic fallback is only for
  identities that legitimately have no name.
- **D3. OPD #37 cases under accounts.** (1) nudge notifications — **shipped 2026-10-23**: the push
  is data-only and carries `pair_id`; the client resolves its local partner label and names the
  sender (generic copy on the lock screen and as fallback) — mapping D16, ADR #222; under accounts
  the label resolves from the profile first (D2). (2) DMS availability/silence notices (server fixed copy — naming requires
  either server-held names or a client-resolution design; decide the privacy trade-off; the LATE check-in reminder this item
  covered is retired as of ADR #223 — check-in is a silent probe, not a notice);
  (3) every client notification with multiple partners (correct person per pair, survives rename);
  (4) deep-link/label copy ("your partner's device") audited per-pair; (5) system channel names stay
  per-app, not per-person (accepted constraint); (6) lock-screen visibility ruling for names.
- **D4. No PII exposure.** Partners see names only — never email, phone or uid.
- **D5. Anonymous users keep typing a label** at pairing; accounts default it from the profile.
- **D6. Name distribution and offline behavior.** The partner's client must obtain my account
  display name without exposing email/uid. Choose: a profile doc readable by current pair members
  (rule-gated) vs a name snapshot denormalized onto the pairing/enrollment record; define rename
  propagation (signed event vs re-read), a last-known-name cache so offline partners still see a
  name, and generic copy when none is known. Google sign-in prefills the display name; email
  sign-up asks for one; both remain editable and partner-visible. Sanitize account names with the
  existing `PartnerLabel.sanitize` before any partner-facing render.

### E. Backend and server data
- **E1. Callable contract unchanged where possible.** `request.auth.uid` remains the identity key;
  new checks only where account state matters (e.g., deletion).
- **E2. Account deletion (new).** A callable (or scheduled flow) that reauthenticates, then purges:
  pairings + partner notification (Req 24/29), mailboxes, push installations, nudges, DMS state,
  the encrypted backup blob (G5), retention/scheduler references — then `deleteUser`. It deletes
  the **Firebase account only, never the Google account**; the confirmation copy must say so.
  Local data and keys are wiped on the deleting device afterwards (B4/G2). Cover `cleanup.js`,
  `mailbox-retention.js`, `partner-sos-scheduler.js`, `dms-processor.js`. Today only pair-level
  deletion exists.

  **IMPLEMENTED (R3.16, 2026-10-30, ADR #244).** `requestAccountDeletion` / `requestGuestDataDeletion`
  (callable, App Check, 60 s) verify a raw token with revocation checking, `auth_time` ≤ 300 s, a
  uid match, a verified email on password identities and a persistent account (guests: anonymous
  only), then write `deletionJobs/{uid}` (`ACCOUNT_DELETE` / `GUEST_DELETE`) and the profile's
  `PENDING_DELETION` in one transaction before any side effect. `processAccountDeletion`
  (`onTaskDispatched`, 12 attempts, 300 s) re-runs disable-and-revoke on every pass and advances
  QUEUED → PAIRS → STORAGE → PRIVATE → PROFILE → AUTH → COMPLETED, paginating pairs, scrubbing
  names from the deterministic system events, recursively deleting private subcollections,
  mailboxes and reservations, and treating Auth user-not-found as success. COMPLETED requires a
  final `verifyPurge`; `reconcileDeletionJobs` (*/15 min) re-enqueues stale jobs and deletes a
  COMPLETED tombstone only after 30 days; `onAuthUserDeleted` writes an `AUTH_DELETION_BACKSTOP`
  job. The client half (`DeletionCoordinator` / `DeletionLocalData`) reauthenticates, calls the
  callable, writes the local purge marker, signs out, and purges the marked identity's scoped
  stores, retained copies and UID-exclusive aliases only while signed out, clearing the marker only
  after every delete succeeds. The same reconciler also converges the two §14 Auth/profile
  mismatches (R3.17, ADR #245): a COMPLETED tombstone whose Auth user exists again is replaced
  atomically by a fresh QUEUED job, and a `PENDING_DELETION` profile with no job gets the missing
  job — both alert through `auth_profile_mismatches`. Device-proven on the disposable API-37 AVD (three COMPLETED jobs,
  client Working → sign-out → relaunch → purge); every identity-destructive leg stays off the
  X6532 by owner ruling. The two-phone partner leg now exists: a terminated pair carrying the
  account-deletion worker's `terminationReason` drives the partner device to shred locally and
  show Ended on its next sync pass (ADR #280, OPD #43), device-proven 2026-11-08 on the X6532C
  against a live `ACCOUNT_DELETED` pair. Honest limits: the 30-day tombstone is retained
  metadata by design, a callable cold start can outlive the client's 60 s window (the job, not
  the client, is authoritative), and the partner purge covers the pair's cached content, not a
  partner that never syncs again.
- **E3. Firestore rules.** New profile doc; keep member-gating; keep App Check enforcement on
  Firestore (identitytoolkit stays unenforced by design).
- **E4. Server-held names (only if DMS copy must name the person).** Minimal profile fields fetched
  at send time; DPIA and lock-screen review first.
- **E5. Email channel.** Firebase Auth templates cover verification/password reset once an email
  account exists; welcome/tips/deletion notices need a provider (OPD #6b).
- **E6. Abuse controls.** Account creation rate limits, email-enumeration protection, and the
  existing pairing rate window stay.
- **E7. Telemetry hygiene.** Never log email, display name, uid or token; Crashlytics stays
  metadata-only (Decision 12).
- **E8. Push ownership.** On sign-out/switch, delete this installation's `/users/{uid}/push_installations`
  doc and clear the local token so a signed-out device cannot receive the previous account's
  partner content. A token re-registered under the new uid must not leave the old doc behind.

### F. Notifications
- **F1. Named copy client-side once labels/names exist** — already the mechanism for client-posted
  events; extend to account names when D2 lands.
- **F2. Lock-screen privacy ruling** (owner decision): whether a partner's name appears on the lock
  screen; if not, names must be resolved at post time only under an unlocked/session state.
- **F3. Multi-partner correctness.** Every named notification names the correct person for its pair;
  rename updates; generic only when no name exists.
- **F4. Channels stay generic** (`MyExodus partner activity`, etc.) — accepted constraint.
- **F5. Email is a separate channel** from push; account emails never carry partner data.

### G. Local data
- **G1. `user_profile` row becomes the account mirror.** Keep the single active row (id `"local"`)
  and mirror name/email from the account into it; do not key it by uid — that would force a Room
  migration and only matters if multi-account (A4) is ever chosen. Guests keep the placeholder.
- **G2. Sign-out/switch wipe policy.** Recommended: on sign-out, wipe behavioral + partner-local
  data (rules, usage, streaks, enrollments, outbox, nudge inbox) after an explicit confirmation;
  the guest→linked upgrade preserves everything because the uid does not change. Decide explicitly.
- **G3. Room schema.** No multi-account profiles (A4); therefore no per-uid partitioning.
- **G4. Local encryption.** SQLCipher + Keystore-wrapped DEK; the wrapping scheme is unchanged,
  but aliases and prefs names are now UID-scope-suffixed (`_<uidHash>`, ADR #233) so a retained
  database of one identity can never be opened under another. The DEK itself is adopted from the
  legacy scoped alias, never re-derived.
- **G5. Backup/restore is the ruled direction, with an open design.** Per OPD #6 (2026-09-30):
  an app-encrypted blob under the account uid (never plaintext server-side), restorable to a new
  install; Android Auto Backup is the wrong rail (25 MB cap, cannot carry Keystore keys).
  **Scope classification (owner ruling 2026-10-29):** back up *logical data only* — habits,
  streaks, milestones, the day log/journal-derived content, recovery resources and personal
  settings; **exclude device-specific state** — app block rules and package targets, usage
  meters/ledger, VPN/DNS state, widget snapshots, pairing keys, enrollments/chains, outbox and
  nudge inbox. **Key recovery is ruled and built (R2.12, below)** — passphrase-derived Argon2id
  KEK plus a user-held BIP-39 recovery key; the never-built Req 28 `recovery_escrow_key` intent
  stays dead. Runtime usage accounting stays device-local. **Guest→account import
  (Release 1 step 11, ADR #237)** applies the same logical-data scope: the encrypted local
  quarantine stores habits, streaks, milestones, attempts, sessions, coaching, risk windows and
  the recovery policy — never blocks, usage, pairing state or the profile row — and the
  additive import recomputes habit aggregates from canonical rows instead of carrying the
  cached values.
  **IMPLEMENTED (R1.11, 2026-10-30, ADR #237):** the Account pane's Import card shows the
  dry-run counts (`To add / Already here / Updated rows`), the portable-preferences opt-in
  (only when the policies differ), `Import data`, `Retry import`, `Delete quarantined data now`
  behind its own confirmation, and an Imported card naming the seven-day deletion date; the
  import applies as one transaction with natural-key replay, and DISCARD deletes the staged copy
  only after finalization succeeds. The imported habit adopts the guest's selection only when
  the target has no selected habit, so a fresh target shows the import on Home without ever
  overriding an existing choice. Device-proven both branches; the observed uids/hashes are
  recorded in ADR #237.
  **IMPLEMENTED import-vs-backup guard (2026-10-30, ADR #246):** before any dry run or write,
  `ManifestImportCloudGuard` compares the target's server backup pointer with this device's
  materialized/high-water anchors. No cloud snapshot or a materialized one imports normally; a
  newer snapshot this device never materialized shows `Restore your backup first` with
  restore/check-again actions and resumes automatically afterwards; a rollback or equivocation
  is a hard `CLOUD_CONFLICT` with no merge or replacement; an unreadable pointer is
  `CLOUD_UNAVAILABLE`. In every blocked state the quarantine and the target database are
  untouched, and backup upload is already refused by its own base check. When the target
  already holds user rows and the cloud snapshot is newer, the card offers the §6.4 item 8
  choice instead of restore-only: (a) restore the backup after staging a copy of this phone's
  rows, then import both additively; (b) merge the guest rows into this phone and leave the
  cloud pointer untouched (no new uploads until restore or replacement); (c) decide later.
  **IMPLEMENTED key recovery (R2.12, 2026-10-30, ADR #238):** a random 32-byte ABK is wrapped
  twice — AES-256-GCM under an Argon2id KEK (`m=32768 KiB`, `t=3`, `p=1`, fresh 16-byte salt;
  BouncyCastle `bcprov-jdk18on` 1.85.2) for the passphrase, and under an HKDF-SHA-256 KEK for a
  32-byte recovery key shown once as a 24-word BIP-39 mnemonic (checksum-only, no seed
  derivation; `FLAG_SECURE`, sensitive clipboard, acknowledgement, never persisted). AAD binds
  UID, key IDs, format version, wrapper kind and KDF parameters; only the wrapped bundle leaves
  the device. `initializeBackupKeyBundle` (revision 1) and compare-and-set
  `updateBackupKeyBundle` run in `us-central1` with App Check enforced and gate on a persistent,
  non-anonymous, non-deleting account with an ACTIVE profile and verified email. Passphrases:
  manual ≥16 characters and zxcvbn 4/4; generated six-word EFF phrases accepted by generation
  method. Rotation re-wraps one secret with fresh salt/nonce (ABK and snapshot wrappers
  unchanged) and reports success only after the committed wrapper self-tests; an exfiltrated
  old secret cannot be revoked retroactively. `DataStoreBackupRevisionStore` anchors the
  highest revision per UID and fails closed on a lower one; a brand-new device has no anchor.
  Cancel reconciles with server truth after a guard that runs before every mutating callable.
  **Honest limit:** §11.5's p95 < 2 s target is missed on the X6532 debug build (p50 ≈ 4.3 s,
  p95 ≈ 4.7–4.9 s); parameters were not weakened, and the release-build measurement is an open
  owner decision. The upload/manifest layer is R2.14 (ADR #242); staging restore is R2.15
  (ADR #243).
  **IMPLEMENTED snapshot envelope (R2.13, 2026-10-30, ADR #241):** the v1 binary object is
  `MYEXBK01` + envelope version 1 + schema version + 16-byte backup ID + 16-byte ABK key ID +
  fresh 12-byte payload and DEK-wrap nonces + the 48-byte DEK wrapper + the u64 payload
  ciphertext length (tag included) + exactly that many bytes. The object carries no hash of
  itself; authentication comes from the two exact §8.5 AAD layouts (byte-built, never JSON).
  Each snapshot gets a fresh random DEK: the compressed payload is streamed through
  AES-256-GCM under it, and the DEK is wrapped under the ABK with a distinct nonce and AAD, so
  a DEK wrapper can never be replayed as a payload block. Serialization is newline-delimited
  JSON records over one explicit entity allowlist shared with the guest quarantine (attempt and
  session `eventSequence` never carried), compressed through deflate while enforcing the
  100 MiB uncompressed / 20 MiB compressed / 100:1 expansion caps (ADR #247) — an unrestorable object is
  never produced; restore enforces the same bounds while inflating. The whole-object SHA-256 is
  computed over the exact bytes for the manifest only. Decryption writes solely to an
  app-private staging sink and reports success only after `doFinal()` verifies the tag; every
  unknown magic/version, invalid length, truncation, trailing byte and tamper maps to a closed
  rejection reason. `withAbk` holds the unwrapped ABK for exactly one operation and zeroes it
  on every exit.
  **IMPLEMENTED upload + manifest (R2.14, 2026-10-30, ADR #242):** `BackupUploader` drives one
  backup as freeze-read → serialize → seal → prove (exact object length and SHA-256) → delete
  the compressed staging file → `beginBackup` → resumable Storage upload (the issued session URI
  is journaled in the same Tink-wrapped DataStore at most once a second, never logged; the
  reservation is extended before a resumed upload and every 10 minutes while uploading) →
  `commitBackupManifest`; the local anchors advance only after the server confirms the commit.
  Before anything is sealed it mirrors the server decision table against this device's
  high-water anchor, so a replayed, ahead-of-base, or equivocated pointer stops the operation
  before any reservation. An explicit replacement is bound to the exact pointer the user was
  shown and still passes the high-water guard. A lost upload response is resolved by the
  manifest's own SHA-256 check: bytes that arrived are committed instead of re-uploaded (the
  create-only Storage rule would reject a second write anyway). Resume is honoured only when
  UID, session generation, backup ID, length and SHA-256 all still match; anything else is
  discarded with its object. `storage.rules` already enforces owner-only create-only
  octet-stream writes ≤ 21 MiB at the reserved path with a live reservation document, and
  denies every update/delete. The Account pane's Encrypted backup card has a Back up now form
  (passphrase or recovery words, held in memory only until the flow ends), live progress,
  cancel, a retry that reuses the held secret, and a Replace / Keep the cloud backup choice
  when the server names a conflicting pointer. The success copy ("restore it on a new phone")
  is backed by the R2.15 restore below.
  **IMPLEMENTED automatic daily pass (ADR #310, 2026-11-14):** the ABK is also persisted on the
  phone wrapped by a non-exportable Android Keystore KEK (AES-256-GCM, StrongBox opportunistic →
  TEE, bound to UID + bundle key ID) and adopted after every successful passphrase/recovery
  unlock, so a debounced 24-hour WorkManager pass can back up without a user secret. The change
  detector is the SHA-256 of the exact compressed plaintext the uploader seals, recorded only
  after `commitBackupManifest` confirms; the pass never replaces a conflicting pointer and never
  uploads an empty snapshot. The wrapped form is never uploaded or transferred: passphrase and
  recovery key remain the only portable unlock. Settings shows the cadence and last completed
  backup. Honest limits: an App Lock vault lock defers the pass until the next unlock; one key
  per phone (an account switch parks automation until a manual unlock); 24-hour WorkManager
  timing is best effort, not a guarantee.
  **IMPLEMENTED staging restore (R2.15, 2026-10-30, ADR #243):** `BackupRestorer.prepare`
  fences the session and refuses a rollback or equivocation against this device's high-water
  pair before any download; the current pointer and manifest must describe one snapshot
  identically (path rebuilt from the authenticated UID, 32-hex backup ID, exact size relation).
  `beginBackupRestore` pins the object against retention server-side (one live pin per UID,
  1-hour TTL, replay-idempotent, superseded by a different backup ID, cleared by
  `endBackupRestore`), the object is downloaded to app-private staging and must match the
  manifest's length and SHA-256 before the ABK is even unwrapped, and no payload byte is parsed
  until the GCM tag verifies. The authenticated payload is validated as a logical graph (unique
  IDs, milestone→streak membership, risk-window cap, non-empty), then written into a byte copy
  of the active database: only the eight logical tables are replaced (restored rows remapped to
  the active profile; per-habit pair consent survives), and rules, usage, pairing, profile,
  policy version and recovery state stay. The staging file must pass `integrity_check` and
  `foreign_key_check` before the active pointer is swapped by two renames, with the pre-restore
  copy kept until the swapped file opens cleanly and a completion mark persisted before the
  relaunch. The preview card shows the revision and counts, defaults to `Keep Local`, offers
  `Restore Cloud Snapshot` and `Cancel`, and states that blocks, screen-time history and the
  partner pairing stay; a device with no anchor is told the revision is what the service
  reports (§6.6 item 10). Every failure path ends the pin, deletes the attempt's staging and
  reopens the untouched active file.
  **Same-device recovery is not backup (ADR #240, 2026-10-30).** The 2026-10-29 ruling still
  governs the backup blob and the portable quarantine: block rules, usage meters/ledger and
  pairing state never enter either. A retained scope is a different artifact — the previous
  identity's own database on this phone — so the same-device recovery (G8) additionally restores
  its block rules, daily usage trackers, usage ledger and per-rule usage into the account scope,
  additively and idempotently, before the offer is retired. Pairing state still stays out: the
  transition terminates every pair server-side (reason `GUEST_ACCOUNT_TRANSITION`), so there is
  no live relationship to restore into the new identity.
- **G6. Migration safety.** The link-upgrade must not touch Room; the DB version does not change.
- **G7. UID-scoped database paths — IMPLEMENTED (Release 1 step 7, ADR #233).**
  `LocalDatabaseMigration` copies `myexodus_rules.db`, `myexodus_accountability.db` and
  `myexodus_activity.db` to `myexodus_{store}_<uidHash>.db` on the first post-update launch and
  commits a journal (`scope_uid_hash`) that makes the operation idempotent and crash-safe; the
  legacy files survive as the rollback copy for seven days. Names derive only from a SHA-256 of
  the uid — never from backup/UI input — and a different current identity never inherits a
  stored scope (ADR #125 rotation keeps this install's data). The account transition itself is
  now complete: step 9 prepare/finalize/cancel (ADR #235), step 10 active-scope switching and
  session fencing (ADR #236), and step 11 quarantine/import (ADR #237). The cloud-restore offer
  on keystore failure remains a Release 2 (G5) item.
- **G8. An identity change outside a transition keeps the retained scope importable — IMPLEMENTED
  (2026-10-30, ADRs #239/#240).** `onAppStart` used to treat any persisted non-anonymous identity whose
  uid hash differed from the active scope as the crash window of a switch and moved the pointer
  without asking; on 2026-10-30 that emptied the app for an account whose real data was still in
  the guest scope. The switch is now expected only when a persisted `pending_identity_switch`
  marker (written by `beginTransition`, cleared by `completeTransition`/`abortTransition`) is
  present. An unmarked mismatch still moves the pointer — a retained database must never open
  under a different UID — but records an offer (`recovery_scope_hash` plus the uid it is promised
  to; a guest sign-out promises it to the next account, because an anonymous UID can never sign
  back in). A **bound** offer (promised to the signed-in uid) stages itself at the next start and
  imports without a second tap; an **unbound** guest-sign-out offer keeps the Account pane's
  "Data from a previous profile" card (ADR #240). Either way the retained scope is read from a
  byte copy (db plus a non-empty `-wal`) under its own Keystore DEK — the original is never
  opened, rekeyed or checkpointed, and a missing key is never re-minted — and the snapshot enters
  the same R1.11 quarantine, dry run, conflict refusal, atomic apply and seven-day replay. The
  automatic path still stops at a human decision: plan conflicts and the portable-preferences
  opt-in show the Import card. A same-device recovery additionally restores the retained block
  rules and usage (trackers, ledger, per-rule usage) into the account scope before the offer is
  retired (ADR #240); none of that enters the backup or the portable quarantine — the G5
  exclusion still governs those. The offer is cleared exactly when the import commits and its
  local restore succeeds; deleting the quarantine does not delete the retained scope, so the
  offer reappears while those files still exist. Only one retained scope can be on offer at a
  time (the pointer that was replaced). No new disclosure: pre-R1.10 builds showed the retained
  data to whoever was signed in, and the transition's own choice is the consent for a bound
  offer; an unbound offer still asks the signed-in account first.

### H. UX and onboarding
- **H1. First-run identity choice** (continue anonymously vs create/sign in), with what an account
  buys and costs explained — no dark patterns, no forced sign-up.
- **H2. Upgrade prompts** before pairing and in Settings; explain that the partner will keep the
  same relationship (uid preserved).
- **H3. Auth screens** cover the full state matrix: loading, invalid credentials, network failure,
  unverified email, credential-already-in-use, provider cancellation, accessibility.
- **H4. Consequence confirmations** for sign-out, account deletion and switching — what stops, what
  stays, what cannot be undone.
- **H5. Partner-side copy.** "Re-pair" becomes "sign in to restore" where an account exists; the
  device-worded surfaces from OPD #37 are updated in the same pass.

### I. Security, privacy and legal
- **I1. DPIA extension.** The partner DPIA is scoped to SOS only; account PII (email, name) and the
  email provider require their own assessment under the Kenya DPA (controller identity, retention,
  erasure).
- **I2. Account-takeover model.** A compromised account must not bypass device-key signing or
  biometric gates; explicit reauth for high-risk actions.
- **I3. Age gate.** The 18+ self-declaration stays; accounts collect PII only within that gate.
- **I4. Terms/privacy + deletion route** (Req 24/29) updated for accounts and account deletion.
- **I5. Firebase project work.** Enable providers, OAuth consent screen, authorized domains; add the
  release signing fingerprint when release signing starts; confirm the direct-APK Google sign-in
  flow against official docs.
- **I6. Data minimization.** No partner email/phone is ever collected or shown.

## 4. Touchpoint → verdict map (completeness check)

| Existing surface | Verdict under accounts | Item |
|---|---|---|
| `PairUidResolver.ensureUid` single-flight | Changes: state machine, guest-only minting | A1, A5 |
| ADR #125 rotation guards | Keeps for guests; signed-in users reauthenticate | A5 |
| `PairingReachabilityStore` denial/rotation card | Gains "Sign in to restore" | C2 |
| `FirestoreSchema` pair/mailbox/push/nudge paths | Unchanged shapes; deletion + rules work | E1–E3, E8 |
| `PushInstallationRegistrar` | Must deregister on sign-out/switch | E8 |
| `EnrollmentPayload.label` / pairing ceremony | Label becomes fallback for account name | D2, D5 |
| `RulesRepository.updateProfileName` / own pairing label | Own name comes from the account profile when signed in; typed label is the guest fallback | D2, D5, D6 |
| `displayNameOf` / named notification variants | Precedence adds account name | D2, F1, F3 |
| DMS fixed-copy notifications | Decide server name vs generic | D3, E4 |
| `PartnerNotifications` channels | Stay generic (constraint) | F4 |
| Local `user_profile` placeholder | Becomes account mirror | G1 |
| `RulesRepository.LOCAL_PROFILE` | Guest placeholder; account path added | G1 |
| `BilateralDeletion` | Unchanged; account deletion is new | C4, E2 |
| `IdentityMigration` / epoch | Must keep severing; copy distinguishes causes | B3, B4 |
| `functions/attestation.js` / `AttestationVerifier` | Unchanged | B5 |
| Callables `request.auth` | Unchanged contract | E1 |
| `AppCheckInstaller` / enforcement | Unchanged | E3 |
| Setup wizard / onboarding | New identity step | H1, H2 |
| Usage engine, rules, streaks, VPN, overlays, widget | **No runtime change**; encrypted backup is additive (G5) | G5 |

## 5. What explicitly does NOT change

Blocking enforcement, VPN/site metering, usage accounting, streaks/milestones, recovery, widgets and
the Desert Dawn UI do not depend on who the user is. The pairing relay contract, transcript/pairing
proof, attestation roots, per-pair tray-id scheme (ADR #119) and channel definitions are unaffected.
The account is an identity/recovery/communication feature, not an enforcement prerequisite.

## 6. Open decisions to settle when auth work begins

1. Single account per install (recommended) vs multi-account profiles.
2. Local-data wipe on sign-out/switch (recommended) and the exact confirmation copy.
3. Name precedence order (account name > typed label > key id) confirmation.
4. Whether server-held names are allowed for DMS fixed-copy messages.
5. New-device policy: partner-approved re-enrollment vs trust-on-first-use; multi-device allowed?
6. Account-deletion retention window and partner notification wording (legal input).
7. The key-recovery design for the encrypted account backup (passphrase-derived KEK vs
   recovery-phrase escrow) — the direction is ruled, the mechanism is not.
8. Email verification: what an unverified account may do (A9), password policy, session revocation.
9. Provider fallback on devices without Google Play services (A10).
10. Name distribution mechanism, rename propagation and last-known caching (D6); lock-screen
    visibility of partner names (F2).
11. Which candidate perks in §8 actually ship.

## 7. Definition of done for the auth build

Every item above is marked Required/Deferred/Rejected with a reason; the §4 map is re-audited; the
device/emulator legs in A–I are run (link-upgrade, sign-out wipe, second-device enrollment, deletion
purge with push deregistration, named notifications under multiple partners); the §8 perk list is
decided; `app/docs/` (this file, OPD items #6/#36/#37 pointers, DPIA) and the progress note are
synced; no invariant in §2 regresses.

## 8. Candidate account perks (product decision, 2026-10-29)

The owner asked what an account buys. Each candidate needs a Required/Deferred/Rejected verdict:

1. **Identity survives reinstall and device change** — the same account restores pair memberships
   after re-enrollment (A2/B1/C2); the strongest perk and the reason accounts exist.
2. **Encrypted backup/restore of logical data** (G5 classification) — habits, streaks, milestones,
   day log, recovery content, personal settings; never block rules or device state.
3. **Partner sees your chosen name** — the account display name replaces the label the partner
   typed (D2/D6); renames propagate.
4. **Account recovery** — password reset or provider sign-in instead of losing the identity
   (A5/A9).
5. **Multi-device** — only if B2/A4 rule it in; otherwise one active device per account.
6. **Email channel** — verification, reset and account notices (E5); never partner data.
