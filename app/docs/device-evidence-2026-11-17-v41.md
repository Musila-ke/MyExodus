# Device evidence — v41 / 1.1.39 blocked legs and the "Protection is limited" banner (2026-11-17 ~06:00–06:40 EAT)

Wireless ADB (LAN address withheld from the repo) returned; the legs recorded here close the three
itemised as BLOCKED in the 2026-11-16 late-night progress entry. Device: X6532, Android 14
(API 34), pid 32236, v41/1.1.39 (`versionCode=41 versionName=1.1.39`). No code changed.

## Path A wall (Instagram) — PASSED

- Rule in force: batch "Apps: Instagram, 6:00 AM–6:00 PM · 2h daily limit" (`Used today:
  7 / 120 min`, `Blocked now`). Launches before 06:00 were outside the window — the earlier
  "not blocked" observation was correct behaviour, not a defect.
- 06:19:40 — Instagram launched: `topResumedActivity=...BlockerActivity`; UI dump text:
  `Instagram is blocked` / `Blocked by your MyExodus rules` / `Open MyExodus Recovery` /
  **`Close`** (bounds `[321,993][399,1033]`).
- Tapped Close (360,1013) → launcher Home (`topResumedActivity=...QuickstepLauncher`).
- Re-opened MyExodus and tapped the Home tab; first sighting of the Limited banner below.

## Home "Protection is limited" banner — known UiAutomation teardown, re-proven on the handset

Re-confirmation (not a new root cause) of the 2026-11-01 AVD finding recorded in the
device-testing memory: `uiautomator dump` registers UiAutomation, which tears down
`BlockerAccessibilityService` (`onDestroy` → `connected=false` → POLLING_ONLY) and rebinds it
afterward. Observed repeatedly on the X6532: Home chip `Limited` + `The accessibility service
isn't running… Re-enable` while the same second's FGS heartbeat logged `authority=FULL`, the
service was `Bound`, and blocking worked. Chain of proof:

1. **Authority writes:** `_authority` is written only by `ProtectionStatusMonitor.refresh()`;
   `a11yAlive` is only `BlockerAccessibilityService.connected.get()`. That flag is set false
   **only** in `onDestroy` (line 363) and true only in `onServiceConnected` (line 126). Grep
   confirms no `disableSelf()` / self-unbind anywhere in `app/src/main/java` — the app never
   takes its own service down.
2. **The flag really does flip:** FGS's own Path-B marker (`authority regained — widening
   lookback…`) fired at 06:23:25 and 06:26:09 (POLLING_ONLY windows), while platform
   `dumpsys accessibility` stayed `Bound services: {...MyExodus blocker}` and
   `Enabled services: {…BlockerAccessibilityService}` at every sample — so the **service
   instance** is torn down and recreated (destroy → connected=false → POLLING_ONLY →
   recreate → connected=true → FULL), the platform bookkeeping never showing an unbind.
   A single log pair caught the whole cycle in 1.6 s:
   `06:31:34.861 protection OK -> DEGRADED_NO_DETECTION (authority=DEGRADED_NO_DETECTION
   cause=overlay …)` then `06:31:36.505 protection DEGRADED_NO_DETECTION -> OK
   (authority=FULL cause=path_a …)`.
3. **The UI is live, not stale:** an overlay app-op deny→allow probe repainted Home to
   `Blocker off` / `Protection is paused` and back; a plain Settings↔Home tab round-trip
   (no uiautomator) rendered `Protecting` with no banner. So each banner render is a genuine
   POLLING_ONLY instant.
4. **Harness causality, re-proven on the handset:** most flips landed within ~1 s of a
   `uiautomator dump` (06:23:24 dump → 06:23:25 flip; 06:32:43 flip right after a dump;
   06:35:58 dump caught the banner after the 06:35:52 tick read FULL). **Three idle minutes
   with logcat only produced zero flaps and FULL ticks throughout** (06:32:54–06:35:54).
   Counter-example: the 06:26:09 flip had no dump in the preceding 3 minutes (the
   `wm density` config change at ~06:23:00 is the only nearby system event; the 2026-11-01
   record lists the AVD rebind at 1–2 s, while the X6532 windows ran tens of seconds, and
   XOS may also rebind on its own). Leaving the phone alone keeps `Protecting`.
5. **Settings vs Home divergence explained:** Settings' Protection row reads platform grants
   (`readAttentionCount` inputs: service-enabled flag, usage access, battery, overlay,
   integrity) and says "Everything we can check is on"; Home reads the live service
   instance. During a transient instance gap the two disagree by design; Home is the more
   sensitive surface and self-heals on reconnect.

Rulings: no app defect; no permission was switched off by the app or the user; force-stop
(`am force-stop`, USER-STOP detection → `APP_STOPPED`) remains the other, user-facing
mechanism that clears accessibility enablement (prior session). Observability gap noted:
POLLING_ONLY windows leave no log line (transitions log only on degradation change), so
these flips are visible only via the FGS Path-B marker.

Tooling rule for this device: when App Lock is off (window not `FLAG_SECURE`), verify UI
state with `screencap` + image inspection; reserve `uiautomator dump` for node queries you
cannot answer otherwise, and do not use it to judge protection status.

## Rule counts

`MyExodusRules: Authoritative snapshot v104: 1 app, 32 url rules` — matches the 32-item
website batch. The earlier `0 url rules` sighting was pre-unlock/transient (vault-locked
load); no mismatch remains.

## Housekeeping verified

- Releases repo: `policy-000016` is `latest` (signed OFFER versionCode 41 / 1.1.39, 100 %);
  `artifact-v41` asset digest matches the built APK
  (SHA-256 `484fd5a7fa7974dd56ce400383c1a720b2e6cbd4202d437c567919f67d750230`,
  39,251,849 B) — already recorded in `app-update-channel.md` §1/§14.
- `~/Documents/MyExodus Release/` holds exactly `MyExodus-1.1.39-v41.apk`; stale debug
  APKs were removed from `app/build/outputs`.
- Device-side probe artifacts (`/sdcard/ui*.xml`, `/sdcard/s1.png`) deleted; rotation guard
  run after the last injected tap.
