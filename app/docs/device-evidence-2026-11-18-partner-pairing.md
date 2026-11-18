# Device evidence — fresh-device partner pairing and delivery (2026-11-18 ~11:52–12:05 EAT)

Re-test of the 2026-11-18 field failure (partner received nothing; `ingestPartnerEvents`
rejecting with `RECIPIENT_KEY_STALE`) on a clean second handset. **No forced drains, no
manual sync triggers, no backoff overrides** — every call below was produced by ordinary
UI acts (pairing, adding a rule/streak) against the live backend.

## Setup

- Device: Infinix X6532C, Android 14 (API 34), wireless ADB a LAN wireless-ADB address (withheld from the repo).
- Installed the published v41 artifact `MyExodus-1.1.39-v41.apk` (the same file whose
  SHA-256 `484fd5a7…` matches `artifact-v41` in `app-update-channel.md` §1/§14) via
  `adb install`. `versionCode=41 versionName=1.1.39`.
- Onboarding walked (usage access, accessibility, notifications, overlay), anonymous
  account. Accessibility was granted without the Android restricted-settings block
  because the shell installer is trusted; the Drive-installed copy (Files-app installer)
  still hits that block — install provenance, not a build difference (see
  `project_partner_field_test_2026_11_18`).

## Timeline (UTC)

| Time | Event | Source |
|---|---|---|
| 08:59:48 | pairing `NErryyQtdBR4xjJpA8nXZ5Qdwml1_WRtLqZ96fTYXmu32qZDMkzwq6MH2` created (status active) | Firestore `pairings` |
| 09:00:03 | owner (`WRtLqZ96…:1c0db12d…`) registration/key updated; old recipient key revoked, new `cUY1bajv…` ACTIVE | `partnerDeviceSigningRegistrations`, `partnerRecipientKeys` |
| 09:00:05 | **fresh device** (`NErry…:5923841b…`) registration ACTIVE; recipient key `OkgPYkG2…` ACTIVE — 17 s after pairing | same |
| 09:02:16 | owner's previously stale queued event accepted (`accepted: 1`, installation `1c0db12d…`) — first success after the morning's `accepted: 0` streak (last rejection 08:55:58, ~5.5 min retry gap) | `ingestpartnerevents` logs |
| 09:02:39 | owner's streak-share event accepted (`accepted: 1`); outbox doc for installation `5923841b…` → `SENT_TO_FCM` | logs + `partnerNotificationOutbox` |
| 09:02:42 | `partnerNotify` push | logs |
| ~09:03 | notification posted on the partner phone: title "musila shared a streak with you", text "You were added to testing.", channel `partner_accountability_events_v1`, tag `partner:event:NErry…_WRtLq…` | `dumpsys notification` + shade screenshot |
| 09:05:26 | owner logged a relapse; outbox doc → `SENT_TO_FCM`; partner phone entry replaced in place: "musila logged a relapse" / "testing · ended a 1-day streak · Nov 18, 2026 12:05 PM" — the alert appeared **on screen** (owner observation), entry importance 4 | `partnerNotificationOutbox` + `dumpsys notification` |

## Rule vs streak notifications

Rule changes **do** notify: the §8.4 rule-control path (`notifyRuleChanged`,
`PartnerNotifications.kt:363-390`) posts "Rule update from <name>" with a per-verb body
(created / assigned / updated / disabled / deleted / expired / unassigned). Confirmed
both ways on 2026-11-18: the partner page's "Rules & blocks" list showed
"Things I'm trying to quit — Rule assigned to you · 32 items" and
"YouTube — Rule assigned to you"; the YouTube rule's push landed in the service outbox
(09:07:50Z, `SENT_TO_FCM`) with device text "Rule update from musila / You were added as
accountability partner on YouTube." (The older pair-event payload vocabulary
`EventPayload.kt:62-118` has no rule type — the rule alerts ride the separate
rule-control wire, not the activity payload set.)

Tray caveat: every partner alert shares one per-pair entry that each new event replaces
in place, so a rule alert can be overwritten by a later event within seconds and
"not in the tray now" does not mean "never delivered" — the partner page list is the
durable record.

## Ruling

The delivery path is sound when the recipient device registers: pairing performed the
key handshake on both sides, the recipient's signing registration and recipient key went
ACTIVE within 17 s, and the owner's next accepted events produced a mailbox push and an
on-screen notification within seconds.

The morning's field failure is therefore **recipient-side enrollment never happening**,
not the ingest/notify pipeline. The real partner uid `aE9Ai9k6…` still has zero rows in
`partnerDeviceSigningRegistrations` / `partnerDeviceLiveness` / `partnerRecipientKeys`,
and Cloud Logging has no `registerDeviceChallenge` / `registerOrRotateDevice` call from
that device, ever.

Build version is **not** the cause: the partner's handset runs v40/1.1.38 (same as the
Drive file), and the v40 tree (commit `527f2fc`) already ships the full delivery package —
`git diff 527f2fc 7a0cedd` shows no changes under `accountability/`, so v40 and v41 have
identical enrollment code. The Drive file differs only in being built without
`-Pmyexodus.updateRepository` (the "no update channel" string), which does not affect
delivery. Remaining explanation class is runtime on that handset: the foreground-gated
§8.3.1 enrollment never completed (app closed/OEM-killed right after pairing, vault
locked, or Keystore key generation failing as the `AccountabilityKeys` fallback log line
anticipates). Next check with that phone in hand: open the app, unlock if App Lock is on,
stay on the Partner page ~60 s while paired, then look for a registration row.

## Accessibility restriction — proven mechanism, and the XOS override gap (2026-11-18 ~12:20–12:28)

- **Mechanism (AOSP source, android14-release, verbatim).** Every install whose package source is
  `PACKAGE_SOURCE_LOCAL_FILE` or `PACKAGE_SOURCE_DOWNLOADED_FILE` (Drive, Files, browser
  downloads) gets `AppOpsManager.OP_ACCESS_RESTRICTED_SETTINGS` set to a denying mode at install
  (`services/core/java/com/android/server/pm/InstallPackageHelper.java:2374-2402`,
  `enableRestrictedSettings`). Enabling an accessibility service requires that op == ALLOWED
  when `config_enhancedConfirmationModeEnabled` is true
  (`services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java:4095-4112`);
  both X6532 phones report that config `true` (`cmd overlay lookup android
  android:bool/config_enhancedConfirmationModeEnabled`). Store/adb installs are not restricted by
  this code path; on both phones `installerPackageName=null` and the op reads `allow`.
- **The only user override in AOSP Settings is App Info ⋮ → "Allow restricted settings"**
  (`packages/apps/Settings/.../appinfo/AppInfoDashboardFragment.java:407-409` menu add,
  `:529-539` visibility). The XOS 14 App Info page has **no overflow menu at all** — captured on
  both the X6532 (owner's, build 260710V3278) and the X6532C (260727V1698), scrolled to the page
  bottom: no ⋮, no restricted-settings entry anywhere. So on this skin the override cannot be
  reached at all.
- **Device repro (X6532C).** With the op in the state a download-file install creates
  (`cmd appops set com.example.myexodus android:access_restricted_settings deny`): toggling the
  service on shows the normal accessibility consent dialog; tapping **Allow** silently reverts
  the toggle to Off, `enabled_accessibility_services` stays null, and a rejection is recorded in
  the op (`rejectTime` updated). No explanation is shown. Restored `allow` afterwards, service
  re-enabled (`enabled_accessibility_services` back), `scripts/rotation-guard.sh` run
  (`accelerometer_rotation=0`).
- **Consequence.** On skins that hide the App Info overflow (proven on XOS), a Drive/browser
  install can never have accessibility granted by the user; in-app instructions cannot create a
  system menu. Real-user routes that provably avoid the restriction: Google Play (store source
  never runs the restriction code) — excluded by ADR #15, owner decision — and adb/shell install
  (device-proven). The stored op is sticky across updates: a `-r` update attributed to Drive left
  it at `allow` on the X6532C.

## Drive APK and the v40 update-channel gap (verified 2026-11-18)

- `/sdcard/Download/MyExodus-1.1.38-v40.apk` pulled from the X6532C: 39,251,849 B, SHA-256
  `219c6ea0387c455dac08ab341eb526922b39a15ff15c7e0635e150706bde7b10` — **byte-identical to the
  published `artifact-v40`** in `Musila-ke/MyExodus-Releases` (recorded in
  `app-update-channel.md` §1). The Drive file's SHA-256 is no longer unverified.
- The v40 APK's dex contains **zero** occurrences of `MyExodus-Releases`, so `artifact-v40` was
  built without `-Pmyexodus.updateRepository`: every v40 install legitimately shows "This build
  has no update channel". The v41 artifact's dex contains the string (`classes4.dex`), so v41
  has a working channel. v40 phones need one manual v41 install; after that the channel updates.

## Natural latencies observed (no forcing)

- Pairing → recipient registration: 17 s.
- Rejected-event retry cadence: 30 s base backoff, observed ~5.5 min gap, cap 30 min
  (`PartnerEventDrain.DrainLimits`). A queued event stranded by a stale key can wait up
  to the cap before re-encrypting — real-user behaviour, relevant to the "within 2 min"
  product bar.

## Follow-up: the overflow question, answered as far as devices allow (2026-11-18 ~12:27–12:47)

- App Info captured in every shell-reachable state: X6532C at 12:31–12:32 with the op at
  `allow`, `ignore` and `deny` (Settings force-stopped before each, page fully rendered);
  X6532 (owner's) at 12:27 and 12:34 with its live state (`allow`, adb install). **No
  overflow ⋮ on any capture, either phone.**
- The state a real file/download install creates cannot be faked: `InstallPackageHelper`
  sets `MODE_ERRORED`, and the shell refuses it (`cmd appops set ... errored` →
  "Mode errored is not valid"). `pm install` never sets a package source, so an adb
  install cannot reproduce a Drive install's op state either.
- AOSP 14 source fact that reframes the whole issue: Settings adds the "Allow restricted
  settings" item unconditionally but shows it only when the op reads **MODE_IGNORED**
  (`AppInfoDashboardFragment.shouldShowAccessRestrictedSettings`, android14-release, lines
  529-537), while the installer writes **MODE_ERRORED**
  (`InstallPackageHelper.enableRestrictedSettings`, lines 2392-2402). On an
  ECM-enabled device (`config_enhancedConfirmationModeEnabled=true`, both phones), a
  freshly sideloaded app therefore shows **no ⋮ on stock AOSP 14 either** — the item's
  visibility condition never matches the install-time mode. The `MODE_IGNORED` state (the
  only one where the item appears) is not produced by the install path; it can only be
  left behind by other tooling/state.
- Owner report of having seen and used the ⋮ on an adb-installed copy in an earlier
  session is not reproducible in any state on either phone today; it is consistent only
  with the op having read `MODE_IGNORED` at that moment. **Resolved later the same day
  (~14:00):** after the X6532C replaced 1.1.39 with 1.1.40 through the in-app updater
  (`installerPackageName=com.example.myexodus`, `lastUpdateTime=13:51:47`), its op read
  `ACCESS_RESTRICTED_SETTINGS: ignore` (`time=+9m56s` at check, matching the update), and
  the owner confirmed the ⋮ is now visible on both phones. The in-app self-update install
  path is a second producer of `MODE_IGNORED`. The earlier "not reproducible" conclusion
  held only for Drive/adb installs, not for this path; the owner's observation was correct.
- Attempt to drive a true file-source install through the on-device installer failed:
  the XOS resolver routes APK VIEW intents to App Info, `InstallStart` started directly
  with `-f 1` is refused (shell UID lacks grant on the downloads URI), and the only
  on-device copy is v40 (downgrade from installed v41 is rejected). No uninstall is
  permitted on physical devices.
- Restored after testing: op `allow`, accessibility service enabled, rotation guard run,
  pushed file and uiautomator dump deleted.

## v42 re-check: delivery fixes on both paired phones (2026-11-18 ~13:57–13:59 EAT)

Both physical phones on release-signed 1.1.40/v42 after the fixes: every actionable
registration block surfaces (tray / Settings dot / Account card), and a foreground drain
re-probes waiting rows inside the 900 s defer.

- **Install path.** X6532C: in-app GitHub Releases update v41 → v42 (Download → verify →
  Install Unknown Apps grant → Play Protect scan → Install), `versionCode=42`,
  `firstInstallTime` preserved. X6532: in-app download stalled at "0%" for several minutes
  (default network cellular; metered consent had already been given by the UI), so the
  channel-verified `artifact-v42` APK was installed with `adb install -r`. Root cause not
  established.
- **Post-upgrade notice.** The X6532 showed the one-time "MyExodus is now on version
  1.1.40" dialog with the exact signed release notes; OK dismissed it.
- **Pair state.** The phones are paired with each other and verified: X6532C shows
  "musila / Since Nov 18, 2026 / Shared activity is verified" and "Synced just now"; the
  X6532 shows "John / Since Nov 18, 2026 / Shared activity is verified".
- **No false alarm.** Neither tray contains a "Partner updates are paused" notification,
  and neither Settings index row carries an attention dot from `deliveryBlock` — correct
  for a healthy pair (the block was null on both).
- **Foreground drain runs on app open.** Relaunching the app on both phones produced
  fresh sync-worker drain lines within seconds (`drain: 0 event(s) delivered`, outbox
  empty). The changed `regenerateWaiting` path executes on every foreground drain.
- **Real rule-change delivery on v42.** On the X6532, toggling the 32-site "Things I'm
  trying to quit" batch off delivered a notification to the X6532C within seconds:
  "Rule update from musila · now / The Things I'm trying to quit batch was tur…", and the
  Partner tab's "Rules & blocks" count moved 4 → 5. Restoring the toggle produced a second
  "Rule update from musila … was cha…" and 6 updates; the X6532C's next sync reported
  `recipientEventsPulled=1`. Both directions of the delivery pipeline work on the release
  build carrying the fixes.
- **Restored state.** The batch is back ON ("Blocked now" on all sites); rotation guard
  ran on both serials (`accelerometer_rotation=0`).
- **Not exercised:** an induced `ATTESTATION_REQUIRED`/`RETRYABLE` stall on hardware (no
  natural producer was available without breaking the healthy pair); the surfacing path
  for those blocks is covered by the exhaustive JVM predicate test.
