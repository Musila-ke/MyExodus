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
| Deletion | Pair-level **bilateral deletion** exists; there is **no account-level deletion** | `BilateralDeletion.kt` |

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
- **A2. Upgrade path = link, with collision handling.** Anonymous → email/Google via
  `linkWithCredential`; on `credential-already-in-use`, offer sign-in-to-existing vs cancel and
  state that cancelling keeps the guest identity. Never silently orphan either side.
  **Link ≠ sign-in:** linking keeps this install's data and pairs (uid preserved). Signing into an
  existing account from a guest install replaces the guest identity — an explicit choose-one whose
  local consequence is the G2 wipe; the guest's pairs remain reachable only by its old uid and are
  otherwise abandoned. Never silent.
- **A3. Sign-out semantics.** Confirmation must list consequences: partner updates stop, pending
  outbox stops, local sensitive data is wiped after confirmation (G2), push installation is
  deregistered (E8); server-side pairs and any encrypted backup remain for a later sign-in. The
  next partner operation mints a fresh guest uid (ADR #125 path), so the old identity is
  recoverable only through the account.
- **A4. Account switching.** Recommended: single account per install; switching wipes local data and
  re-syncs from the account. Multi-account profiles would require a Room schema change and are
  rejected unless a real need appears.
- **A5. Dead-token recovery diverges by identity type.** ADR #125's rotate-to-new-anonymous-uid
  stays guest-only; a signed-in user is sent to re-authentication instead of rotating. The
  re-pair card gains a "Sign in" remedy (C2).
- **A6. Reauthentication for sensitive actions.** Account deletion (E2) and identity-changing
  actions require a fresh credential; biometric signing gates remain independent and unchanged.
- **A7. Provider setup for the direct-APK channel.** Email/password + Google enabled in the
  Firebase project; email verification; password policy; email-enumeration protection; OAuth
  client IDs for a non-Play install. Verify against official Firebase docs before implementation.
- **A8. Offer timing.** Anonymous remains the default (OPD #6 direction). The account offer belongs
  before pairing (highest value) and in Settings as "Secure your identity" (OPD #36).
- **A9. Email verification and password lifecycle.** Decide what an unverified email may do
  (recommend: all core use allowed, verification prompted, required for email change and account
  deletion) and for how long. Password reset while signed out (email link/code, generic copy so
  the flow cannot enumerate accounts) vs change while signed in (recent-login reauthentication,
  then invalidate other sessions). Firebase-hosted templates/action pages; a direct-APK build
  needs no app deep link for reset, but the sending domain and templates must be configured.
  Email change is Deferred unless the plan calls for it; if added it requires recent
  reauthentication and re-verification.
- **A10. Provider availability across OEMs.** The app targets every Android skin; devices without
  Google Play services cannot use Credential Manager Google sign-in. Detect availability, hide the
  Google option and fall back to email/password — never dead-end (OPD #6 already records that the
  email/password provider itself needs no Play services). Verify the exact Credential Manager +
  Firebase ID-token flow (web/server OAuth client id, no Play install referrer) against official
  docs before implementation.

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
- **G4. Local encryption.** SQLCipher/Keystore setup is per-install and unchanged.
- **G5. Backup/restore is the ruled direction, with an open design.** Per OPD #6 (2026-09-30):
  an app-encrypted blob under the account uid (never plaintext server-side), restorable to a new
  install; Android Auto Backup is the wrong rail (25 MB cap, cannot carry Keystore keys).
  **Scope classification (owner ruling 2026-10-29):** back up *logical data only* — habits,
  streaks, milestones, the day log/journal-derived content, recovery resources and personal
  settings; **exclude device-specific state** — app block rules and package targets, usage
  meters/ledger, VPN/DNS state, widget snapshots, pairing keys, enrollments/chains, outbox and
  nudge inbox. The open engineering piece is **key recovery** (passphrase-derived KEK vs
  recovery-phrase escrow, the never-built Req 28 `recovery_escrow_key` intent); no backup ships
  until that is ruled. Runtime usage accounting stays device-local.
- **G6. Migration safety.** The link-upgrade must not touch Room; the DB version does not change.

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
