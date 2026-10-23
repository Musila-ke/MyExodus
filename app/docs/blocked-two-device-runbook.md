# Blocked legs — two-physical-device runbook

Every leg in this file is BLOCKED for a hardware reason, not an implementation reason. Run it
only when a **second physical Android phone** (with hardware TEE attestation and its owner
present) is available alongside the X6532. Until then these legs stay recorded as blocked —
never simulated, never inferred from JVM tests, never reported as live-backend proof.

The local two-AVD rig (`ATTESTATION_STUB=1`, Firebase emulators, `PartnerTestKeys`) is the
sanctioned rehearsal and already covers most behavioral legs; its evidence is labelled
**LOCAL-RIG** everywhere. This runbook is for the **LIVE** and **PHYSICAL** legs it cannot prove.

## Why these legs cannot be faked

- Pairing requires hardware-backed attestation. `AttestationVerifier.kt:37` rejects
  `SecurityLevel.SOFTWARE`, and the server pins Google hardware roots — an emulator's key never
  passes (re-confirmed 2026-10-23: the X6532 rejected an AVD with "The other phone's security
  identity didn't pass verification"). A debug waiver was explicitly rejected; the virtual
  partner is a harness, not a pairing substitute.
- Real FCM delivery needs two distinct production/no-stub installs. The emulator rig delivers
  through mailbox sync, not through Google's push transport.
- The bilateral unpair leg shreds the pair's keys and consume the pairing. It must run LAST.

## Preconditions (both phones)

1. **APK:** the same debug APK on both, installed with `install -r` only on the X6532. Never
   uninstall or `pm clear` the owner's phone; on the second phone a clean install is fine.
2. **App Check debug token (debug builds only):** Firestore is App Check-enforced. Read each
   install's token from logcat (`DebugAppCheckProvider: Enter this debug secret into the
   allow list`), register it in the Firebase project (App Check → Apps → Manage debug tokens),
   then force-stop and relaunch. Without this, the first Firestore write fails `PERMISSION_DENIED`
   — the exact cause of the 2026-10-23 pairing failure after the AVD's data was wiped.
3. **Auth:** both apps must hold an anonymous Firebase uid (the onboarding flow does this).
   Confirm a uid exists before starting; a rotated uid orphans the previous pair (ADR #125).
4. **Notifications:** `POST_NOTIFICATIONS` granted on both (`adb shell pm grant
   com.example.myexodus android.permission.POST_NOTIFICATIONS`).
5. **Device credential:** both owners present. Signing after a cold start/update raises
   "Confirm with your device key"; the owner must satisfy it (ADR #112). A phone without its
   owner cannot send partner events.
6. **Network:** both on the same LAN/internet; no VPN on the pairing path.
7. **Owner permission:** the X6532's pair is intentionally left alive between sessions. Any leg
   marked CONSUMES PAIR below destroys it and requires a fresh ceremony afterwards.

## Legs (run in this order)

### 1. Production pairing ceremony + SAS — closes #36 pairing half
- X6532 → Partner → Invite a partner → create the code; second phone → Join with a code.
- Both compare the six-digit SAS and tap "They match"; both show "You're paired" naming the
  other phone's label.
- **Proof:** both roster rows, both enrollment rows, server pairing doc `active`, and the
  Firestore mailbox usable from both ends. Record the pair id and epoch (expected 1 on fresh
  installs).

### 2. Partner activity over real FCM — closes #36 notification half
- From phone B (sharer): enable "Share this streak" + relapses/milestones; create/pick a habit.
- Trigger streak update, relapse, milestone (day-7 recipe: set clock forward + reboot to bump
  `BOOT_COUNT`, then deliver `BOOT_COMPLETED` to `MilestoneBootReceiver` — ADR verification
  item c), a motivational message, and a coaching-plan edit.
- **Proof per event:** sender chain row → mailbox document → receiver chain row → receiver
  notification on `partner_accountability_events_v1` naming the partner; the sender's pending
  flags clear. On a real device the receiver may be woken by FCM; if it is not, a foreground
  pass/`Check now` must deliver the same event (push is a latency optimization).
- Also run the local test alert on one device (single-device, no partner needed) to separate
  channel health from transport.

### 3. SOS nudge, two physical phones — residual from 2026-10-18
- X6532 → Reach out (SOS) to the live partner; second phone receives the HIGH-importance
  `partner_nudges_v3` notification and the Home inbox card.
- **Proof:** `sendNudge` accepted, queue doc finalized `sent`, push delivered (or the inbox card
  present after a sync), and the receiver's tap routes to the Home partner surface with the
  nudge id.
- **Restore:** leave the server kill switch `config/features.partnerSosEnabled` exactly as found;
  the RC half is retired (ADR #171).

### 4. Adversarial pairing on real devices — closes #49's remaining device legs
Run each on the **second phone** against a live invitation from the X6532; none should consume
the live pair because each attempt fails before SAS:
- wrong code (one character off) → refusal copy, invitation still usable;
- expired code (wait past the server expiry; do not trust the creator's clock) → "invitation ran
  out" on the joiner, inviter's wait still bounded by the 5-minute skew allowance (ADR #215);
- SAS refusal ("They don't match") → both sides return to an ended/retry state, no enrollment
  written;
- re-join on an already-active pair → refused without touching the live enrollment.
- **Proof:** each outcome observed on both screens plus no new `active` pairing doc; logs show
  the refusal reason, not a crash.

### 5. Bilateral unpair — CONSUMES PAIR, run LAST
- X6532 → Partner → End partnership → owner device credential.
- **Proof:** sender reaches remote-shred-confirmed; receiver shreds, acks, and shows "Your
  partner ended the partnership… erased"; both rosters drop the pair; mailbox unusable
  afterwards (denied). Then re-run Leg 1 to leave a live pair for the next session (or leave
  unpaired by owner choice — record which).

### 6. Two-device stress/resilience (Tier C) — if still in scope
- Kill/restart either app mid-sync; airplane-mode one phone while the other sends; doze the
  receiver; force-stop + relaunch. **Proof:** no duplicate chain rows, no lost event after
  resume, one notification per event, outbox retries with backoff. The single-device half is
  already covered in `project-stress-test-backlog`; these are the multi-device residuals.

## Recording the result

- Label every observation as **LIVE** (real backend, real FCM) or **PHYSICAL** (real devices).
  A LOCAL-RIG result is never upgraded by association.
- For each leg write: date/time, pair id + epoch, uid/install ids, the exact log lines or
  Firestore document paths, and whether it was a notification or a mailbox-pull delivery.
- If a leg cannot run (no second phone, no USB adb, owner absent), mark it BLOCKED with the
  missing precondition — never a silent skip.
- Update `app/docs/deep-review-2026-10-22.md` (audit ledger), `MyExodus V1.md` if a V1
  requirement closes, the vault progress note, and the task list in the same session.
