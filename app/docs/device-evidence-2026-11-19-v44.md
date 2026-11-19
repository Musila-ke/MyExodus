# Device evidence — v44 / 1.1.42 wall Close evict legs (2026-11-19 ~16:31–16:35 EAT)

Device: X6532, Android 14 (API 34), v44/1.1.42 installed with `adb install -r` (`versionCode=44
versionName=1.1.42`, `installerPackageName=null`, `firstInstallTime=2026-11-15 17:13:06`
preserved). Wireless ADB over mDNS (address withheld from the repo). Accessibility enabled and
bound throughout (`authority=FULL` ticks before and after every leg).

Scope note: the code change under test (ADR #316) is `OverlayController.dismissWall` running the
host's close **before** hiding the overlay card. On a healthy run the overlay card hands off to
`BlockerActivity` within ~1 s, so the visible surface for these legs was `BlockerActivity`, whose
own Close (`leaveToHome`) predates the change and was verified unchanged. The card's
close-before-hide ordering and both wall kinds are pinned by the JVM regression tests
(`wallClose_closesTheAppBehindTheWall_beforeHiding`, `bannerDismiss_neverRunsTheWallClose`).

## Leg 1 — Instagram block wall Close — PASSED

- Instagram launched; wall rendered: `Instagram is blocked` / `Blocked by your MyExodus rules` /
  `Open MyExodus Recovery` / **`Close`** (bounds `[321,993][399,1033]`),
  `topResumedActivity=...BlockerActivity`.
- Close tapped (360,1008): `topResumedActivity` returned to the launcher, no Instagram resumed
  (`dumpsys activity activities`), `MyExodusOverlay: hidden display=0` logged the same second.
- The automatic attach-confirmed close log from the block moment shows the healthy suppressed
  handoff: `block close pkg=com.instagram.android evicted=false wallFront=false launching=true
  root=null` — the FGS's durable wall was in flight, so HOME was correctly withheld; the wall
  then surfaced and carried the Close.

## Stress — 3 further block/close cycles — PASSED

- Each cycle: launch Instagram → blocked → ended on the launcher with no resumed app.
- Logs per cycle: either `evicted=true … root=com.instagram.android type=1` (automatic close
  performed the HOME evict) or the suppressed-handoff line above followed by the wall surfacing.
- No spurious extra eviction was logged after a healthy handoff — the v44 re-run gate (still the
  active root?) declined, as designed.

## Not exercised — stated, not claimed

- The XOS pause wedge itself did not recur, so the re-run's **positive** branch (fires the extra
  close when the wall never surfaced and the app is still the active root) has no live firing
  this session; it is pinned by the JVM tests and the gate logic only.
- Overlay-card Close (the changed ordering) was not the visible surface today (healthy runs hand
  off to `BlockerActivity` quickly); the ordering is JVM-pinned.

## State restored

Launcher front, accessibility bound, FGS tick `authority=FULL degradation=null`, rotation guard
run and verified (`accelerometer_rotation=0`, `user_rotation=0`).
