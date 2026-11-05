# Production test — 2026-11-01

Live-user end-to-end verification. Evidence only: every row below is a screenshot,
logcat line, on-device test result, or API response. No outcome is claimed without one.

## Targets

| Target | Role | Constraint |
|---|---|---|
| X6532 (`LAN-ADDRESS-REDACTED:5555`) | Owner's real device | Reversible actions only. Real data must survive. Never uninstall. |
| X6532C (`192.0.2.105:5555`) | Second phone | Full testing, per-action owner permission granted this session. |
| MyExodus_API37 (`emulator-5554`) | Disposable | Identity-destructive flows only (onboarding, account create/delete, backup restore). |
| Live backend | Firebase project `myexodus-53804` | Partner legs run live. |

## Baseline (captured 2026-11-01 14:11)

- All three installs: `versionName=1.1`, `versionCode=2`. AVD last updated 2026-10-31 16:26; X6532 2026-11-01 13:36; X6532C 2026-11-01 13:37.
- X6532: home = "Days free from New AC", 9 days, free since Oct 22, next milestone 30-day mark, milestones Day 9 of 90, status "Protecting". Accessibility service `BlockerAccessibilityService` enabled.
- X6532C: home = status "Limited" (accessibility service not running), pinned partner message "From X6532 — Thinking of you today" sent 1h ago, no habit tracked.
- AVD: stale guest session from prior instrumentation (r318 habit, 1 day, Blocker off, "Protection is paused"), no `debug_backend` prefs (live backend), App Check debug token `7ab5dfc0-…` (value redacted; it lives in the AVD's debug-token prefs, never in the repo).
- Live pair active: X6532 ↔ X6532C (X6532 sent the visible message).

## Results

### 1. Onboarding (AVD, live backend, fresh data 14:13)

Setup used: `pm clear`, Usage access denied via appops, overlay denied via appops, POST_NOTIFICATIONS revoked, accessibility unset.

| Step | Action | Observed | Evidence |
|---|---|---|---|
| 1/5 Usage access | zero state | "Not allowed yet", Skip + "Open Usage Access settings" | `/tmp/avd_setup_1_usage.png` |
| 1/5 | Skip | advanced to 2/5, no crash | `/tmp/avd_setup_2_disclosure.png` |
| 2/5 Disclosure | text + Back/Continue, no Skip | copy matches `SetupScreen.kt` | `/tmp/avd_setup_2_disclosure.png` |
| 3/5 Accessibility | zero state | "Not allowed yet", Skip present | `/tmp/avd_setup_3_a11y.png` |
| 3/5 | Open settings → toggle on → Allow | system dialog "Allow MyExodus blocker to have full control"; service listed after grant | `/tmp/avd_a11y_confirm.png`, `settings get secure enabled_accessibility_services` |
| 3/5 | return | live "Granted", Skip replaced by Next | `/tmp/avd_setup_3_granted.png` |
| 4/5 Notifications | Allow → Don't allow | `POST_NOTIFICATIONS: granted=false`; button correctly fell back to "Open notification settings"; Skip remained | `/tmp/avd_notif_denied2.png` |
| 4/5 | settings → toggle on → back | "Granted", Next | `/tmp/avd_notif_granted.png`, `granted=true` |
| 5/5 Overlay | Open settings → allow → back | "Granted", Done | `/tmp/avd_setup_5_granted.png`, `/tmp/avd_setup_5_done.png` |
| Finish | Done | FGS foreground (`EnforcementForegroundService isForeground=true`); Home shows pause banner + usage-access warning + "Blocker off"/Limited | `/tmp/avd_home_after_setup.png` |
| Recovery | Re-enable on pause banner | banner cleared; status "Limited" (usage still off) | `/tmp/avd_home_resumed.png` |
| Usage fix | "Turn on" → settings → allow | status "Protecting"; both warnings gone | `/tmp/avd_home_usage_on.png` |

Note: the paused state appeared immediately after onboarding. Cause not yet established — logged for root-cause follow-up (not a pass).

App Check: new AVD token `4cb7dde5-…` registered after pruning five stale AVD debug tokens (cap is 20). No phone token touched: `DebugAppCheckProvider.kt:41` installs the debug provider only on emulators, and neither phone has a debug-token prefs file.

### 2. Blocking engine — X6532C, live backend (15:29–15:41)

App-side status diagnosis first: X6532C's "Limited" pill during earlier dumps was a
**testing-method artifact**, not an app state. Each `uiautomator dump` registers Android's
UiTestAutomation service, which unbinds our accessibility service for the dump window;
`MyExodusProtection` logged `protection FULL -> DEGRADED_NO_DETECTION (cause=usage_access+overlay)`
at 15:27:29.511 and the reverse at 15:27:31.910, and FGS ticks read `authority=FULL` throughout.
`screencap` (which does not register UiAutomation) showed a green "Protecting" pill. From here,
status is verified by screencap, never by a dump taken during the observation.

| Step | Action | Observed | Evidence |
|---|---|---|---|
| Create | Rules → Add app → Calculator → Block all day toggle → Save | saved; `MyExodusRules: Authoritative snapshot v17: 1 app, 0 url rules` | `/tmp/x6532c_rules_tab.png` |
| Enforce (Path A) | open Calculator | `MyExodusBlocker: detected com.transsion.calculator — launching blocker`; BlockerActivity "Calculator is blocked / Blocked by your MyExodus rules" with "Open MyExodus Recovery" and "Close" | device screen, `/tmp/repro_x6532c.log` |
| Recovery | Open MyExodus Recovery | Recovery tab: "Blocked: Calculator", live friction countdown (~60 s), "Log an urge", "Use it now (override)", "Overrides are logged on this device." | device screen |
| Urge log | Log an urge → 5 → Log it | sheet copy "Logging keeps the block in place — this is not an override. Nothing here is shared with your partner."; sheet closes and routes Home | device screen |
| Timer expiry | countdown reached 0 while the sheet was open | `unlockRule()` (RecoveryScreen.kt:194-203) sets `blockExpiresAt = now-1`; `MyExodusRules: v18: 0 app` at 15:32:00.434; next Calculator open `decide com.transsion.calculator gen=1 -> Allow` 15:32:22 | `/tmp/repro_x6532c.log` |
| Re-add blocked | Add app → Calculator → Save | "That's already in your block list. Edit the existing block instead." — the row was never deleted | device screen |
| State | scroll Rules → Deactivated | **Calculator · Always on · until Nov 1, 2026 · Expired**, toggle off, "Remove all" | `/tmp/x6532c_deactivated.png` |

**Resolution of the 15:32 "rule vanished" investigation: not a defect, not a deletion.**
The friction countdown is the actor: when it reaches zero it expires the rule by design
("the block lifts when it ends"), which removes it from enforcement and from the Apps section
(enabled-only) and moves it to the Deactivated section — below the fold, which is why the
first read of the Rules screen wrongly concluded "gone". No rule-delete path was invoked;
`getByProfileAndPackage` still found the row, and the Deactivated row above renders it.
Reproduction confirmed in the same session (Save rejected as duplicate at 15:40:27).

Open UX finding (not a functional defect; matches the owner's 2026-11-01 request, task #9):
the timer's expiry retires an all-day block with no way to keep it. Copy does state the
outcome in advance, but the only in-flow choices are wait, log an urge, or override.

Stale pairs — resolved as a finding, not a pass: the rule editor lists four partner rows all
named "X6532" with **no date or hint** (`RuleEditor.kt:639-704` renders `partner.name` only),
so the live pair is not identifiable there. The "People you support" switcher *does* show a
paired-since date (Oct 31 / Oct 4 ×3), but the three harness leftovers share one date and are
still indistinguishable from each other. Delivered to the newest row, so the assignment was
correct; the user has no way to know that from the editor.

### Batch overlap matrix — X6532C (16:32–16:49)

The duplicate/overlap slice: does a batch respect the standalone block list, and does its
master switch actually govern enforcement?

| Step | Action | Observed | Evidence |
|---|---|---|---|
| Picker exclusion | Add batch → app picker → search "calc", "bolt", "camera" | "No apps match" for Calculator and Bolt (each already has a standalone rule); Camera listed with its checkbox — matches `AppMultiPickerDialog(exclude = existingAppKeys)` (`BatchEditor.kt:600-601`, filter at `AppPicker.kt:182`) | `/tmp/batch5.png`, `/tmp/batch6.png`, `/tmp/batch7.png` |
| Create | Name "Overlap test", pick Camera → Save | card saved: "1 item", "Always on", "Blocked now", "Not shared with a partner" | `/tmp/batch_on_again.png` |
| Standalone duplicate | Add app → Camera → Save | Save rejected: "That's already in your block list. Edit the existing block instead." (`strings.xml:490`, `RuleEditor.kt:213`) — the guard covers batch membership, not just rule rows | `/tmp/overlap7.png` |
| Enforce | open Camera | "Camera is blocked / Blocked by your MyExodus rules" wall | `/tmp/cam_block1.png` |
| Sweep off | toggle "Overlap test" off | card: toggle off, member row reads "Paused"; launching Camera opens the real camera UI, `mCurrentFocus` = `com.transsion.camera/com.android.camera.CameraLauncher` (dumpsys in-session) | `/tmp/batch_off.png`, `/tmp/cam_batch_off.png` |
| Sweep on | toggle back on | card: toggle on, "Blocked now"; launching Camera shows the wall again | `/tmp/batch_on_again.png`, `/tmp/cam_batch_on.png` |

**Finding (not fixed): a batch member has no edit path, but the duplicate rejection tells the
user to edit it.** The rejection copy is shared by every duplicate source, yet for an app that
belongs to a batch there is nothing to edit: `BatchCard` receives only `onToggle`,
`onRemovePartner`, `onDelete` (`RulesScreen.kt:909-918`, signature at `:1048-1057`), its member
rows render through `BatchMemberRow` with no click handler (`:1149-1182`), the batch route
renders the create-only `BatchEditorScreen` with no batch parameter (`:423-443`;
`BatchEditor.kt:87-99`), and batch members are excluded from the editable Apps list by
`batchId == null` (`RulesScreen.kt:901`). The only recourse for that user is "Delete batch"
and recreate — which also drops the partner assignment and sends that partner a delete event.
Copy so far: the editor should say the app is inside the named batch (and, if batch editing
stays out of scope, that it must be deleted and rebuilt). Owner ruling needed on whether
batch editing is in scope at all.

### Schedule, day scope, and Ends — X6532C (16:50–16:58)

Rule under test: Bolt (`ee.mtakso.client`), overnight window; no website rules existed during
this slice (snapshots v36–v39 all read "3 app, 0 url rules"), so the accessibility engine was
the only enforcer.

| Step | Action | Observed | Evidence |
|---|---|---|---|
| Outside window | window 10 PM–7 AM; open Bolt at 16:51 | `decide ee.mtakso.client gen=46 -> Allow`; Bolt's own splash loads | `/tmp/bolt_outside_window.png`, logcat |
| Inside window | edit From to 4:00 PM → Save (snapshot v37) | launch at 16:52:38 logs `detected ee.mtakso.client — launching blocker`; wall "Bolt is blocked / Blocked by your MyExodus rules" | `/tmp/bolt_in_window.png`, logcat |
| Day scope out | deselect Sunday → Save (v38); the 4 PM–7 AM window still covers now | `decide ee.mtakso.client gen=54 -> Allow`; Bolt opens to its own onboarding | `/tmp/bolt_sun_off.png`, `/tmp/bolt_sun_off_open.png`, logcat |
| Restore | re-select Sunday + 10 PM–7 AM → Save (v39) | Rules row returns to "10:00 PM–7:00 AM (overnight) · Not active now" | `/tmp/bolt_fully_restored.png` |
| Ends set | Ends → pick Nov 1 → OK → Save | editor and row persist "until Nov 1, 2026" | `/tmp/bolt_ends_set.png` |
| Ends clear | Ends → Clear → Save | editor returns to "Ends Never" | `/tmp/bolt_clear_save2.png` |

Day scope is a whole-rule switch applied on top of the window (Sunday off disables the rule even
while the window is active). No defect in this slice; the end-of-day storage and expiry behavior
are covered by the website control below.

### Website rules — DNS layer vs accessibility layer — X6532C (17:01–17:26)

Rule under test: `example.com`, all-day, Ends Nov 1 2026. The app's own VPN consent was
already granted; the tunnel is product-driven (`VpnLifecycle` starts it when ≥1 live website
rule exists and stops it when none remains — adb cannot send the STOP intent, which requires
`BIND_VPN_SERVICE`).

| Step | Action | Observed | Evidence |
|---|---|---|---|
| DNS enforce | all-day rule on | `tun0` up (`inet 192.0.2.1/32`); `ping example.com` → `unknown host`; `MyExodusVpn: nxdomain example.com v4 rule=policy written=true` | `ip addr`, logcat |
| Counterfactual | toggle the rule off | `tun0` gone; `ping example.com` resolves; Chrome loads the page | `/tmp/web_ab_9.png`, logcat |
| Scheduled rule, out of window | set 10 PM–7 AM; row reads "Not active now" | still `unknown host example.com`; a cache-busted Chrome load shows the raw `DNS_PROBE_FINISHED_NXDOMAIN` page, while the scanner logs `'example.com' -> Allow` | logcat |
| Live wall | open `example.com/?v=3` in Chrome | scanner `'example.com/?v=3. Connection is not secure' -> Block`; wall "example.com is blocked / Blocked by your MyExodus rules" with both actions | `/tmp/different_site_1.png` |
| Different site | tap "Go to a different site" | Chrome focused, `about:blank` loaded in the same tab (tab count unchanged); wall cleared | `/tmp/different_site_2.png` |
| Return | bring Chrome to the front | wall re-shows over the blocked tab ("Blocking resumes when you come back") | `/tmp/different_site_4.png` |
| Recovery from the wall | tap "Open MyExodus Recovery" | Recovery tab: "Blocked: example.org", friction timer at 0:43, "Log an urge", "Use it now (override)" | uiautomator dump |
| Timer end | let the countdown reach 0 | session closes to "No active block"; the rule expires (`unlockRule` writes `blockExpiresAt = now-1`), moves to Deactivated as "Always on · until Nov 1, 2026 · Expired"; `tun0` goes down | `/tmp/web_timer_rules.png`, uiautomator, `ip addr` |
| Expiry control | fresh `example.org`, Ends = Nov 1, saved 17:21 | row reads "Blocked now" with "until Nov 1, 2026" — the picker stores 23:59:59 local, so no early expiry | `/tmp/exp1_10.png` |
| Cleanup | Deactivated → Remove all → Remove | "2 rules will be deleted permanently"; Websites returns to empty; tunnel stays down | `/tmp/remove_confirm.png`, `/tmp/removed_clean.png` |

**Correction to an earlier reading:** the website wall's "Open MyExodus Recovery" does start a
real session — the earlier "No active block" was observed after that rule had already expired
(the friction timer rewrote its expiry), not because website sessions are unsupported.

Findings:

1. **[HIGH — owner ruling] The DNS layer over-blocks schedule-only website rules.** A rule whose
   window is 10 PM–7 AM (UI: "Not active now") still answers NXDOMAIN all day, so the site is
   unreachable outside its window and the wall never explains why. This is the documented
   `DomainBlocklist` ceiling (ADR #66): the DNS clock is used for expiry only, never for
   time-of-day windows; the accessibility scanner enforces the window accurately (`Allow`).
   Options: keep the DNS blocklist to always-on rules only and let the scanner own schedules
   (smallest change, some window-edge exposure), or make the UI copy admit the broad block.
2. **[MEDIUM/HIGH UX] A DNS-only block has no MyExodus surface.** The user sees Chrome's raw
   "This site can't be reached / DNS_PROBE_FINISHED_NXDOMAIN" — the exact confusion the owner
   voiced in this session — while an app block gets the explained wall. Any DNS answer (or
   browser-side interstitial) that names the block would close the gap.
3. **[Noted, by design] The friction timer rewrites a website rule's Ends.** Reaching 0 expires
   the rule the same way it does for apps; the Deactivated row then reads "until <today> ·
   Expired". This is the accounting for the earlier "rule vanished" alarm: expiry by the timer,
   not deletion.
4. **Method:** the Recovery tab is a FLAG_SECURE privacy surface (`screencap` returns an empty
   file; verify by uiautomator dump); Chrome's cache can serve a blocked host while DNS already
   answers NXDOMAIN (use a cache-busting query); the omnibox text may carry suffixes
   ("Connection is not secure") but still canonicalizes to the rule's host.

### Daily-limit mechanism (website meter) — X6532C, live (17:50–18:00)

Two website rules created through the editor: `example.org` 2m/day and `somafm.com` 1m/day
(both Every day, no partner). Tunnel up for both.

| Step | Action | Observed | Evidence |
|---|---|---|---|
| Static page, no traffic | open `example.org`, leave foreground 2m15s | row still "Used today: 0 / 2 min" — the meter counts active connection chunks (copy says so), not wall time | uiautomator dump |
| Real stream | open `https://ice1.somafm.com/groovesalad-128-mp3` in Chrome, tap play | media session `state=PLAYING(3)`; after ~75 s row reads "Used today: 1 / 1 min" (exhausted) and example.org reads 1 / 2 min from its backgrounded tab's live socket | `/tmp/dl_stream0.png`, `dumpsys media_session`, uiautomator dump |
| Warning | threshold at 90% of a 1-min limit | notification id 3 on `domain_usage_status_v1`: "Website limit almost used up / somafm.com: less than a minute left today. Tap to review your rules." | `dumpsys notification --noredact` |
| Exhaustion | bring Chrome back after the limit | full-size website wall: "ice1.somafm.com is blocked / Blocked by your MyExodus rules" + Recovery + different-site; stream data stopped | `/tmp/dl_stream_after_limit.png` |

**Owner question: "wasn't there a popup also?"** Two different surfaces exist:

- **App daily limits — yes, an in-app popup banner.** `BlockOverlayView` renders
  `OverlayContent.Warning` over the app ("About N min of today's time left" at the 50%
  threshold, "Almost out of time — about N min left" at 90%), strings
  `limit_warning_title`/`_final`; the overlay is the accessibility service's own window.
- **Website daily limits — no popup, notification only.** `AndroidDomainUsageHost`
  posts the warning notification (id 3) and nothing renders on screen; the owner asked for
  the popup here too. Logged as finding 8 below.

The wall in this run rendered clean at full size; the audio/media session was unrelated to
the earlier wall animation finding (which remains a `BlockerActivity` window-animation issue,
see below).

### Uninstalled-app rule hiding — AVD, fresh build (18:06–18:07)

Round-trip on emulator-5554 after installing the 18:05 build (`lastUpdateTime` advanced;
the earlier failure was a stale APK from 2026-10-31 that predated commit ac60835).

| Step | Action | Observed | Evidence |
|---|---|---|---|
| Start | Clock rule (`com.google.android.deskclock`, Block all day) exists; app uninstalled for user 0 (`pm uninstall --user 0`, package still listed by `pm list packages -u`) | Rules → Apps shows only Chrome; caption: "1 block is saved but hidden — its app isn’t installed. It returns when you reinstall the app." | `/tmp/avd_rules_hide1.png` |
| Reinstall | `pm install-existing com.google.android.deskclock`, switch tabs away and back | Clock row returns immediately ("Always on · Blocked now"), caption gone — `ACTION_PACKAGE_ADDED` re-query works without restart | `/tmp/avd_rules_hide2.png` |

PASS. Blocking sanity in the same session: the Clock rule still decided and evicted on launch
(`decide com.google.android.deskclock -> Block`, `block close ... evicted=true`) while the
temporary Phone rule existed, and the dialer case is covered above. The temporary Phone rule
was deleted after the A/B (`Delete this block?` dialog → confirmed), leaving the AVD with its
pre-existing Chrome + Clock rules.

### App daily limits — AVD, live (18:22–18:33)

First attempt (Files, 1m/day, 18:24–18:26) produced **no** warning, no wall, and no
`MyExodusUsage` log while Files stayed foreground 70+ s. Root cause found in the service logs,
not the limit pipeline: the app was still in the paused state left by the 18:13 reinstall —
FGS tick `degradation=APP_STOPPED`, i.e. `ProtectionStatusStore.requiresExplicitReEnable=true`
(`ProtectionStatusMonitor.visibleDegradation` ranks that flag first). Re-enabling the a11y
service through system Settings does **not** clear it; only the user's explicit Re-enable does.
`sessionTracked` deliberately refuses to open usage sessions while paused ("Time limits stay
paused until you re-enable", ADR #160), so no accounting ran. The Home banner showed the state
correctly ("Protection is paused — Something on your phone closed MyExodus… Re-enable",
`/tmp/avd_home.png`). Tapping **Re-enable** at 18:29:37 cleared it — FGS logged
`re-enable requirement cleared — running credit-edge audit`, pill changed "Blocker off" →
"Protecting" (`/tmp/avd_home_reenabled.png`).

Re-run after re-enabling, Files rule 1 min/day, all times from the same logcat capture:

| Step | Observed | Evidence |
|---|---|---|
| Launch Files (18:29:49) | WSC → `decide ... gen=1 -> Allow` / `gen=2 -> Allow`; session opens on the Allow transition | logcat `MyExodusBlocker` |
| 30 s | `observe com.google.android.documentsui +10002ms -> 30034ms/60000ms ShowWarning(level=1, remainingMs=29966)`; banner over the app: "About 1 min of today's time left" | `/tmp/files_t35.png` |
| 60 s | `observe ... +10001ms -> 60039ms/60000ms Block`; `block close pkg=com.google.android.documentsui evicted=true`; wall "Files is done for today / Today's time limit is used up. It resets tomorrow." + Close | `/tmp/files_t75.png` |
| Close → relaunch | `observe ... +0ms -> 60049ms/60000ms Block` within ~200 ms; `evicted=true`; wall re-armed | `/tmp/files_reentry.png` |
| Delete rule → relaunch | snapshot publish `v11: 3 app, 0 url rules`; Files launch `decide ... -> Allow`, no wall, normal Downloads UI | `/tmp/files_unblocked.png` |

PASS. Two observations, neither a defeat:

- **Level-2 warning (90%) is unreachable for a 1-minute limit** with the 10 s ticker: the tick
  at 50 s reads 50,039 ms (< 54,000), and the next tick at 60 s is already `Block`. Only limits
  shorter than ~100 s can skip level 2 this way; ≥10-minute limits warn at 10 and 5 min of
  remaining budget and are unaffected (FR3 as designed).
- Debug-only log copy: `session watchdog: tracked foreground without session
  pkg=com.google.android.apps.nexuslauncher` prints for any foreground package without a
  session **before** `reacquireUsageSession()` checks `isLimitTracked`; the launcher correctly
  never gets a session (no rule), only the wording is misleading. Cosmetic.

Both temporary AVD rules (Files and Calendar, each 1m/day) were deleted afterwards; the AVD is
back to its pre-existing Chrome + Clock rules.

### Finding FIXED (2026-11-02): block wall slides in instead of appearing instantly

Owner report (X6532): "theres adilay in the sizing of the block screen making it look narrower
than it should … should it goes back to its original size but still". Reproduced on X6532C
(Calculator, all-day rule) with a `screenrecord` of the blocked launch:

| Frame | Time | What the frame shows |
|---|---|---|
| 46 | 3.071 s | cream wall starts covering the calculator from the right edge — `/tmp/bf2/f050.png` |
| 54 | 3.267 s | only a ~75 px strip of the calculator remains at the left — `/tmp/bf2/f054.png` |
| 58 | 3.344 s | wall settled, full width — `/tmp/bf2/f059.png` |

So the "narrower" phase is a **right-to-left slide over 273 ms**, not a resize of the settled
screen. Control run with `window_animation_scale` / `transition_animation_scale` /
`animator_duration_scale` = 0: the same launch cuts launcher → calculator → full-width wall
with **zero partial-coverage frames** (`/tmp/block0.mp4`, scales restored to 1.0 in the same
command). That proves the phase comes from the platform window animation, not app compositing.

Cause: `BlockerActivity` declares no theme and no `windowAnimationStyle` (AndroidManifest.xml:169-177),
and `Theme.MyExodus` declares none either (themes.xml:8-10), so a new-task launch inherits the
platform/OEM activity-open animation. The accessibility-overlay host explicitly opts out
(`lp.windowAnimations = 0`, OverlayController.kt:364, "bridge must appear instantly, not scale
in" at :361), so the two enforcement hosts transition differently. During those 273 ms the
blocked app remains visible.

**Fix applied (2026-11-02).** `BlockerActivity` now uses `Theme.MyExodus.Blocker`, whose
`android:windowAnimationStyle` is `Animation.MyExodus.None` — all four activity-open/close
animations set to `@null` (`themes.xml`), so the platform has no animation to play.

Evidence on the AVD (`emulator-5554`), structural rather than frame-level: `dumpsys window`
on a blocked launch shows `wanim=0x7f120005`, and `aapt2 dump resources` resolves that id to

```
style/Animation.MyExodus.None
  0x010100b8=@null  (activityOpenEnterAnimation)
  0x010100b9=@null  (activityOpenExitAnimation)
  0x010100ba=@null  (activityCloseEnterAnimation)
  0x010100bb=@null  (activityCloseExitAnimation)
```

so the wall is composed at full size on the first presented frame. **Frame-level re-capture
on hardware is BLOCKED** (X6532/X6532C detached) and cannot be faked on the AVD: this AVD
carries all three animation scales at 0 as a harness artifact, so a re-capture there proves
nothing about the slide. The 273 ms slide was measured on X6532C pre-fix; the fix removes the
mechanism the control run isolated. Upgrade when hardware is attached: record a blocked
launch and confirm no partial-coverage frame.

**ANR during that capture — harness artifact, not an app defect.** While recording a blocked
launch on the AVD with `screenrecord` running, the platform raised "MyExodus isn't
responding" and the wall did not appear in that capture. Diagnosis from the platform SIGQUIT
dump (`/data/anr/traces` → `trace_07`, copied before the relaunch): the main thread was
**idle** in `android.os.MessageQueue.nativePollOnce` → `Looper.loopOnce` (state S), i.e.
waiting for work rather than wedged in app code. That is a transient input-dispatch stall
under the capture process's CPU load on an 8 GB Intel host running an API-37 image, not an
app lock. The dialog was dismissed with Wait (`input tap 331 1343`) and `dumpsys window`
confirmed zero "Application Not Responding" windows immediately after; the relaunch showed
the wall normally. Recorded here so it is not later mistaken for a product finding. The same
rig behaviour recurred after the fix pass (15:00–15:07): with the host still finishing lint,
the guest showed "Process system isn't responding" and ANR-killed the app at startup three
times (`am_anr … failed to complete startup`); the ANR trace again had `main` idle in
`MessageQueue.nativePollOnce`, and the input log showed the launcher's own gesture monitor
timing out on the same backlog. Once host load fell and the guest clock re-synced, the app
launched (21.6 s cold start) and Home rendered with the "Protecting" chip. Rig artifact,
not an app regression.

### Life-safety defect (CRITICAL, open): a block rule on the Phone app defeats the dialer exclusion

Run 17:31–17:33 on X6532C. Steps: Rules → Add app → picker → search "phone" → **Phone**
(`com.sh.smart.caller`, the live default dialer: `cmd package resolve-activity -a
android.intent.action.DIAL` and `cmd role get-role-holders android.app.role.DIALER` both name
it) → Block all day → Save. The rule saves normally; nothing warns that the dialer is
protected.

| Attempt | Action | Observed | Evidence |
|---|---|---|---|
| 1 | `am start -a android.intent.action.DIAL` | dialer splash, then `MyExodusBlocker: decide com.sh.smart.caller gen=7 -> Block`; overlay shown; `block close pkg=com.sh.smart.caller evicted=true`; launcher returns | logcat, `/tmp/em_dialer.png` (launcher) |
| 2 | force-stop dialer, HOME, `am start -a android.intent.action.DIAL` | reproduced: dialer splash at 1.0 s, launcher by 2.8 s; `block close pkg=com.sh.smart.caller evicted=true` | `/tmp/dial_block1.png`, `/tmp/dial_block2.png`, logcat |
| Control | disable the rule, force-stop dialer, DIAL again | dialer opens and stays (`mCurrentFocus=com.sh.smart.caller/...MainActivity`) | `/tmp/dialer_restored.png` |

The exclusion itself is loaded correctly: `MyExodusEmergency: Emergency exclusion refreshed:
dialer=[com.sh.smart.caller] roles=[]` (17:25:49). The gate is bypassed, not the data.

**Root cause:** the emergency check runs only on the event-entry path
(`BlockerAccessibilityService.kt:231`) and is conditioned on
`isActiveForeground(packageName, window)`. During an app-launch transition the first WSC arrives
before the dialer window is active (XOS window-list lag), so the gate is skipped and the event
is deferred (`:310-315`). `deferredWsc` then re-decides at `:859` when
`rootInActiveWindow` finally names the dialer — calling `decideApp` directly, with **no
emergency re-check**. `decideApp` itself (`:411`) has no gate. A protected package therefore
reaches the Block branch, the cover attaches and `closeBlockedApp` force-closes it.

This is the one scenario the §12 life-safety suite never covered: it launches the dialer while
a *different* app is restricted, so the dialer is never a tracked package. A user can make it
tracked in two taps. The fail-open contract (Decision 9: exclusion "runs first,
unconditionally, before every other step of the event pipeline") is violated on any protected
package whose own rule exists.

**Fix queued (task #8, not applied during this testing pass):** re-apply
`emergency.isProtected(packageName, window)` at the top of `decideApp` (the single decision
choke point both paths use) — hide the cover and return; plus a device-test leg that seeds a
rule on the live default dialer package and asserts it is never overlaid. Until fixed, the
picker should not offer protected packages, or the save should refuse them.

**Fix status 2026-11-01 18:20 — applied, source- and bytecode-verified; device leg blocked.**
`BlockerAccessibilityService.kt:417-427` now re-checks `emergency.isProtected(packageName,
window)` at the top of `decideApp`, hiding the cover, closing the usage session and returning.
`EnhancedModeDeviceTest.lifeSafety_dialerWithItsOwnBlockRule_isNeitherWalledNorClosed()` seeds
a rule on every live dialer role holder and asserts no overlay + foreground dialer for 4 s.

* Bytecode check (dexdump 36.1.0 on `classes10.dex` of the rebuilt APK): `decideApp` begins
  `getEmergency()` → `isProtected(String, AccessibilityWindowInfo)` → `if-eqz` → `overlay.hide`
  → `closeUsageSession` → `return-void`, before `BlockDecisionEngine.decideApp`. The earlier
  18:13 no-fix build was verified fix-free the same way (it opened with
  `displayIdOrDefault` → engine decide), so the A/B binaries were genuinely different despite
  identical APK byte size.
* AVD A/B (emulator-5554, disposable): with the Phone rule active, cold-launching the dialer
  never reproduced the force-close **in either binary**. On this AVD the first WSC's deferred
  retries (6 × 400 ms) expire before `rootInActiveWindow` names the dialer (~3 s cold start),
  and the later direct WSC is caught by the entry gate at `:231` — so the defective
  `deferredWsc → decideApp` path is never reached here. The AVD therefore cannot validate
  this fix; a green run on it proves nothing. `/tmp/avd_{nofix,fix}_dialer*.png`, focus
  histograms 30/36 and 31/36 samples on the dialer activity.
* The instrumented regression cannot run on the AVD (the suite self-skips on emulators,
  `EnhancedModeDeviceSuite.kt:100-112`) and cannot run on either handset without wiping the
  live store (`resetData()` → `recovery.resetAllData()`, `:126-138`). **Open:** run the test
  on a handset the owner accepts wiping, or replay the manual X6532C scenario after installing
  the fixed APK (needs per-action permission; the seeded rule then must be deleted).
* Until that hardware leg passes, treat the defect as **fixed in code but not yet proven on a
  device**; the picker still offers protected packages.

**Secondary observation:** because eviction fires as soon as the cover attaches, the user sees
the dialer splash replaced by the launcher — no wall, no explanation (the wall is logged
`shown` and `hidden` within ~1 s). This is the same "blocked app just disappears" class as the
uninstalled-app/`block close` behavior, but here it hits an app the app promised never to block.

## 3. Partner surfaces — rule lifecycle, notifications, multi-user switching

Run 16:15–16:24 on X6532C (sender) → X6532 (receiver), live backend. A debug APK with the
feed-copy fix below was installed on both phones (`install -r`) at 16:15; data survived.

| Step | Action | Observed on X6532 (receiver) | Evidence |
|---|---|---|---|
| Create | Bolt, 10 PM–7 AM overnight, Tue–Sun, assigned to first X6532 row → Save | banner "1 update waiting to be sent"; after Send now + BiometricPrompt, tray: "Rule update from Musila" / "You were added as accountability partner on Bolt." | `/tmp/x6532c_editor_4x6532.png`, `/tmp/x6532c_sendnow_after.png`, `dumpsys notification` |
| Edit | remove Mon → Save | tray updated in place (same per-pair tag): "Rule update from Musila" / "Bolt was changed." | `dumpsys notification --noredact` |
| Disable | toggle off | row leaves Apps; Deactivated shows "Bolt · Paused · Watched by X6532"; tray: "Rule update from Musila" / "Bolt was turned off." | `/tmp/disable_t0.png`, `/tmp/rules_deactivated_rows.png`, `dumpsys notification` |
| Disable send | Send now | **no prompt** — 900 s signing window was warm; banner cleared | `/tmp/disable_sendnow.png` |
| Delete | editor → Delete → confirm "Delete this block?" | tray: "Rule update from Musila" / "Bolt was deleted." | `/tmp/bolt_delete_confirm.png`, `dumpsys notification` |
| Receiver page | Partner → Rules & blocks | "4 rule updates": Bolt · Rule deleted 1m / Rule turned off 3m / Rule updated 12m / Rule created 15m | `/tmp/x6532_partner_rules_after.png` |

Copy fix shipped this session (owner report: "at least mention that that is a block or rule,
not just saying Bolt"): the shared verbs now carry the noun — `partner_rule_created` →
"Rule created", `updated` → "Rule updated", `disabled` → "Rule turned off", `deleted` →
"Rule deleted", `assigned` → "Rule assigned to you", `unassigned` → "Rule no longer shared
with you", `expired` → "Rule ended" (`strings.xml:976-982`). One `AlertRow` renders both the
activity feed and the Rules & blocks page, so both changed. Verified on X6532 after install.

Multi-user switching from inside a subpage (owner-specified case): opened Rules & blocks,
tapped its own Switch, selected a stale pair → the subpage **stayed open** and re-scoped to
"No rule updates yet"; selected the live pair → scoped rows returned. Repeated on the "How
they're doing" page and left standing 8 s: no self-navigation back to the hub. Matches
`selectUser()` (`PartnerScreen.kt:409-415`; #96 comment "switching … must not also navigate
you away"). PASS.

### Rules ownership page — owner question 2026-11-01

Question: "is there a page where user can see the blocks and rules ie the partner the apps
or batches or website and the person or partner they belong to?"

Verified answer, two sides:

- **Owner's own inventory — YES.** The Rules tab is that page. It groups Batches, Apps,
  Websites, and Deactivated; every app/website row and every batch card carries an ownership
  line: "Watched by <partner>" (primary colour), "Not shared with a partner" (neutral, shown
  on every row whenever a partner is on the roster), or "Unsupervised" (error, the assigned
  pair is gone). Evidence `/tmp/x105_rules_scrolled.png`: Clock reads "Watched by X6532",
  Calculator/Bolt/Overlap test read "Not shared with a partner". Code:
  `RulesScreen.kt:1546-1560` (rows), `:1088-1110` (batch cards).
- **Partner's view of the rules they watch — NO inventory, only a change feed.** Partner →
  Rules & blocks lists events ("Clock · Rule created · 3m ago", "Bolt · Rule deleted · 1h
  ago"), not the current set: the empty copy is explicit ("When a rule or batch you watch is
  created, changed, turned off, or ends, it shows up here"), and nothing renders the apps or
  websites inside a rule/batch, schedules, or the person the rule is enforced against. If the
  owner wants the watching partner to see the live inventory (apps/batches/websites + owner),
  that surface does not exist yet and needs a product ruling.

### Open findings (not fixed; need owner rulings)

1. **Shared streak summary is anonymous.** Hub tile "How they're doing — 10 days free" and
   the summary "Days free · 10" name no streak, while the sender's own Home reads "Days free
   from New AC" and the feed rows on the same page name the habit ("PartnerTest · Relapsed ·
   ended a 1-day streak"). Cause: `ActivityStateAfter.habitLabel` exists on the wire
   (`EventPayload.kt:162`, v2/ADR #134) but `PartnerActivityReceiver` never stores it —
   `partner_activity_projection` is one row per pair with no habit column, and
   `applyStateAfter` keeps whichever event holds the highest sequence. With two shared habits
   (proven: New AC and PartnerTest both reach this pair) the number can silently belong to
   either. Fix requires a projection column + copy ("10 days free from New AC") + a ruling on
   multi-habit (per-habit rows vs. most-recent).
2. **Pending-delivery banner grammar.** With one queued event the title is correctly singular
   ("1 update waiting to be sent") but the body still reads "…to send **them** to your
   partner." (`rules_pending_delivery_*`). Cosmetic; every count uses the same body.
   **FIXED 2026-11-02:** the body is now a plurals resource (`rules_pending_delivery_body`),
   so the one-update case reads "to send it to your partner" and the many-case keeps "them".
   Device leg BLOCKED (needs a paired second phone to reach the banner); JVM build verified.
3. **"Viewing" context bar drops the date.** The hub card shows "Viewing X6532 · Since Oct 31,
   2026"; every subpage's context bar shows only "Viewing X6532", so after switching pairs
   there is no way to verify which same-named pair is selected.
4. **Message deep link lands where the message isn't.** Tapping the partner-event tray entry
   opened X6532C's Partner tab with the correct pair selected, but the 256-char message is not
   rendered anywhere on that surface — it lives only on Home's pinned card. (Earlier leg;
   `/tmp/x6532c_shade_expanded.png`, `/tmp/x6532c_longmsg_home.png`.)
5. **Rule updates wait on a manual "Send now" — owner ruled this a defect (2026-11-01).**
   Saving a partner-shared rule leaves "1 update waiting to be sent — Confirm with your device
   key to send them to your partner. Send now"; delivery only starts after the tap (and a
   BiometricPrompt on a cold signing window). Owner: "its dumb it should be automatuc cause we
   risk the user not even sending the update." Fix in task #8: sign and send in the
   background (no tap gate), surface and retry a stalled delivery. Memory:
   `project_rule_updates_automatic`. **FIXED 2026-11-02:** `RulesRepository.journaled()`
   now fires `PartnerSyncSignals.ruleChangesJournaled` + `enqueuePartnerSyncNow`, and
   `MainActivity` collects the signal under `repeatOnLifecycle(STARTED)` →
   `healBlockedPartnerSigning(forcePrompt = true)`, bypassing the 10-min prompt cooldown while
   a warm signer delivers silently; a stalled delivery stays surfaced and retryable. Device
   leg BLOCKED (needs a paired second phone); JVM build verified.
6. **Partner labels are not disambiguated.** The rule editor's partner picker showed four
   identical "X6532" rows, and "Watched by X6532" (Rules rows) with several same-named pairs
   is ambiguous — same root as finding 3. Needs a stable discriminator (paired-since date or a
   short key suffix) wherever a partner is named.
7. **Partner-side Rules & blocks is a change feed only — owner wants an inventory section
   (owner request 2026-11-01).** Owner: "that would be so much better, we can add that section
   … in rules and blocks then we can see a section also of updates where it tells us who is in
   that block, the update and when." Add to the partner's Rules & blocks page: a current-rules
   section (each rule/batch/website the partner watches, naming the person it belongs to), in
   addition to the existing timestamped update feed. Feeds on wire data already present (rule
   snapshots / `partner_event_journal`); needs a product ruling on what non-owner viewers may
   see (rule names are shared today; app/website contents are not).
8. **Website limit warnings have no popup — app limits do (owner question 2026-11-01).**
   App limits render `OverlayContent.Warning` over the app ("About N min of today's time
   left", `BlockOverlayView.kt:347-360`); website limits only post notification id 3
   (`AndroidDomainUsageHost.kt:40-57`). Observed live: somafm.com hit 1/1 min during playback
   → notification only, nothing on screen; the wall appears only when the browser is
   refocused. Owner: "sure we have a notification for time limit warning but wasn't there a
   popup also?" Decide whether website warnings should also get the in-place banner.
9. **Path A blocks are never written to the attempt log — Insights "Times blocked"
   undercounts (device-proven 2026-11-01).** Two independent reproductions: (a) an all-day
   app rule — real Chrome and Calendar walls ("Chrome is blocked", persistence ≥10s,
   `MyExodusBlocker: decide com.android.chrome gen=1 -> Block`, `block close
   pkg=com.android.chrome evicted=true`, FGS foreground and running); (b) a daily-limit rule —
   Files showed "Files is done for today … resets tomorrow" from the Path A usage ticker
   (`MyExodusUsage: observe … -> 395206ms/60000ms Block`, `MyExodusOverlay`, `block close
   pkg=com.google.android.documentsui evicted=true`). In both, logcat had zero
   `MyExodusAttempts: attempt logged` lines and Insights read **"Times blocked 0"** while
   "Overrides 1" was correct. Cause: `AttemptRecorder.recordBlocked` is called only from the
   FGS usage-stats *new-launch* path (`EnforcementForegroundService.kt:739`, gated on the
   `launching blocker` line) and URL blocks (`BlockerAccessibilityService.kt:1045`); the
   accessibility decision branch and the Path A usage ticker show the wall and evict without
   recording, and the FGS never sees the launch because the app is evicted within ~2s.
   Contract violation: the ADR requires "every enforced block and forced override is recorded
   durably" (MyExodus Architecture Decisions.md:243), and website blocks demonstrably log
   `attempt logged: outcome=BLOCKED`. Since Path A is the primary path whenever the service is
   alive, nearly every real block is unrecorded. Fix: record at every wall-showing site
   (a11y decision branch + Path A usage ticker), with double-record protection when the FGS
   path also fires. Evidence `/tmp/chrome_repro.png`, `/tmp/state_now.png`,
   `/tmp/files_open.png`.
   **FIXED 2026-11-02.** Every wall-showing site now records: FGS detection
   (`EnforcementForegroundService.kt:739`), FGS Path B daily wall (`:986`), a11y app decision
   (`BlockerAccessibilityService.kt:472`), a11y daily wall (`:764`) and URL block (`:1087`).
   Double-record protection is `AttemptLogDao.countRecentBlocked` — same rule+target within
   5 s is one attempt, so the FGS poll and the a11y decision that observe the same launch
   collapse. That guard had its own edge case: the original window was open-ended on the low
   side, and future-dated rows (the AVD clock had once run a month ahead) matched "recent"
   forever and silently suppressed every later attempt. The query is now bounded on both
   sides (`occurred_at BETWEEN :sinceMs AND :nowMs`); a future stamp is clock skew, not this
   launch. The mechanism reproduced itself on 2026-11-02: during a host-load ANR storm the
   guest clock read **2027-02-08** and then re-synced to the host's 2026-11-02 15:06 within
   minutes — a three-month jump, exactly the shape that poisoned the old window. JVM regression: `AttemptLogTest.recordBlocked_ignoresFutureDatedRows` plus the
   dedupe/window/override matrix in the same file.
   **Device proof (2026-11-02, AVD):** blocking Chrome on the a11y path produced
   `14:31:09.681 I/MyExodusAttempts: attempt logged: outcome=BLOCKED
   rule=9b871cf3-8fde-4dad-b43e-c3318367a008 target=com.android.chrome`, and Home's Chrome
   card went from "10 attempts" to "11 attempts" on the same launch. Hardware leg BLOCKED
   (X6532/X6532C detached).
10. **App daily limits count foreground time only — background playback is invisible (owner
   question 2026-11-01).** The app meter sums `ACTIVITY_RESUMED`→`ACTIVITY_PAUSED` intervals
   from `UsageStatsManager.queryEvents` (`PlatformUsageReader.kt:100-120`, same technique as
   Digital Wellbeing), and the blocker only evaluates an app when it is foreground. So Spotify
   playing with the screen off, or YouTube Premium background audio, accrues **zero** toward
   that app's daily limit and is never cut when the limit is exhausted; the limit bites only
   when the app is next opened. By contrast the website meter (VPN, connection-activity based)
   does keep counting a backgrounded browser tab with live sockets — observed today as
   example.org reaching 1/2 min while a different tab held the foreground. Needs a product
   ruling: Digital Wellbeing parity (foreground-only) vs. counting media-active time
   (`AudioManager.isMusicActive` / MediaSession) for apps that work in the background.

### SOS reach-out — both directions, live (19:22–19:25 local)

Server gate read before the run: `config/features.partnerSosEnabled = true`; pair active, epoch 2.
Both devices foreground on the live app; no keyguard in the path.

| Leg | Action | Observed | Evidence |
|---|---|---|---|
| X6532 → X6532C | Home → quick actions → Reach out → selected the top recipient → default message → Send | X6532 snackbar "Check-in sent."; X6532C tray within ~3 s: id 2005 `partner_nudge`, "Accountability Check-in" / "X6532 reached out — check in when you can."; X6532C Home card "X6532 could use a check-in · Sent just now · I could use a check-in." | `dumpsys notification --noredact`, uiautomator dump |
| X6532C ack | card X ("Dismiss the reach-out") | card cleared; server `nudges/72eb16aa…` `createdAt 16:22:51.097Z`, `ackedAt 16:23:40.885Z` (49 s) | Firestore REST |
| X6532C → X6532 | same path, first of four identical "X6532" rows | X6532 tray: "Accountability Check-in" / "Musila reached out — check in when you can."; X6532 Home card "Musila could use a check-in · Sent just now" | `dumpsys notification`, uiautomator dump |
| X6532 ack | card X | cleared; `nudges/a1631386…` `createdAt 16:24:32.825Z`, `ackedAt 16:25:02.137Z` (29 s) | Firestore REST |

PASS both directions: named sender in the tray and on the card, in-app card within seconds of send,
and the acknowledgement is the card's X (`NudgeInboxViewModel.acknowledge`, owner ruling
2026-11-01: the card offers no reply). Findings attached:

- **Roster duplication reaches the SOS picker.** X6532's Reach out dialog listed "Musila, Mama,
  Mama, RoundTrip Partner"; X6532C's listed four identical "X6532" rows. Same root as finding 6
  and the same consequence: the live pair is not identifiable from the picker.
- **Partner-visible actions are device-key gated, as designed.** Sending a message from X6532
  raised "Send your message / Confirm with your device key" (BiometricPrompt). The same gate
  holds the queued `quiet-marker.example` rule update on X6532; one authentication warms the
  900 s window and flushes both.
- **Messaging from X6532C is blocked with the designed consent dialog** — "Share a streak first
  … Encouragement to X6532 carries your latest progress summary, so it can only send while at
  least one streak is shared with them" + "Change what you share" (ADR #135). X6532C has no
  streaks of its own (`What you share` → "No streaks yet"), so only the X6532 → X6532C message
  direction is reachable on this rig.
- **Partner Rules & blocks remains a change feed only.** X6532C viewing X6532: "2 rule updates"
  (both "Restriction rule", 1d, no inventory of the actual rules/apps/websites). Confirms
  finding 7; the owner's requested inventory section does not exist.
- **Do not trust a protection card captured by a uiautomator dump** — the dump's UiTestAutomation
  registration unbinds the a11y service for the observation window (section 2 method note).
  Home showed "Protection is limited" during dumps while the tray held "Protection active";
  screencap is the only valid evidence for this surface.

### Partner surfaces on the borrowed phone — read-only sweep (19:28–19:35 local)

X6532's message send sat behind an unanswered BiometricPrompt for this whole window, so the
sweep is the X6532C side only; screenshots, no uiautomator dumps.

| Surface | Observed |
|---|---|
| Consent ("Other information → Which partner sees this?") | Four identical "X6532" rows, all "Not shared" on entry. The Blocked-apps switch was cycled ON→OFF as a consent-matrix probe and restored; final state verified by screencap (all three switches off, parent page back to "Not shared"). |
| Hub (Viewing X6532, since Oct 31 2026) | "Nothing shared yet" banner ("X6532 can't see your streaks, check-ins or recovery updates until you choose what to share"); tiles: Plans, Rules & blocks (2 changes), How they're doing (10 days free), Check-ins (Not shared), What you share (Nothing shared), Pairing (Synced 8m ago). |
| How they're doing | Days free 10, "Last shared 3h ago"; 4 relapses logged, 7 milestones met, 0 coaching items; a long-form check-in message from X6532 renders in full; Last-7-days chart Sat=4, all other days 0; Recent activity (4) = 2 rule rows + 2 relapse rows. |
| Recent activity backend cross-check | X6532C's mailbox holds 17 messages from X6532's uid (FtVxf…): 12 on Oct 31, 5 on Nov 1. The two "PartnerTest — Relapsed · ended a 1-day streak" rows correspond to distinct messages an hour apart, so they are two delivery events, not one duplicated message. Not yet verified against X6532's own streak history (its screen is behind the prompt). |
| Rules & blocks | "2 rule updates", both rendered "Restriction rule" with the pre-identity fallback (Rule turned off / Rule assigned to you, 1d). No inventory section — finding 7 confirmed live. |
| Plans | "You shared — Evening wind-down v2, 0 of 2 done, With X6532". No "From your partner" section, i.e. X6532C holds no plan authored by X6532. **Unresolved:** earlier notes have a "Daily routine" plan shared 2026-10-31 toward X6532C; it is not present here. Re-check X6532's Plans page once its prompt clears — possible stale-pair share (roster bug) or a projection gap; do not claim either yet. |
| Check-ins | "Check-ins — Not shared — Your partner hasn't chosen to share device availability." Honest downgrade copy; matches X6532's Device-availability consent being off. |
| Pairing | "Paired with X6532 — Since Oct 31, 2026 · Last synced 10m ago · Updates arrive as soon as both phones are online." "Send a test alert" ran: heads-up notification "Partner alerts are working · now — This is a test alert. Real partner activity will…". "Support another person" (Invite / Join). "End partnership" copy is honest about the unilateral end and the deferred partner-side erase. |

Partner labels inside one surface are inconsistent with each other: X6532C names the pair
"X6532"; earlier X6532 surfaces named the same live peer across rows as "Musila / Mama / Mama /
RoundTrip Partner". Same roster-duplication class as finding 6.

**Switcher ("People you support") — dates exist here, nowhere else.** X6532C lists four rows,
all "X6532": one "Paired Oct 31, 2026" (the live pair) and three "Paired Oct 4, 2026". The
Oct 4 rows are still shown as supported people with no stale/dead marker. Selecting one
switches the whole partner context: the Pairing page then reads "Paired with X6532 — Since
Oct 4, 2026 · Last synced 11m ago · Updates arrive as soon as both phones are online." Backend
cross-check: X6532C's uid (SvBQF…) has exactly one Oct 4 pair on the server
(`…_zmHLexVW…`, status active) — with an identity that no longer belongs to the live X6532
install (current uid FtVxf…). So an orphaned pairing with a long-gone identity is presented as
a current relationship with fresh sync. The 11m figure is the app's own per-enrollment
watermark (server contact succeeds; the partner will never answer), so the honest reading is
"last server contact", not "last sync with this person". Also unresolved: the other two Oct 4
rows have no matching server pair for this uid — ghost enrollments. Switching back to the live
row worked on retry; two injected taps on the dialog were swallowed first (device input
flakiness seen elsewhere today, not reproducible app freeze — the same taps took effect after
BACK/retry).

### AVD — BlockerAccessibilityService down, and the user's recovery path (19:04–19:50 local)

The QA AVD ran with its accessibility service **crashed and unbound** while the setting still
said enabled. Home was honest about it ("Needs attention — Protection is limited — The
accessibility service isn't running, so blocks can start a few seconds late. Turn it back on
to make blocking immediate." + Re-enable), and the state was proven independent of the
dump-induced unbind artifact: `dumpsys accessibility` showed `Bound services:{}`,
`Enabled services:{…BlockerAccessibilityService}`, `Crashed services:{…BlockerAccessibilityService}`;
ActivityManager logged "Scheduling restart of crashed service … in 1000ms" at 19:04:44.417
and 19:43:20.928 with process restarts at 19:04:45 and 19:43:22. The OS restarted it twice,
then stopped auto-restarting (crash-loop cap) and left it down indefinitely.

**Cause found (not an app-code fault):** the app *process* was ANR-killed under emulator CPU
starvation, and the accessibility service died with it. Both captured instances are
input-dispatch timeouts, not exceptions:

- 19:43:18 `ANR in com.example.myexodus (MainActivity)` — "Waited 5098ms for
  FocusEvent(hasFocus=true)", Load 24.01, CPU pressure some/avg10=99.61; kill at 19:43:20,
  then the "crashed service" restart scheduling.
- 19:51:49 second ANR — "Waited 8151ms for MotionEvent…"; the launcher (`nexuslauncher`)
  ANR'd in the same second and both processes were killed (signal 9).
- 19:55:59 after a cold boot: "Process … failed to complete startup", Load 31.57, CPU
  pressure some/avg10=99.57 — killed before the a11y bind completed.

The guest runs with **1 vCPU** (`hardware-qemu.ini: hw.cpu.ncore = 1`; a CLI `-cores 3`
relaunch is accepted but the guest still reports one processor — same quirk as the
2026-10-17 note), and the crash buffer holds no Java fatal. The launcher ANR'ing alongside
the app is the control: this is host/emulator load, not app code. The service staying down
is Android's own handling — the OS disables a crashed a11y service and does not re-bind
until the user re-enables it.

Recovery path, driven end-to-end:

1. Home "Re-enable" → system Settings → Accessibility: "MyExodus blocker — Not working. Tap
   for info."
2. Service detail: the "Use MyExodus blocker" toggle is **ON** (checked), with "This service
   is malfunctioning." — i.e. the screen shows the switch already on.
3. Toggling off asks "Turn off MyExodus blocker?"; confirm → `accessibility_enabled=0`,
   `enabled_accessibility_services` empty, Bound/Crashed sets empty.
4. Toggling on raises "Allow MyExodus blocker to have full control of your device?"; Allow →
   `Bound services` non-empty, `Crashed services:{}`.
5. App Home pill returned to **Protecting** (screencap), and the FGS authority went
   `DEGRADED_POLLING_ONLY` (last degraded tick 19:48:38, while unbound) → **FULL** at 19:49:48.

**UX gap (fix queue, not a blocker):** the app's copy says "Turn it back on", but the system
screen shows the toggle already on and malfunctioning; the repair that actually works is a
full off→on cycle. A user who follows the copy literally and sees an ON switch has no visible
reason to touch it. Re-enable does deep-link to the right screen, so the path exists — the
guidance and the visible state disagree.

### AVD — nudge-inbox listener retry on an unpaired profile

`MyExodusNudgeInbox: inbox attempt N: no listener attached, retrying` ran 1→20 at 3 s
intervals, then restarted at attempt 1 when Home was re-entered (19:49:00), on the QA profile
that has no pair. Checked against source (`NudgeInboxViewModel`, `NudgeInboxRepository`): the
retry is **bounded by design** — 20 attempts × 3 s ≈ 60 s per Home visit — because `inbox()`
emits null and completes when there is no pair/uid/key. Not a defect; noted only because a
persistently unpaired profile re-arms the poll on every Home entry (~60 s of 3-second
wakeups). Doc comment intent (survive the session-bootstrap race) is satisfied.

### X6532 — encouragement delivered, composer stuck on "Sending…" — FIXED 2026-11-02 (reported 20:05; stuck since ~19:28)

Owner report: "message reached but screen is stuck in sending." The "Send encouragement"
dialog (To: Musila, body "Two-phone test 2026-11-01.", 26/280) showed **Sending…** with
**Cancel disabled** at 20:05, 20:07 and 20:10 (screencaps `/tmp/x6532_sending*.png`,
`/tmp/x6532_stuck_now.png`). The message itself was delivered: X6532C's Home shows the card
"From X6532 — Sent 49m ago — Two-phone test 2026-11-01." (20:17 screencap), the owner saw
the notification on X6532C, and both mailboxes now return zero documents (Firestore
runQuery). The cleanup job deletes a message only once every registered non-stale
installation has acked it (`FirestoreSchema.mailboxAckDocument` doc comment), so the empty
mailbox is receipt proof, not loss. "Sent 49m ago" at 20:17 puts the tap at ~19:28 — the
dialog had been stuck ~40 minutes before it was reported.

Defect (HIGH, fix queue #8):
- **No exit while sending.** `ComposeMessageDialog` uses
  `onDismissRequest = { if (!sending) onDismiss() }` and a Cancel button with
  `enabled = !sending` (`ui/partner/PartnerScreen.kt:1518-1646`), so any hung send traps the
  entire screen (back and scrim both ignored).
- **No timeout on the send path.** `messageSendOutcome`
  (`accountability/sync/MessageDelivery.kt:33-49`) wraps `authorize()` and `runPass()` in
  `runCatching` only — that catches throws, it does not bound a suspend that never resumes.
  `authorize` = `signWithAuthPrompt` → `biometricSign` (MainActivity), which resumes only
  from the BiometricPrompt callbacks and has no deadline; `runPass` =
  `runPartnerSyncPass` → `syncOnce`, serialized on `syncMutex` and containing Firestore
  awaits with no per-call deadline.
- **Device evidence of a blocked process:** one `am_anr` for the stuck process (pid 14584)
  at 20:15:42 — "Input dispatching timed out … Waited 5000ms for FocusEvent(hasFocus=false)"
  — logged as the process was being force-stopped/relaunched. The exact suspend that never
  resumed is **not proven**: `debuggerd -j` requires root, and the one successful jdb attach
  could list threads but `where all` needs a VM suspend; the re-attach was refused. Do not
  record a root cause beyond "no timeout anywhere on the path".

Recovery (only available exit): `am force-stop` + relaunch, then re-arm the a11y service via
the Accessibility settings off→on cycle — force-stop clears its binding — and confirm the FGS
is foreground again.

Fix direction: the sheet must stay dismissible/cancellable while sending; wrap the outcome
derivation in `withTimeout` (~30-60 s) and report QUEUED on timeout (the journal-first
contract makes that honest); ensure the sync kick can never hold the composer indefinitely.

**Fix applied (2026-11-02, `MessageDelivery.kt`).** Each leg of `messageSendOutcome` is now
bounded by `withTimeoutOrNull(MESSAGE_SEND_TIMEOUT_MS = 45 s)` — `authorize`, `runPass`, and
`stillPending`. A timed-out leg reports QUEUED, never SENT: only an explicit `false` from
`stillPending` claims delivery, so a wedged pass or an unreadable queue can never read as
"went through". The journal-first emit means QUEUED is honest — it leaves on the next pass.
JVM coverage in `MessageDeliveryTest`: `anAuthorizationThatNeverReturnsIsSavedWithinTheTimeout`
and `aQueueThatNeverAnswersNeverClaimsSent`, alongside the full SENT/QUEUED/FAILED matrix.
**Device leg BLOCKED** (a paired second phone is needed to reach the composer); the timeout
paths are exercised in JVM. The composer's `Cancel disabled while sending` binding is
unchanged — with every leg bounded at ≤45 s the worst case is a bounded wait rather than the
observed ~40-minute trap; re-verify on hardware whether the button should also stay tappable.

Relaunch observation: the transient "MyExodus isn't responding" dialog appeared under XOS
while WhatsApp was in use; tapping Wait cleared it and the new process has been healthy
since. Recorded as observation, not a separate defect — the ANR event above is attributed to
the pre-relaunch pid.

### App-wide number audit (owner demand, 2026-11-01 20:35+)

Method (not a skim): every numeric render site in `ui/`, `widget/`, `blocking/` was enumerated,
not sampled — all 35 `toString()` sites, all `StatCard`/`StatTile`/`StatColumn`/`HeroStatRow`
call sites, all `Text(<number>)` sites, every `<string>`/`<plurals>` resource carrying `%1$d`,
and every bare-placeholder resource. On-device evidence: `/tmp/c_activity_recheck2.png`
(X6532C, activity page) and `/tmp/x6532_activity2.png` (X6532).

Confirmed ambiguous (bare number with no subject/period — fix queue #8):

| ID | Surface | Where | What the user sees | Defect | Fix |
|----|---------|-------|--------------------|--------|-----|
| N1 | Partner → activity page → "Last 7 days" chart | `PartnerScreen.kt:2267`, strings 867-868 | A bare `4` above Saturday's bar; caption "Their shared activity, one bar per day. Today is on the right." | "Activity" is never defined — 4 what? The caption explains layout, not unit. Only the TalkBack sentence ("Sat, 4 events") names the unit, and sighted users never hear it. | Name the counted things in the caption, e.g. "Each bar counts what your partner shared that day — rule changes, relapses, milestones and messages. Today is on the right." |
| N2 | Partner → activity page → "Recent activity (3)" | `PartnerScreen.kt:2317-2324`, strings 858-860 | "Recent activity (3)" then "3 Today" / "3 This week" | (a) Counts have no subject; (b) the title's parenthetical is the number of rows shown (`RECENT_LIMIT`=5), not the week count — on a 12-event week it says "(5)" beside "12 This week", two unrelated numbers in one card. | Drop the parenthetical; label the columns "Updates today" / "Updates this week"; add one line under the title naming what is counted. |
| N3 | Partner → dashboard tile "Rules & blocks" | `PartnerScreen.kt:861-869`, strings 776-779 | "3 changes" | No period, and "changes" contradicts the page it opens, which titles itself "3 rule updates" (`partner_rules_count`). | Same vocabulary and period: "%d rule updates" (the count is the stored history list, all-time received). |

Verified labeled (no defect): Insights "Times blocked"/"Overrides" + captioned chart + legend +
"What you tried most" (app-labelled) + recovery rows (each label names its bucket); Home streak
numeral ("Days free" + unit), "Blocks"/"Times blocked" tiles; History "Urges logged" total +
three named buckets; Recovery intensity chips (under "How strong is the urge?"); partner summary
(Days free / Relapses logged / Milestones met / Coaching items); all partner tiles except N3;
every timetable/limit string ("until %s", "%d of %d selected", "Step %d of %d"); widget numeral
under its "DAY" label; milestone dialogs ("%d days" under "Free from %s"); every notification
body (each carries its noun); debug-only Domain Usage section (monospaced, metric names in rows).
Only two conventional counters were left as-is by design: the pinned-message carousel
indicator ("%1$d of %2$d") and the app-picker's "Add %1$d" button (the picker screen is the
noun).

### AVD backup conflict legs — Replace vs Keep (2026-11-01 ~17:40–17:46 UTC)

Setup: QA account `qa.avd.20261101@example.com` (uid `nS0ntehLhfUlnDHgRrzjOguo6k33`), backup
key set up with passphrase; a cloud backup already existed that this install had not
downloaded, so "Back up now" raised **"Replace the cloud backup?"** ("The cloud already has a
backup this phone hasn't downloaded… This can't be undone.") with "Replace cloud backup" and
"Keep the cloud backup".

Pointer before either leg (Firestore `users/<uid>/backupControl/current`):
`backupId=9d8ee862ef7a63363cf523fa8aa62b9f`, `revision=1`, `updatedAt=2026-11-01T15:47:59.561Z`.

- **Keep leg (PASS):** tapped "Keep the cloud backup" → dialog dismissed → re-read pointer:
  byte-identical (same backupId, revision 1, same updatedAt; only server `updateTime` moved
  from the metadata read). True no-op — nothing was overwritten.
- **Replace leg (PASS):** re-ran "Back up now" with the passphrase, same dialog, tapped
  "Replace cloud backup" → "Uploading… 0%" → "Backup complete" toast/dialog ("Your data is
  encrypted and saved to the cloud. You can restore it on a new phone with your passphrase or
  recovery key.") → pointer now:
  `backupId=174f2bb54053c0894bfa394ce606810c`, `revision=2`,
  `updatedAt=2026-11-01T17:46:20.229Z`, new `sha256=ab406a38…14efe2`. Revision advanced and
  the object identity changed — the overwrite happened exactly as the dialog warned.

Both outcomes are distinct and match the dialog's stated consequences; no silent overwrite on
Keep, no silent failure on Replace.

### AVD sign-out semantics (2026-11-01 ~20:47 local)

Account pane (signed in as QA): sign-out row copy "Your encrypted data stays on this device.
Signing out doesn't delete it." → Sign out → confirmation not shown as a dialog in this build;
tapping the button restarted the process (new pid) and returned to the launcher; relaunch
opened the app signed out. Signed-out Account pane: "Accounts are optional … Everything stays
on this device. An account lets you back up and recover your progress if this phone is lost —
including your partner connection." with Create account / Sign in. Home still renders the
retained scope's data (habit "Guest marker A", streak day 1) — consistent with ADR #236's
retained encrypted DB. Settings subtitle after sign-out: "Guest mode — data stays on this
device". Protection banner "Something on your phone closed MyExodus. Time limits stay paused
until you re-enable." appeared after the process restart — expected self-healing state, not a
defect (re-enable re-arms, verified elsewhere).

### Two-phone final legs — owner→partner delivery, partner-end survival, hand-back
(2026-11-01 ~21:34–21:39 local, X6532 owner + X6532C borrowed, live backend)

- **Owner→partner rule delivery — PASS.** The queued `owner-marker.example` and
  `quiet-marker.example` updates flushed after the owner's BiometricPrompt approval: X6532's
  "1 update waiting to be sent" banner cleared, and X6532C's Partner → Rules & blocks went
  from "2 rule updates" to "4 rule updates" with fresh rows `owner-marker.example · Rule
  created · 24m ago` and `quiet-marker.example · Rule created · 2h ago`. This is the only
  direction not previously recorded (the 16:15 lifecycle matrix ran X6532C → X6532).
  Evidence: `/tmp/x6532_rules_before_unpair.png`, `/tmp/x6532_rules_before_unpair.xml`.
- **Partner-side end → rules survive — PASS.** X6532C → Pairing → End partnership (dialog:
  "This erases your partner's data on this device immediately. Your partner's phone erases
  its own copy next time it's online — that part can't be forced.") → X6532C erased the live
  pair's data ("Nothing shared yet", "Rules & blocks — None shared with you yet"). X6532
  detected the end with the exact copy: "This phone is no longer recognized by your pairing…
  the pairing was ended from the other side… Until then, nothing is shared." The owner's
  rules all survived (Netflix, owner-marker.example, pornhub.com, quiet-marker.example,
  xvideos.com, Chrome, the hidden-block note) and every assigned row reconciled to
  "Unsupervised — the assigned partner is no longer paired".
- **New consequence of the roster-duplication defect (finding 6):** after the live pair ends,
  both phones silently switch their context to a stale roster row — X6532 to "Viewing Mama ·
  Since Oct 21, 2026", X6532C to "Viewing X6532 · Since Oct 4, 2026" — instead of showing the
  ended pair or a clean empty state. The dead pair's UI still offers "Send a test alert" and
  "End partnership".
- **Hand-back (X6532C only, owner-authorized):** the dead Oct 4 pairing was ended through the
  UI, then `pm clear com.example.myexodus` was run on X6532C alone — exit 0, app data
  directory now contains only the system-maintained `lib` symlink, app process gone. The app
  remains installed; nothing else on the phone was touched.
- **Physical re-pair leg — BLOCKED (missed window, corrected 2026-11-02):** the shipped V1
  ceremony is **typed codes only — no camera, no QR** (authority: partner-pairing plan §1/§2,
  ADR #97 — Crockford `XXXX-XXXX-XXXX-C`; UI `PairingCeremonyScreen.kt`): the initiator reads
  the code to the acceptor, the acceptor types it, both confirm the 6-digit SAS and each
  authorizes with the screen lock. **It was not run before X6532C was wiped and returned** —
  the hand-back was the last action, so the second device was gone. This should have been
  executed BEFORE the wipe; recorded as a planning miss, not a pass. What IS proven without a
  second phone: the full unpair → re-pair → SAS ceremony twice on the two-AVD local rig, plus
  all five adversarial pairing legs (ADR #252 / task #61). What remains unproven is the
  live-backend ceremony on two physical handsets. Cost when a second phone is available:
  about five minutes; nothing else depends on it.

### AVD restore first-open anomaly (2026-11-01 ~21:18–21:31 local)

**Observed:** restoring revision-2 on the QA AVD ended on "Recoverable data found — A backup
of a previously corrupted database exists on this device." with "Discard recoverable data" as
the only action — a dead end with no retry and no way to use the restored copy.

**Forensics (proven):** the swapped-in restored file is valid — it opens under the app's DEK
standalone and alongside the pre-restore WAL/SHM, and shares 52/76 byte-identical encrypted
pages with the pre-restore copy (same key material). The failure occurred inside
`ensureRawKeyed` (no sqlcipher-open success log precedes the recovery action); the ~1.5 s gap
matches the PBKDF2 legacy-key fallback. `usePreRestoreCopy()` then renamed the restored file
to `.corrupt` and restored the pre-restore copy. After tapping "Discard recoverable data", the
app opened from the restored content (habit "Guest marker A", day 1, milestones 7/30/90) —
proving the payload's logical data but not the restore path.

**Preserved artifacts:** `/tmp/restored_corrupt.db` (sha256 `7195af9f…aac37729`),
`/tmp/restored_current.db` (sha256 `80350bb2…e57d957`), `/tmp/avd_recoverable_screen.png`.

**Open, HIGH:** (1) post-swap first open can fail despite a valid restored file — cause not
yet proven (leading hypothesis: legacy/stale-scope key path resolved before the new scope was
visible); (2) the recovery screen offers only destructive discard. The decisive replay (fresh
`pm clear` → sign-in QA → restore the same revision-2 backup with logcat) was started at 21:31
and remains to be completed.

### AVD restore first-open anomaly — root cause, fix, and decisive replay (2026-11-01 ~23:32 local)

**Root cause (proven from code + device log):** Room captures the database file name when the
builder is created, but the encrypted driver resolved the DEK suffix per `open()`. A pre-scope
builder (widget publish during Hilt injection) therefore captured the legacy file name
`myexodus_rules.db` while the driver resolved the scoped key suffix after
`LocalDatabaseMigration.start()` flipped the process's scope, so the legacy file was opened
with the scoped DEK → HMAC failure on page 1 → the corrupt catch renamed the *scoped active
file* (the freshly swapped-in restored snapshot) to `.corrupt` and restored the pre-restore
copy — silently undoing the restore. Fix in the working tree: one scope snapshot
(`currentRulesScope()`) supplies both `databaseName` and `keySuffix` at builder creation
(`AppRulesStoreFactory.openInMode`), `usePreRestoreCopy`/`renameCorruptRules` refuse to act
on any file other than the one actually being opened, and a regression test
(`RulesStoreKeyManagerInstrumentedTest.driverCapturesKeySuffix_scopeFlipAfterCreationKeepsTheLegacyKey`)
pins the capture moment.

**Replay blocker (diagnosed and fixed):** QA sign-in failed with the red banner "Sign in to an
account first." — logcat showed `DebugAppCheckProvider: Failed to exchange debug token` /
`Error getting App Check token: Too many attempts` and `FirebaseFunctionsException:
Unauthenticated`. The AVD's current App Check debug token had never been registered (the
project sat at the 20-token cap and older tokens were pruned across sessions). The token was
registered through the App Check API (display name `AVD QA 2026-11-01 restore replay`; the
token value is intentionally not recorded in this doc). After an app restart, the same
credentials (`qa.avd.20261101@example.com` / passphrase restored from the session transcript)
signed in: `MyExodusDbScope: active database scope switched to uid hash 7fdd531a…` followed by
a transition relaunch (new pid) into the QA scope.

**Replay (installed build = working tree at 22:xx, `pm clear` at 23:19):**
1. Settings → Account → Encrypted backup → Restore from cloud → passphrase → Continue.
   Preview: "Revision 2 · records: 5 … Habits: 1 · streaks: 1 · milestones: 3".
2. The staged file `myexodus_rules_7fdd531aed7f890ee9bde3734abc389f.db.restore` was present in
   `databases/` before the apply, as designed.
3. "Restore Cloud Snapshot" tapped at 23:34:34; process relaunched (pid 21529 → 21941).
4. Log: `MyExodusDbScope: local databases scoped to uid hash 7fdd531a…` then
   `MyExodusDb: Fast path: published cache v0` then
   `MyExodusDb: **Restored database verified; pre-restore copy removed**` — the cleanup ran
   only after the verified healthy open.
5. Clean checks: zero `hmac check failed` lines; zero `.corrupt` / `.restore-previous` files
   anywhere in the app data dir (`find` returned nothing); the active scoped DB and its
   `-wal` are present and non-empty.
6. Content check: Home renders the restored habit "Days free from Guest marker A" — "1 day",
   "Free since Nov 1", "Next milestone: 7-day mark — 6 days to go.", "Day 1 of 90".

**Status:** (1) CLOSED — the first-open failure is root-caused, fixed, and the restore path is
proven on-device with the fixed build. (2) The recovery screen's destructive-only action is a
separate UX finding and remains open in the fix queue.

### AVD blocking-matrix legs — batch and daily limit (2026-11-01 23:40–23:56 local)

- **All-day app rule → wall → override → retire — PASS.** Chrome wall ("Chrome is blocked",
  `decide com.android.chrome gen=1 -> Block`, persists ≥10s), "Open MyExodus Recovery" landed
  on Recovery with a live friction timer, "End the block" retired the rule; the app then
  opened with `decide … -> Allow` and Insights counted the override. Evidence
  `/tmp/chrome_repro.png`, `/tmp/state_now.png`.
- **Mixed batch (2 apps + 1 website) — creation and both enforcement legs PASS.**
  "Focus mix" = Camera + Clock + reddit.com; snapshot `v5: 3 app, 1 url rules`. Clock launch
  produced its own wall ("Clock is blocked") from the batch rule; with the Chrome all-day rule
  toggled off, navigating to reddit.com rendered the site wall ("reddit.com is blocked · Go to
  a different site · Home and back always work…") and recorded
  `MyExodusAttempts: attempt logged: outcome=BLOCKED rule=ac0b9179… target=reddit.com`.
  Evidence `/tmp/clock_wall2.png`, `/tmp/reddit_wall.png`.
- **Daily-limit wall — PASS, copy and controls correct.** Files rule "1 min/day" saved
  (snapshot `v7`), app opened → "Files is done for today / Today's time limit is used up. It
  resets tomorrow. / Close" with no Recovery button (by design), plus Path A eviction
  (`block close pkg=com.google.android.documentsui evicted=true`). The limit applied to
  already-accrued usage from before the rule existed (394,200 ms → Block), which is the
  documented "blocked after this much use each day" semantics. Evidence `/tmp/files_open.png`.
- **Notification permission Fix flow — PASS.** Home's "Notifications are off" banner → Fix
  opened the app-notification settings; toggling "All MyExodus notifications" ON and returning
  cleared the banner; FGS tick switched `degradation=NOTIFICATIONS_OFF` → `null`, pill returned
  to "Protecting". A brief "Protection is limited" flash seen in one `uiautomator` dump is the
  known test-method artifact from §2: the dump registers UiAutomation, which unbinds the
  accessibility service for the dump window; FGS ticks read `authority=FULL` immediately
  before and after, and a screencap shows "Protecting". Not a defect.
- **Under-recording defect (finding 9) reproduces on both block paths**: neither the all-day
  walls nor the daily-limit wall wrote an `attempt logged` line; only the URL block did.

### AVD blocking-matrix legs — app-only batch lifecycle (2026-11-02 00:02–00:06 local)

- **App-only batch: create → enforce → pause → allow → delete — PASS.**
  "Apps only" = Contacts + Messages, mode **Block all day** (the editor's default), scope
  **Every day / Ends: Never** (timezone note "Times use Africa/Nairobi."). Created from the
  Rules page's Add batch tile; snapshot went `v7: 3 app, 1 url` → `v8: 5 app, 1 url` —
  exactly two app rules added and the URL count unchanged, which is the app-only contract.
  The Rules page rendered "Apps only · 2 items · Always on" with both apps listed
  "Blocked now". Launching Messages produced its own wall ("Messages is blocked / Blocked by
  your MyExodus rules / Open MyExodus Recovery / Close / Blocking resumes when you come
  back."); the FGS path logged `detected com.google.android.apps.messaging — launching
  blocker` and `attempt logged: outcome=BLOCKED rule=489e9909… target=…messaging` — i.e. an
  FGS-detected app block does record (finding #9 is specifically the Path A-only blocks:
  accessibility walls and the Path A daily-limit ticker). Pausing the batch (toggle off)
  took the snapshot to `v9: 3 app, 1 url`, the card showed "Paused", and Messages opened
  normally to `ConversationListActivity`. Deleting the batch through its confirmation dialog
  ("Delete this batch? 'Apps only' and its 2 rules will be removed and stop blocking
  immediately.") removed the card with **no residue**; snapshot stayed `v10: 3 app, 1 url`
  because paused rules were already excluded. Evidence `/tmp/avd_messages_wall2.png`,
  `/tmp/avd_batch_off.png`, `/tmp/avd_delete_dialog.png`, `/tmp/avd_after_delete.png`.

### AVD blocking-matrix legs — website-only batch lifecycle (2026-11-02 00:10–00:15 local)

- **Website-only batch: create → enforce → escape → re-block → delete → allow — PASS.**
  "Sites only" = instagram.com, mode **Block all day** (default), scope **Every day / Ends:
  Never**. Created from Add batch; snapshot went `v10: 3 app, 1 url` → `v11: 3 app, 2 url` —
  exactly one URL rule added, app count unchanged (the website-only contract mirror of the
  app-only leg). Card showed "Sites only · 1 item · instagram.com · Always on · Blocked now".
- **Enforcement — PASS.** Force-stopped Chrome, opened `https://instagram.com`; the site wall
  appeared ("instagram.com is blocked / Blocked by your MyExodus rules / Open MyExodus
  Recovery / Go to a different site / Home and back always work. Blocking resumes when you
  come back."), with `MyExodusUrlScan: url scan gen=1 focused=false 'instagram.com' -> Block`
  and `MyExodusAttempts: attempt logged: outcome=BLOCKED rule=99bd23e6… target=instagram.com`
  — URL blocks record. Evidence `/tmp/r2.png`.
- **"Go to a different site" — PASS.** Tap loaded `about:blank` in the same Chrome tab
  (`url scan … 'about:blank' -> Allow`), Chrome stayed focused. Navigating back to
  instagram.com re-blocked immediately (re-scans like any blocked URL). Evidence `/tmp/r3.png`,
  `/tmp/r4.png`.
- **Delete — PASS.** Confirmation "Delete this batch? 'Sites only' and its 1 rule will be
  removed and stop blocking immediately." → snapshot `v11: 3 app, 2 url` → `v12: 3 app, 1 url`,
  card gone, no residue. Post-delete re-open of instagram.com loaded the real site with
  `url scan … 'instagram.com' -> Allow` and no wall. Evidence `/tmp/d2.png`, `/tmp/d4.png`.

### AVD blocking-matrix legs — schedules, day scopes, expiry (2026-11-02 00:15–00:42 local)

- **Schedule window — outside window allows — PASS.** "Window off" = Contacts, **On a
  schedule** 1:00 AM–7:00 AM, Every day, Ends never. Creating a schedule requires turning
  "Block all day" **off** first — the mode row's schedule control is `enabled = !alwaysOn`
  (`BatchEditor.kt`), matching the shipped contract. At ~00:20 local the card showed
  "Not active now"; launching Contacts opened it normally with no wall and no attempt.
  Evidence `/tmp/t4.png`, `/tmp/c2.png`.
- **Schedule window — inside window blocks, overnight wrap — PASS.** "Window on" =
  Messages, On a schedule 10:00 PM–7:00 AM; the editor renders "Overnight — ends the next
  day." and the timezone note "Times use Africa/Nairobi." At ~00:24 local (inside the
  window) the card showed "Blocked now", launching Messages produced its wall and
  `MyExodusAttempts: attempt logged: outcome=BLOCKED … target=…messaging`. Evidence
  `/tmp/f9.png` (editor), `/tmp/f13.png`, `/tmp/f15.png`.
- **Day scope — excluded day allows — PASS.** "Weekend only" = Phone, Block all day,
  Active on Weekends. On Monday (device date Nov 2) the card showed "Not active now";
  Phone opened normally with no wall and no attempt logged. Evidence `/tmp/g11.png`,
  `/tmp/g13.png`.
- **Day scope — included day blocks — PASS.** "Monday only" = Drive, Block all day,
  Active on Mon. On Monday the card showed "Blocked now"; launching Drive produced the
  engine Block decision, `launching blocker`, and `attempt logged: outcome=BLOCKED`.
  After advancing the device clock one day (Tuesday) the card flipped to "Not active now".
  Evidence `/tmp/h10.png`, `/tmp/h11.png`.
- **Expiry — ends today still blocks today — PASS.** "Ends today" = Gmail, Block all day,
  Every day, Ends Nov 2, 2026. The date picker refuses past dates (`NotPastDates` — "A past
  expiry lifts the rule the moment it is saved") and stores local end-of-day
  (`toLocalEndOfDayMillis`). On Nov 2 the card showed "Blocked now" and Gmail's launch was
  blocked and recorded. Evidence `/tmp/i13.png`.
- **Expiry — auto-lift next day — PASS.** Clock advanced one day (Tue Nov 3): the card
  showed "Always on · until Nov 2, 2026" / "Expired", and Gmail opened with no wall and no
  attempt — `BlockDecisionEngine` allows when `now > blockExpiresAt` and the domain
  blocklist only blocks while `nowMillis <= expiresAt`. Evidence `/tmp/j2.png`.
- **Expiry — resume drops lapsed end date — PASS.** Toggling the expired batch back on
  re-published it without the lapsed end date (card "Always on" / "Blocked now"); Gmail was
  blocked again (`attempt logged: outcome=BLOCKED … target=com.google.android.gm` at
  Tue 00:41), proving `setBatchEnabled` resume drops a lapsed end.
  Snapshot `v19: 8 app, 1 url rules`. Clock restored with
  `settings put global auto_time 1`. Evidence `/tmp/j4.png`, `/tmp/j6.png`.
- **Cleanup — PASS, one honest note.** Every test batch was removed through its named
  confirmation dialog ("Delete this batch? “<name>” and its N rule(s) …"). The first
  cleanup tap landed on the mixed **"Focus mix"** card (the list had settled one row off
  after the last swipe) and deleted it — it was a disposable test artifact and the
  mixed app+url batch contract had already been proven in an earlier leg. Snapshot walked
  `v20: 7 app, 1 url` → `v25: 1 app, 0 url`; the only surviving rule is the baseline
  Files daily-limit batch. No residue on the Rules page.

### AVD blocking-matrix legs — website DNS layer vs accessibility layer (2026-11-02 00:47–01:05 local)

- **Both layers come up from the rule, and the tunnel is rule-driven — PASS.** Adding a
  website rule ("Lay" batch, example.com) published `v26: 1 app, 1 url`, and the VPN
  started itself: `MyExodusVpn: policy blocklist: 1 host rules (snapshot v26)`, transient
  `DEGRADED DNS_LOOP_DOWN`, then `MARKER: vpn-state ACTIVE` — the shipped
  `VpnLifecycle.decide` contract (start iff ≥1 live website rule + consent). The category
  flow ("Social media" → all 10 curated sites → "Block 10 sites") then added the ten
  curated rules one at a time (`v33…v35`), ending at `v36: 1 app, 11 url rules` and
  `policy blocklist: 11 host rules`.
- **DNS layer alone — PASS (sinkhole serves NXDOMAIN).** With
  `enabled_accessibility_services` emptied (dumpsys confirmed `Enabled services:{}` /
  `Bound services:{}`), opening `https://tiktok.com` in Chrome produced Chrome's own
  `This site can't be reached … DNS_PROBE_FINISHED_NXDOMAIN` page, and the tunnel logged
  `nxdomain tiktok.com v4 rule=policy written=true` (4×). No MyExodus wall, no URL scan,
  no attempt record — the DNS layer enforces by itself. Evidence `/tmp/dns_tiktok1.png`
  (loading), `/tmp/dns_tiktok2.png` (NXDOMAIN result).
- **Accessibility layer — PASS (URL scan + wall).** Re-enabling the service
  (`Enabled services:{...BlockerAccessibilityService}`, bound "MyExodus blocker") and
  reopening the same URL produced the shipped site wall ("tiktok.com is blocked / Blocked
  by your MyExodus rules / Open MyExodus Recovery / Go to a different site / Home and back
  always work. Blocking resumes when you come back."), with
  `MyExodusUrlScan: url scan gen=2 focused=false 'tiktok.com' -> Block`,
  `MyExodusBlocker: url result gen=2 -> Block`, and
  `MyExodusAttempts: attempt logged: outcome=BLOCKED … target=tiktok.com`. Evidence
  `/tmp/a11y_tiktok2.png`.
- **Honest limitation observed — DNS blocking is not retroactive over a cached page.**
  Before the a11y-off DNS leg was isolated, `example.com` had answered NXDOMAIN
  (`nxdomain example.com v4 rule=policy written=true`) yet Chrome rendered the page from
  its disk cache. The fresh domain (tiktok.com) proved the sinkhole itself works; the
  cached render is browser cache behavior, not a DNS-layer defect, but it means a page
  already cached can still open while its rule is active. Ledgered as an observation for
  the fix queue.
- **Source-verified layer split (not re-run on device this sitting).** `DomainBlocklist`
  deliberately skips path-pinned rules (`example.com/forum`) and metered-only rules; a
  time-window-only website rule still blocks the host all day at DNS while the
  accessibility engine enforces the window (`DomainBlocklist.kt` header + `fromUrlPatterns`
  filters, ADR #66). Browsers are the accessibility layer's job; the DNS layer is the
  name-level backstop for everything else.
- **Cleanup — PASS.** The "Lay" batch plus each of the ten curated rules was removed
  through the rule editor's Delete action and its named confirmation ("“<host>” will be
  removed and no longer enforced. It stops blocking immediately."), each editor verified
  to show the expected host before deleting. Snapshot walked `v37: 1 app, 10 url` →
  `v47: 1 app, 0 url`; the tunnel then tore down by the same rule-driven policy
  (`policy blocklist: 0 host rules (snapshot v47)` → `MARKER: vpn-state STOPPED`). The
  accessibility service was re-enabled and is bound again; the Rules page is back to the
  baseline (Files 1m daily limit, empty Websites section). While the service was off, Home
  correctly showed "Blocker off / Protection is paused — Something on your phone closed
  MyExodus … Re-enable" and the "Enhanced Mode is off" notification fired — the documented
  degradation UX, observed live.

### Home protection chip — "stuck Limited" is a harness trap, not a defect (2026-11-02 01:10–01:23)

- **Sequence.** The force-stop leg disabled a11y; Home correctly showed the paused state;
  the service was re-enabled through Settings ("Allow full control" → Allow). From then on,
  uiautomator dumps (including after a rotation-forced recomposition) reported chip
  `text="Limited"` plus the card `text="Protection is limited"` / "The accessibility service
  isn't running…" while two independent oracles disagreed: FGS ticks logged
  `tick 80/100/120: usage=true authority=FULL degradation=null` (01:21:41 → 01:22:42,
  pid 7356) and `dumpsys accessibility` showed the service both enabled and bound.
- **Source predicates.** The chip reads LIMITED when `limitedBeyondAuthority(authority,
  readiness)` (FULL + essentialGap) or when `authority == DEGRADED_POLLING_ONLY`
  (`HomeScreen.kt:529-536`); the exact card is gated by `explainPollingOnly`, which requires
  `authority == DEGRADED_POLLING_ONLY && degradation == null && persistent`
  (`HomeScreen.kt:1336-1345`). That copy can only render from a POLLING_ONLY read, which
  contradicts the FGS's singleton flow (MainActivity collects the same
  `protectionStatusMonitor.authority`, `MainActivity.kt:521`; Hilt `SingletonC` shows one
  DoubleCheck provider).
- **Mechanism, proven by quiet-window experiment.** The flips are caused by the harness, not
  by the app: `uiautomator dump` starts a UiAutomation accessibility client, which suppresses
  other accessibility services; the in-process liveness flag drops, `ProtectionStatusMonitor`
  publishes `DEGRADED_POLLING_ONLY` (and the chip/card correctly say Limited), then the
  service rebinds and authority returns to FULL. Last flip line 01:24:59.970 coincides with
  a dump command at 01:25:02. A subsequent 100 s window with **no** uiautomator
  (01:25:29–01:27:09) produced **zero** `authority regained` lines, while ticks
  220/240/260/280/300 (01:25:13 → 01:27:13) were all `authority=FULL degradation=null`.
  Screenshots with no dump running show "Protecting" (`/tmp/limited_now.png`,
  `/tmp/limited_now2.png`); dumps taken during suppression show "Limited" and can also serve
  a stale node tree after the state recovers (the known stale-tree trap).
- **Verdict — no product defect.** This is in fact positive evidence for the Path A→B
  handoff contract: on accessibility loss the FGS re-takes accounting authority
  (`authority regained — widening lookback to re-acquire the foreground`), and on rebind the
  FULL authority is republished. State-critical observations in this sweep are confirmed with
  `screencap`, not dumps alone.

### DEFECT (HIGH) FIXED 2026-11-02: Strict Mode's 24 h lock is bypassable for up to 60 s after enabling

Reproduced twice: first at 01:39 (screenshot `/tmp/strict_bypass.png`), then with a fresh
log capture at 01:42 (`/tmp/strict_bypass_fresh.png`).

- **Sequence (01:42).** Baseline: Enhanced ON, Strict OFF. Tapped Strict ON. Immediate dump:
  Strict switch `checked=true enabled=false` (locked — correct), **Enhanced switch
  `checked=true enabled=true`** — it should already be `enabled=false` with the cooldown
  warning. Tapping the still-enabled Enhanced switch turned protection off:
  `01:42:17.502 MyExodusProtection: protection OK -> DEGRADED_NO_DETECTION
  (authority=DEGRADED_NO_DETECTION cause=enhanced_mode_off oem=Google api=37)`.
  The Enhanced row then recomposed, saw `canDisable() == false`, and locked itself in the OFF
  position — protection is now disabled and cannot be re-enabled in-app until the cooldown
  expires.
- **Root cause (source).** `EnhancedModeToggleRow` reads `strictMode.canDisable()` and
  `cooldownActive` only when it recomposes (`EnhancedModeScreen.kt:64,67`). Its 60 s ticker
  (`EnhancedModeScreen.kt:50-55`) bounds the staleness but does not react to the sibling
  switch: `StrictModeToggleRow`'s handler (`EnhancedModeScreen.kt:121-124`) writes
  StrictModeManager and its own local `strictOn` state, nothing the Enhanced row observes.
  So for up to 60 s after Strict is enabled, the Enhanced row still holds the pre-toggle
  `enabled=true` / `warning=null` composition and accepts a tap.
- **Contract broken.** StrictModeManager's stated contract is a 24 h in-app lock
  (`StrictModeManager.kt:30-60`, `COOLDOWN_MILLIS = 24h`, `canDisable()` false while the
  cooldown runs). A one-tap bypass that also disables protection is the exact scenario the
  feature exists to prevent.
- **Fix direction.** One shared observable strict-mode state so both rows recompose from the
  same write — smallest option is hoisting the tick (and a bump from the Strict toggle
  handler) into the Settings index composition and passing it to both rows; the cleaner
  option is an observable cooldown deadline on `StrictModeManager` collected by both rows.
  Regression: enable Strict, stay on the page — Enhanced must immediately be disabled with
  the cooldown warning, and a tap must not change `enhancedModeEnabled` or stop the FGS.
- **Observation, not defect.** The Settings index Protection row still reads "Everything we
  can check is on" while Enhanced Mode is off; that subtitle describes permissions, so it is
  arguably correct, but it is misleading in this state.
- **Fix applied (2026-11-02).** `strictOn` is hoisted into `SettingsScreen` state and passed
  to both `StrictModeToggleRow` and `EnhancedModeToggleRow`, so the Strict toggle's write
  recomposes the Enhanced row in the same frame instead of waiting up to 60 s for its own
  ticker. The Enhanced lock and the cooldown warning are both driven by that hoisted value
  (`SettingsScreen.kt`).
- **Device proof (2026-11-02, AVD `emulator-5554`).** Expired the cooldown via
  `strict_mode.xml`, killed the process (`kill -9 $(pidof com.example.myexodus)` — not
  `am force-stop`, which sets APP_STOPPED on this platform) and relaunched. Tapped Strict ON;
  within ~1–2 s a fresh uiautomator dump read `Enhanced Mode -> checked="true"
  enabled="false"`, `Strict Mode -> checked="true" enabled="false"`, and both rows carried
  the "Strict Mode active — in-app controls unlock in 23h 59m" warning. The bypass tap that
  worked at 01:42 is no longer accepted. The pre-fix repros above stand as the regression
  description; re-run this leg on hardware after the next install.

### Strict Mode defect — fresh reproduction evidence (2026-11-02 01:42)

- Before: `Enhanced Mode -> checked="true" enabled="true"`, `Strict Mode -> checked="true"
  enabled="false"` (both read from the same dump; the write-checkable nodes).
- Tap Enhanced at (937, 842) → after: `Enhanced Mode -> checked="false" enabled="false"`,
  `Strict Mode -> checked="true" enabled="false"`; log line quoted above at 01:42:17.502.
- Restore: removed `strict_mode.xml` as root, force-stopped (force-stop also clears the a11y
  binding on this platform — documented above), relaunched, re-armed the service via
  `settings put secure enabled_accessibility_services …`, then
  `tick 100: usage=true authority=FULL degradation=null` (01:45:25) with Enhanced ON,
  Strict OFF, Discreet ON.

### Method correction: a black `screencap` is the secure-surface design, not a rendering fault (2026-11-02 01:58)

Two black captures this session (Settings → Coaching plan with the add-value dialog; Settings →
Risk windows) were initially triaged as a possible rendering/emulator fault. Source and the
window dump settle it: **both screens declare `SecureSurface()`** (`CoachingPlanScreen.kt:68`,
`RiskWindowsScreen.kt:70`), the count mirrors onto the shared window at `MainActivity.kt:565-575`
(`FLAG_SECURE` + `setRecentsScreenshotEnabled(false)` below API-level gate), and while the Risk
windows screen was composed `dumpsys window` showed `fl=… SECURE …` on
`Window{3365664 com.example.myexodus/MainActivity}`; pressing Back cleared it and the next
`screencap` rendered (non-black fraction 0.999). W6/INV-11, ADR #80. **So: black + intact
uiautomator tree on these surfaces is expected; the a11y dump and `dumpsys window` flags are the
oracles there, never the screenshot.** No app defect, no emulator quirk to reproduce.

### AVD — local protection notifications, live (2026-11-02 02:00–02:10 local)

Two local notification paths driven end-to-end on the unpaired AVD; both post, alert once, carry
their action, and are cancelled when the condition clears.

| Leg | Action | Observed | Evidence |
|---|---|---|---|
| Protection paused (1002) | Settings → Enhanced Mode OFF | `protection OK -> DEGRADED_NO_DETECTION (cause=enhanced_mode_off)`; notification id 1002 on `protection_alerts`, importance HIGH, `ONLY_ALERT_ONCE|AUTO_CANCEL`, action **[Fix]**; title "Protection is paused"; BigText "MyExodus can’t tell which app is open right now, so blocks won’t start. Open MyExodus to see what’s off and turn protection back on." | `dumpsys notification --noredact`; shade dump shows title/text/Fix |
| Fix action | Tap [Fix] in the shade | MyExodus foregrounded on Settings (target `protection-degraded` → TAB_SETTINGS) | `mCurrentFocus` MainActivity; dump starts "Settings / Permissions and enforcement status" |
| Re-enable clears | Enhanced Mode ON | `protection DEGRADED_NO_DETECTION -> OK (authority=FULL cause=path_a)`; 1002 gone; FGS 1001 re-posted; Home chip reads "Protecting" (screenshot crop) | log line 02:06:45.772; `dumpsys notification`; `/tmp/chip5.png` |
| Accessibility revoked (1004) | `settings put secure enabled_accessibility_services ""` + `accessibility_enabled 0`, wait 20 s | notification id 1004 on `enhanced_mode_health_v1`, AUTO_CANCEL, importance HIGH; title "Enhanced Mode is off"; text "Blocks still work, but they start up to a few seconds later while detection falls back to polling. Re-enable the accessibility service for instant blocking." (posted after the 15 s re-check) | `dumpsys notification --noredact` |
| Re-arm clears | restore the service id + `accessibility_enabled 1` | 1004 cancelled; `dumpsys accessibility` shows the service bound again ("MyExodus blocker"); Home chip "Protecting" | `dumpsys notification`, `dumpsys accessibility`, `/tmp/chip5.png` |

**Open finding (MEDIUM, copy): 1004's title names the wrong control.** It fires when the
*platform accessibility service* is revoked, but titles that "Enhanced Mode is off" while the
Settings "Enhanced Mode" switch (a separate stored preference, `enhanced_mode_title` /
`enhancedModeEnabled`) is still ON — demonstrated live: switch `checked="true"` in the same
minute the notification claimed it was off. The Settings row for the actual mechanism is
"Accessibility service" (`settings_accessibility_title`) and the Home limited card already uses
that name. Suggested title: "Accessibility service is off" (or "Blocking is slower"); the body
already ends with the correct instruction — only the title is misleading.

**Method note:** typing into a Compose text field on this AVD requires killing Gboard first
(`am force-stop com.google.android.inputmethod.latin`): while Gboard's IME window is up, injected
`input text` / `input keyevent` events are dropped (`VRI[InputMethod]: Cancelling event (no window
focus)`; Back did not even dismiss a Compose dialog). With Gboard stopped, hardware key events
reach the app normally ("My family" typed, counter 9/200). Harness limitation, not an app defect —
real typing goes through Gboard's own soft keyboard, which was not exercisable via adb here.

### AVD — milestone catch-up across a reboot-observed wall jump (guest 2026-11-14; host 2026-11-02 02:30–02:44)

- **Setup.** Cold emulator restart (`boot_count` 140 → 141) with the guest RTC re-initialised
  from the host (Nov 2), then `date` set to Nov 14 02:30 (`auto_time 0`). The stored streak
  anchor (wall Nov 8 area, elapsed ≈ 1 day) therefore took the reboot-detected wall-delta path
  with a large positive delta. `StreakTimer.evaluate` (`StreakTimer.kt:96-130`) accepts a
  positive delta and logs `REBOOT_WALL_DELTA`; only a negative delta is rejected.
- **Startup ANRs are the emulator storm, not the clock jump.** Two launches were killed with
  `ANR in com.example.myexodus` / `Reason: … failed to complete startup`; the trace’s main
  thread sat in `FirebaseIidExecutors.<clinit>` with 41 ms user + 26 ms system CPU and 27 s of
  scheduler wait, and the same startup-ANR reason hit Chrome, Dialer, and Calendar in the same
  boot window. The third launch, with `/proc/loadavg` at 3 after the storm, succeeded. No app
  crash record exists in the crash buffer.
- **Catch-up posted two 7-day notifications.** On the first foreground,
  `MilestoneAlerter.catchUpAll()` evaluated both active habits and posted both achieved-and-
  unclaimed milestones. `dumpsys notification --noredact`: id `3001`, channel
  `streak_milestones_v2`, importance 4 (HIGH), `AUTO_CANCEL`, visibility PRIVATE, per-streak
  tags `streak_milestone:7e97dcbb-…:7` → title "7 days free from Coffee" and
  `streak_milestone:982ce514-…:7` → "7 days free from Guest marker A", body "Your streak hit
  7 days. Open MyExodus to keep it going."
- **Splash + hero agree.** Home rendered the milestone card ("Milestone reached" / "One week
  free" / "Free from Guest marker A", 7/30/90 with 7 checked); **Continue** dismissed it. The
  hero then read "Days free from Guest marker A — **12 days**, Free since Nov 1, Next
  milestone: 30-day mark — 18 days to go", and the ring strip read "Day 12 of 90, Rings mark
  days 7, 30, 90". The notification is therefore catch-up delivery for a crossing that
  happened while no alarm could fire; the accumulator’s current value is 12 days.
- **Notification tap.** Tapping the grouped milestone card opened MainActivity (content intent
  is a `startActivity` record) and auto-cancelled the milestone records (both tags gone;
  FGS 1001 unaffected). The habit-selection half of the deep link (`EXTRA_START_HABIT` →
  `streaks.selectHabit`, `AppShell.kt:339-345`) could not be distinguished in this tap because
  the tapped habit may have been the already-selected one; the 30-day rerun below tests it
  decisively.

**Post-reboot accessibility state (environment, not an app defect).** After the cold reboot
the service was enabled but stuck: `Enabled services:{…BlockerAccessibilityService}` with
`Binding services:{…BlockerAccessibilityService}` and `Bound services:{}` for more than four
minutes, while the FGS logged `authority=DEGRADED_POLLING_ONLY` and the app showed the
**Limited** chip, the "Protection is limited" card with **Re-enable**, and alert 1004. This is
distinct from the 01:10 harness trap above: there `dumpsys accessibility` reported the service
enabled **and bound** while the chip read Limited; here the platform never completed the bind.
Recovery, driven through the app's own Re-enable button: Settings → Accessibility opened in
3.4 s; the MyExodus service detail showed "Use MyExodus blocker" ON; toggling it OFF then ON
and accepting "Allow" produced `Bound services:{Service[label=MyExodus blocker…]}` and live
`MyExodusBlocker: event … -> Allow` lines. Returning to the app then self-healed: chip
"Protecting", limited card gone, 1004 cancelled. The app detected exactly the right mechanism
and guided the user to the right switch; the bind failure is on the emulator/platform side.

### AVD — 30-day milestone (guest 2026-12-02) and 90-day via the scheduled-alarm path (guest 2026-01-31, host 2026-11-02 02:44–02:52)

- **30-day leg.** Reboot (boot_count 142) with the guest set to Dec 2 (≈ +18.0 d wall from the
  stored anchor). Two `MILESTONE_NOTIFICATION_POSTED milestoneDay=30` lines on the first
  foreground sweep, two `REBOOT_WALL_DELTA … deltaMs=1555258554 … ACCEPTED_WALL_DELTA` lines.
  `dumpsys notification --noredact`: id 3001, tags `streak_milestone:7e97dcbb-…:30` ("30 days
  free from Coffee") and `streak_milestone:982ce514-…:30` ("30 days free from Guest marker A").
  The Home splash card read "One month free / Free from Guest marker A"; **Continue**
  acknowledged it (`acknowledge(streakId, day, tWall)`). Hero then read 30 days, "Free since
  Nov 1", "Next milestone: 90-day mark — 60 days to go"; strip "Day 30 of 90".
- **90-day leg, no foreground needed.** Reboot (boot_count 143), guest set to Jan 31 2027,
  app left in the background. The scheduled milestone alarm posted both 90-day notifications
  at 23:47:12/13 — before any app launch — with accepted +60.0-day wall deltas. This is the
  alarm path, distinct from the catch-up sweep: delivery does not depend on the UI.
- **Splash.** First foreground showed "Three months free / 7✓ 30✓ 90✓"; Continue acknowledged.
- **Grouped-tap routing (harness finding, not an app defect).** Two auto-grouped milestone
  children under `g:Aggregate_AlertingSection` are not individually tappable: tapping a preview
  row routes to the group summary's synthesized content intent (no habit extras) and clears
  both children. Confirmed via `ActivityRecord` intent after the tap: plain
  `cmp=com.example.myexodus/.MainActivity`, no data URI. The decisive single-notification test
  below shows the per-notification deep link itself is correct.

### AVD — third habit, anti-minting, and the decisive milestone deep-link test (host 2026-11-02 02:52–02:56)

- **Third habit created through the UI.** "Test habit C" onboarded via the Home form with a
  backdated "Free since" date. Result: **1 day** (floor), not the backdated elapsed count —
  `onboard` seeds `currentStreakElapsedMs = 0` and the form copy states "The date is remembered
  as when you stopped; it doesn't add days by itself." Anti-minting verified live.
- **Add-habit CTA clipping (LOW/MEDIUM, open).** The form's primary button sits at
  [84,2029][996,2111] while the ScrollView viewport ends at 2064 and the bottom nav overlays
  it; the user must scroll to see "Start my streak". Other pages pad their scroll content by
  96dp; this form does not. Not blocking (scrolling reveals it), recorded for the fix queue.
- **Decisive deep link.** Selected habit switched back to "Guest marker A" (98 days), reboot
  (boot_count 144), guest jumped +8 d. A single `MILESTONE_NOTIFICATION_POSTED milestoneDay=7`
  posted with tag `streak_milestone:b35c69af-…:7` ("7 days free from Test habit C"). Tapping
  that single (ungrouped) notification at its dumped bounds switched the Home hero to "Days
  free from Test habit C" and rendered its own "One week free / Free from Test habit C" splash.
  **Conclusion:** the milestone content intent, the data URI, `EXTRA_START_HABIT`,
  `selectHabit`, and the acknowledgement path all work; the earlier failures were the
  collapsed-group summary routing, not the app.

### AVD — widget legs on nexuslauncher (host 2026-11-02 02:53–03:07)

Widget instance appWidgetId=6, provider `com.example.myexodus/.widget.DesertDawnWidgetReceiver`.
All taps at uiautomator-dumped bounds.

- **Whole surface → app (PASS).** Tap inside the card but off every inner control opened
  MainActivity with the plain launch intent (`Intent { flg=0x10000000 xflg=0x4
  cmp=com.example.myexodus/.MainActivity }`, no data URI) on the **Home** tab. That is
  `openApp` (spec NFR5), not `openStreaks`.
- **Day lockup → Streaks (PASS).** Tap on [341,738][454,872] opened the **Streaks** tab
  ("Your days free and what to expect", hero "Days free from Test habit C"). The transition
  lags `dumpsys` by ~2 s; the first read still showed the launcher.
- **Log urge (PASS).** Tap on "Save this moment" opened `WidgetActionActivity` with
  `dat=glance-action:/CALLBACK?appWidgetId=6&viewId=…`; the surface reads "Save this moment?
  It stays on your phone — nothing is shared." and "Keep holding — release to cancel".
  - 400 ms hold → released early → surface stays open, **no** row written.
  - 1500 ms hold (> `HOLD_CONFIRM_MILLIS = 1_200`) → activity finishes; Recovery → History
    shows exactly **1** urge logged, "Logged, no technique · Today · 2:54 AM"; "Urges logged 1",
    Week 1.
- **Log relapse (PASS).** Tap on "Reset the count" opened "Reset the count? The current count
  ends and a new one starts at Day 1."
  - 400 ms hold → cancelled, no change (widget still Day 7).
  - 1500 ms hold → widget re-rendered **Day 1 / "1d to 7-day mark"** in place. Home hero then
    read "1 day, Free since Feb 8, Next milestone: 7-day mark — 6 days to go, **Best run: 7 days
    — that record stays with you.**"; strip "Day 1 of 90". The best-run retention copy is the
    contract.
- **Reach out (PASS, unpaired).** Tap on "Reach out" opened "Reach out? No partner is set up
  yet — pair one in the app." with **Close** only — the honest `PartnerGate.Missing` state, no
  hold control, nothing sent. Close returns to the launcher.
- **Resize / variant switch (PASS with one gap).** With the instance selected:
  4×2 cells → expanded variant (hero top-left, footnote + icon controls at the bottom);
  widened to 5 columns (2 or 3 rows) → **tall variant** rendered: "NEXT MILESTONE" band on the
  left, "6d to 7-day mark" on the right, progress bar, controls bottom-right (this is
  `TallContent`, the finding-#11 fix). **Gap:** a 4-column-wide cell stretched to 3 rows stays
  on the expanded variant (the TALL preset is 312 dp wide and cannot be selected for a
  4-column cell), so the middle of the card is an empty band. LOW: cosmetic, launcher-cell
  dependent, not a data or blocking fault. `SizeMode.Responsive` presets: 120×60, 260×110,
  120×110, 312×174.
- **Launcher process death (PASS).** `am force-stop com.google.android.apps.nexuslauncher`,
  then HOME: the launcher restarted and the widget re-rendered from the persisted snapshot with
  the same Day 1 / "1d to 7-day mark" content.
- **Note on DEBUG logs.** The installed APK is byte-identical to `app/build/outputs/apk/debug/app-debug.apk`
  (sha256 4e99a6eb…), and `widget publish` DEBUG lines appear — but the widget's
  `widget size` / `widget options` DEBUG lines were **not** observed around the resize events.
  The mechanism was not instrumented further, so the variant switching above is evidenced by
  the rendered screenshots and the a11y trees, not by the size log. (Open question, one line of
  instrumentation if widget sizing is debugged again: which path recomposes the retained Glance
  composition on a host resize.)

### AVD — dirty process death and self-recovery (host 2026-11-02 03:08–03:10)

State before: pid 4028, `Bound services:{Service[label=MyExodus blocker…]}`, FGS notification
1001 present, Home chip "Protecting", hero "Days free from Test habit C — 1 day".

- **`kill -9 4028` (dirty death, no `am force-stop`).** Immediately: `pidof` empty;
  `Bound services:{}` and `Binding services:{}`; notification 1001 gone. The blocker is an
  in-process accessibility service, so a dead process is an unprotected window — the app's own
  chip is the honest indicator of it.
- **Self-recovery without user action.** At +9 s still dead; at +29 s a new pid (10984),
  `Bound services:{Service[label=MyExodus blocker…]}` restored, and notification 1001 re-posted
  (ADR #152's ensure-FGS-on-process-start path).
- **UI state survived.** Launching the app resumed the existing task on the Streaks tab with
  the same habit ("Test habit C"), 1 day, "Free since Feb 8", "Best run: 7 days — that record
  stays with you." Home hero identical.
- **Chip transition.** The first Home read immediately after the relaunch showed **Limited**;
  ~10 s later, with the service connected, it showed **Protecting** and no limited card. Same
  transition shape as the 01:10 harness trap, but here it self-healed without any Settings
  trip — the health state follows the service's connected signal.
- **Widget unaffected** — separate launcher proof above (launcher process death).

### AVD backup + restore on the live clock (2026-11-02 ~01:41–01:57 UTC)

The "Couldn't reach the server" pane seen at the end of the 2026-11-01 session was
environmental, proven now. Guest clock had been parked at Mon Feb 8 2027 (`auto_time=0`,
set for milestone/streak tests). Restoring `auto_time=1` snapped the guest to real time
(Mon Nov 2 2026). The shell could reach `firestore.googleapis.com:443` (`toybox nc -z`
exit 0) the whole time, and the app process still carried poisoned token/stream state from
the fake clock — Firestore `WriteStream` logged
`UNAVAILABLE cause=java.net.NoRouteToHostException`, then `UNAUTHENTICATED`. After
`am force-stop` + relaunch on the real clock, Settings → Account → Encrypted backup loaded
("Backup key is set up") on the first tap.

- **Back up now, no conflict (PASS):** passphrase `avd-qa-backup-2026-11-01-longphrase`
  (QA-only credential) → "Uploading… 0%" → "Backup complete" ("Your data is encrypted and
  saved to the cloud…"). Live pointer `users/nS0ntehLhfUlnDHgRrzjOguo6k33/backupControl/current`
  advanced revision 2→3: `backupId=41ef6d07d695294643668dd341067986`,
  `updatedAt=2026-11-02T01:50:43.039Z`, `sha256=c258abd6604e…44f8`. Distinct backupId and
  ciphertext hash — a real new snapshot, not a re-registration.
- **Finding (LOW, UX):** after "Backup complete" is dismissed, the passphrase bottom sheet
  is still open underneath; the user must tap Cancel. Same shape appeared after a successful
  upload in the conflict-leg test.
- **Restore from cloud (PASS):** passphrase → "Preparing your data…" (progress label reads
  **"Cancel backup"** during a restore — copy mismatch, LOW) → preview dialog:
  "Revision 3 · records: 50", then the exact split
  "Habits: 2 · streaks: 2 · milestones: 6 · activity entries: 22 · sessions: 14 · coaching
  steps: 3" plus "Your recovery plan settings are included" and the explicit replace/stay-put
  list. This is the concrete-number copy the owner asked for — no vague totals.
- **Keep Local (PASS):** dialog dismissed, local data untouched, returns to the passphrase
  sheet (field cleared; "current passphrase" is never persisted).
- **Restore Cloud Snapshot (PASS):** re-entered passphrase → log
  `MyExodusDb: Restored database verified; pre-restore copy removed`, app process restarted
  and Home came back with the 98-day streak intact. Blocks, screen-time history and partner
  pairing were left alone by design, per the dialog.

### Owner acceptance test — independent friction timers and individual cancel (2026-11-02, AVD)

Owner spec: with friction set to e.g. 4 h, open Instagram → a session starts; immediately open
TikTok → another session; keep going. Every timer must stay active, the user must see each one
separately and know which rule it belongs to, and must be able to cancel each one individually.

- **Coexistence + naming — PASS.** Friction set to 4 h 01 m (Settings → Friction timer → "Now
  set to 4h 1m"). Chrome and Photos rules active. Opening each app rendered its wall; "Open
  MyExodus Recovery" started its session. One Recovery screen showed both simultaneously as
  `Blocked: Chrome` 240:52 and `Blocked: Photos` 146:59, each with its own
  "Use it now (override)", above the shared "Leave the block alone" card.
- **Individual cancel — PASS, after fixing a ghost-row defect found by this test.** Tapping
  Photos' override opened "Override the block?" → "End the block". Log:
  `15:12:25.435 I/MyExodusAttempts: attempt logged: outcome=OVERRIDE rule=e7b1a61c-14d5-4225-8f2d-43ba53207b99 target=com.google.android.apps.photos`;
  rules snapshot v59 (3 app) → v60 (2 app); Rules moved Photos to Deactivated/Expired while
  Chrome stayed "Blocked now".
  - **DEFECT (HIGH) FIXED 2026-11-02: the cancelled session's timer stayed on screen.** After
    the override, Recovery kept rendering `Blocked: Photos` for 4+ minutes (145:38 → 144:57)
    with a live countdown next to the still-running Chrome card; only re-entering the tab
    cleared it. Root cause in `RecoveryScreen.kt`: the per-second ticker captured
    `val tracked = liveSessions` once and rewrote that stale list on every tick, so the row
    removed by the override's `reload()` was written back one second later. Fix: the ticker
    reads the current `liveSessions` each pass instead of the captured snapshot.
  - **Re-verified on the fixed build (AVD).** Rebuilt, `install -r`, a11y re-bound, Photos rule
    resumed. Photos' wall → Recovery showed Chrome 203:27 **and** Photos 240:50 together.
    Photos' override → "End the block" → `15:48:45.491 I/MyExodusAttempts: attempt logged:
    outcome=OVERRIDE rule=e7b1a61c… target=…photos`, snapshot v64, and the Photos row was gone
    from the same screen within 2 s (dump count of "Blocked: Photos" = 0) while Chrome's timer
    kept counting (203:27 → 203:06).
- **"Leave the block alone" — PASS.** The card routed Home (launcher focused) and unlocked
  nothing; Chrome's session survived the app process restart and a full AVD cold boot
  (Chrome still at 203:27 with its own override after reboot), proving leaving the app does
  not stop a timer.
- **Environment note.** The post-install package-update storm crashed this AVD's
  system_server once (the documented post-boot/cold-boot crash class), not app-caused; the
  guest was recovered with the documented SIGKILL-qemu + cold-relaunch recipe. The acceptance
  evidence above was captured after recovery on the rebuilt APK.

## 2026-11-02 overnight — two-phone signing, partner feed, and heads-up verdict (X6532 ↔ X6532C)

Build: debug APK 22:58:30 on X6532; X6532C on the previous build (no install was performed on
the borrowed phone — installing there requires per-action owner permission). Local rig over
the live backend, wireless adb `LAN-ADDRESS-REDACTED:5555` ↔ `192.0.2.105:5555`.

### Cold-window biometric signing — PASS (fingerprint)

Started from a genuinely cold signing window (`setUserAuthenticationParameters` expired).
Evidence chain:

- `MyExodus: SyncReport(... signingBlocked=true, pulledCount=0)` at **23:41:33** — the sync
  pass correctly refused to sign.
- Rule update journaled → `healBlockedPartnerSigning(forcePrompt = true)` → BiometricPrompt
  window added **23:41:51.926** (`dumpsys window` / Keyguard drain).
- Owner placed their fingerprint (owner-confirmed); prompt window removed **23:41:56.269**,
  no prompt error logged.
- Delivery: `MyExodusPartnerSync: drain: 3 event(s) delivered` at **23:42:20.622**.
- Receiver: `SyncReport(... pulledCount=3, signingBlocked=false)` at **23:42:28.182**; the
  sender's "1 update waiting to be sent / Send now" banner cleared.
- A pass at 23:42:06 still reported `signingBlocked=true` between the prompt closing and the
  drain — transient auth-token propagation, not a failed prompt (delivery followed 14 s
  later, same session).

The app-labeled "Use PIN/pattern" negative button was owner-verified visible in both light
and dark mode (the actual defect fixed earlier today was invisible SystemUI text). The
credential route behind that button was **not** re-exercised in this window; its last
device proof is ADR #112 Leg B (2026-09-28, cold acceptance with device credential only).
Recorded as: fingerprint leg PASSED today; credential leg PASSED earlier, not repeated.

### Partner feed accuracy — PASS, with one display defect fixed (ADR #257)

Owner question: "is the rules and blocks page being updated accurately? I saw a block
violation yet I see no activity of it, what of modification…"

- **Block violations do appear** — in Partner → How they're doing → Recent activity on
  X6532C, three `Blocked` rows (28m / 38m / 55m ago). They are deliberately not on the
  Rules & blocks page, which lists rule/batch lifecycle only (owner ruling 2026-10-31:
  "each page has its own responsibility").
- **Modification rows do arrive** — X6532C's Rules & blocks showed
  "cold-window-test.exampl — Rule updated · 5m ago", beside "Rule created · 23m ago" and
  "AnkiDroid — Rule assigned to you · 2h ago". This is the wire-v2 `RuleIdentity` path.
- **Duplicate-looking Blocked rows were genuine distinct attempts** (minutes apart, not
  seconds). The shipped 5 s dedupe (`AttemptRecorder`, pinned by `AttemptLogTest`) collapses
  only the two enforcement paths' view of one launch; no further change was needed.
- **DEFECT (MEDIUM, display) FIXED — raw package id.** The three Blocked rows read
  `com.ichi2.anki` because AnkiDroid is not installed on the viewing phone and the attempt
  wire text carried no label. Fixed additively in ADR #257: the attempt payload now carries
  the sender-resolved app label (optional key, ignored by older receivers; text format
  unchanged); the receiver shows its local label when the app is installed and the sender's
  otherwise. JVM-proven through the real-crypto coordinator path; the receiver-display leg
  on X6532C is **BLOCKED** until the new build is installed there (permission required).

### Heads-up banner on XOS — app correct, OEM presentation (verdict)

Channel `partner_accountability_events_v1` is `IMPORTANCE_HIGH` with sound, and
`dumpsys notification` shows the posted record with the expected flags. Two controlled
captures (23:18:49 partner event; 23:28:17 test alert) recorded no heads-up banner on the
X6532 dynamic bar; the notification was present in the shade both times. Verdict: the app
posts a correct high-importance notification; whether it banners is XOS's dynamic-bar
policy (device-owner-level setting), not app delivery. No code change. Honest limit: this
was observed on one OEM skin; it is not evidence that every OEM banners.

### Remaining device work (resolved 2026-11-03 — see the second 2026-11-03 pass below)

- Receiver display of ADR #257's label on X6532C — **PASSED 2026-11-03** on hardware (the
  current APK was installed on X6532C with the owner's per-action permission).
- On-device tap leg for ADR #258 (partner notification → the subpage it concerns:
  activity / rules / requests) — **PASSED 2026-11-03 for activity and rules**; the requests
  view was not triggered on device (no approval request arrived in the window) and remains
  JVM-covered.
- Everything else exercised in this window is PASSED with the evidence above.

## 2026-11-03 — final gates, APK install, and phone release

- **JVM suite:** 2,412 tests, 0 failures, 0 errors, 0 skipped (`:app:testDebugUnitTest`, final
  tree including ADRs #257/#258).
- **Android lint:** `:app:lintDebug` — "No issues found." (separate invocation from tests,
  ADR #208).
- **APK:** `assembleDebug` → `app-debug.apk` (50,541,984 bytes, 01:00), `install -r` on X6532
  (`lastUpdateTime=2026-11-03 01:01:20`); cold start resumed
  (`topResumedActivity=com.example.myexodus/.MainActivity`), process alive, crash buffer empty,
  no `ANR in com.example.myexodus`. X6532C intentionally not installed (borrowed phone,
  per-action permission required). **Superseded later the same day:** the owner granted
  per-action permission, the same APK was installed on X6532C (`lastUpdateTime=2026-11-03
  01:11:24`), and both installed base APKs hash-match the built APK (`sha256
  6b70e44a37242190066e634f50ea3d076824dfa98a8849de0ba84a02beb8dc0c`, `versionName=1.1`).
- **Test-infra note (no app change):** `AppLabelsTest.publishesNeverThrowWhileTheMainThreadKeepsApplyingSnapshots`
  no longer reads the state map back after its churn. Under 8×200 background publishes against
  a tight snapshot-applying thread, Compose 1.12 can transiently make the whole map unreadable
  to every snapshot (`IllegalStateException: Reading a state that was created after the snapshot
  was taken…`, Google issue 346818437). Reproduced with a throwaway Compose-only probe with zero
  app code on 4/6 runs; plain, `Snapshot.global`, and read-only-snapshot reads all failed
  together. The test's ADR-#251 contract ("no publish threw") still passes on every run; the
  label-visible path is pinned by the other tests in the class. No production occurrence of this
  class is known — `publishLabel` never threw in any run.
- **Phone release:** top-level `/sdcard/*.png` captures and `window_dump.xml` deleted on both
  phones; `stay_on_while_plugged_in=0`; `rotation-guard.sh` run for both serials
  (`accelerometer_rotation=0`); wireless adb disconnected (`adb devices` empty).

## 2026-11-03 — second pass: partner legs on hardware, wall frame capture, hand-back

Both phones were reattached over wireless adb with the owner's per-action permission. The
build under test is the same APK as the final-gates section above: `sha256
6b70e44a37242190066e634f50ea3d076824dfa98a8849de0ba84a02beb8dc0c` on the build host and on
both installed `base.apk` files (`versionName=1.1`; `lastUpdateTime` 2026-11-03 01:01:20 on
X6532 / 01:11:24 on X6532C).

### ADR #257 — receiver-side label on X6532C: PASSED

`com.ichi2.anki` (AnkiDroid) is installed on X6532 (FtVxfq…) but **not** on X6532C
(`pm list packages | grep ichi2` returns nothing there). X6532C's Partner → How they're doing
→ Recent activity renders "AnkiDroid — Blocked · 10m ago" and "AnkiDroid — Blocked · 11m ago"
for two fresh blocked attempts, while an older row from ~2 h earlier still reads
`com.ichi2.anki — Blocked`. The sender-resolved `al` label on the attempt wire is the only
possible source of the name on that phone; the older row is the before/after contrast.
Evidence: `app/docs/evidence/2026-11-03-adr257-receiver-label-x6532c.png`.

### ADR #258 — notification tap opens the subpage it concerns: PASSED (2 of 3 views on device)

With MyExodus backgrounded on X6532C (HOME, process alive; no force-stop), both partner
notifications were tapped from the shade after expanding the group:

- **VIEW_RULES:** "Rule update from Musila — cold-window-test.exampl was turned off." →
  landed on **Rules & blocks** with "4 rule updates", including "Rule turned off · 5m ago".
  Evidence: `app/docs/evidence/2026-11-03-adr258-rules-tap-x6532c.png`.
- **VIEW_ACTIVITY:** "New activity from Musila" → landed on **How they're doing** with the
  chart reading Today=6. Evidence:
  `app/docs/evidence/2026-11-03-adr258-activity-tap-x6532c.png`.

The **requests** view was not triggered on device: no approval request arrived during the
window, and producing one requires a signed partner action. All three view mappings are
JVM-proven (`PartnerDeepLinkViewTest` 4/4, with the plan-extra-outranks-view-extra contract;
`PartnerNotificationsTest` 29/29). This is 2-of-3 on hardware, stated as such.

### Rule updates deliver automatically — device half: PASSED

X6532C's own `files/rule-delivery.log` (pulled before the wipe) records the delivery of the
disabled throwaway rule with no "Send now" tap on the sender:

```
2026-11-02T22:29:31.382Z event=049ebf1c… stage=received change=DISABLED label=cold-window-test.exampl
2026-11-02T22:29:31.406Z event=049ebf1c… stage=stored stored=true
2026-11-02T22:29:31.429Z event=049ebf1c… stage=notify notify=posted
```

The same log shows the earlier CREATED (20:24:45Z) and UPDATED (20:42:25Z) deliveries of that
rule. On the sender, the "1 update waiting to be sent" banner was stale after the automatic
delivery. The 2026-11-02 cold-window leg additionally proved the prompt path when the keystore
window is cold (forced heal → BiometricPrompt → owner fingerprint → `drain: 3 event(s)
delivered`, banner cleared).

### Block wall transition — frame-level capture on X6532: PASSED

The Animation.MyExodus.None fix (BlockerActivity inherits `windowAnimationStyle` from
`Theme.MyExodus.Blocker`, themes.xml:13-21) was re-captured on hardware with the device's
animation scales at 0.5 (all three). A blocked AnkiDroid launch was recorded at ~43.5 fps
(222 frames) and every frame was inspected by per-frame edge-column colour analysis plus
contact sheets:

- launcher through frame 90 (t=2.008 s, both edge columns still launcher-coloured);
- the wall window is **full-screen from its first painted frame** (frame 91, t=2.035 s);
- no frame anywhere in the transition shows a wall/app boundary — the 273 ms right-to-left
  slide is gone.

The live BlockerActivity window reports `wanim=0x7f120005`, and `aapt2 dump resources` on the
installed APK resolves `0x7f120005` to `style/Animation.MyExodus.None` with all four
`activity*Animation` items = `@null`. Evidence:
`app/docs/evidence/2026-11-03-wall-no-slide-launch-strip-x6532.png` (frames 82-101) and
`app/docs/evidence/2026-11-03-wall-activity-host-x6532.png` (settled activity-host wall).

Observation (not a fix-scope defect, one launch): on the first capture the accessibility
overlay took over from the activity host and the target app's own UI (including AnkiDroid's
backup dialog) was visible for roughly 0.9 s before the overlay card painted. The recording has
a 260 ms encoder gap in exactly that interval, so the exact exposure is uncertain, and whether
the already-attached overlay was intercepting touches during the gap was not measured. A second
launch kept the activity host full-screen for the whole capture.

### Pending-delivery banner grammar — device leg deliberately not run

The singular body fix is present in the installed APK (`plurals/rules_pending_delivery_body`:
one = "Confirm with your device key to send it to your partner.", other = "…send them…"). The
device leg would require leaving a rule update queued on X6532 by cancelling the cold-window
biometric prompt; with X6532C being wiped and returned in the same session that queued update
could never be delivered, so the sender would be left with a stuck banner. Not run for that
reason; the resource and the JVM binding are verified, the on-device singular body is not.

### Coverage ledger — what remains BLOCKED and why

- **Physical typed re-pair ceremony (unpair → invitation code → SAS → both authorize):**
  BLOCKED. The shipped V1 ceremony is typed Crockford codes only (partner-pairing plan §1/§2,
  ADR #97). Both the unpair (`MainActivity.endPartnership`, MainActivity.kt:920-925) and each
  SAS-gated pairing signature (`PairingCeremonyScreen.kt` `signAndFinish` → `deps.sign`) route
  through `signWithAuthPrompt`, which raises a BiometricPrompt unless the signing key's 900 s
  keystore window is warm. The last owner authentication was 2026-11-02 23:41:56; the window
  expired ~23:56, and the owner was unavailable at 01:40. **Unblock:** the owner present for
  about five minutes to authenticate on both handsets; then run unpair → re-pair twice. The
  emulator cannot substitute (live backend rejects emulator pairing).
- **Composer send on the current build:** BLOCKED by the same signing gate (composer auth is
  `signWithAuthPrompt`, MainActivity.kt:797). The 45 s per-leg timeout fix itself is JVM-proven
  (`MessageDeliveryTest`); the normal send path on this build was not re-run because reaching
  it requires the prompt.
- **Requests-view notification tap:** closed by owner ruling 2026-11-05 — JVM-covered; no
  production entry point calls `ApprovalFlow.requestApproval`, so no user-reachable request
  notification exists to tap (ADR #258). The owner exercised a rule-update tap on hardware
  himself (notification → Rules & blocks).
- **OEM heads-up banner policy:** XOS-specific verdict above; other skins remain unmeasured.

**Task-list honesty correction.** In the previous session's task list, task #5
("Unpair, re-pair, adversarial pairing") and task #12 ("Wall frame-level re-capture on X6532
hardware") were marked `completed` while the entries themselves said BLOCKED. #12 is now
genuinely PASSED (above). #5 remains BLOCKED — this file has said so throughout, and that
record stands. The two-phone live partner matrix (task #4) was genuinely completed on
2026-11-01 21:39 and is not part of the blocked set.

### Hand-back and release (X6532C)

- `pm clear com.example.myexodus` on X6532C → `Success`; the app's `files` directory is gone
  and the package remains installed. Its `com.example.myexodus.test` androidTest package was
  left installed (removing it was not covered by the granted per-action permission).
- Harness artifacts deleted from `/sdcard` root on both phones (95 files on X6532, 120 on
  X6532C: XML/UI dumps, log dumps, screen recordings). User folders were not touched.
- `stay_on_while_plugged_in=0` on both; `scripts/rotation-guard.sh` run for both serials and
  read back `accelerometer_rotation=0`, `user_rotation=0`; both phones on the launcher, screens
  off (`KEYCODE_SLEEP`); wireless adb disconnected (`adb devices` empty).

## 2026-11-05 — re-pair, promptless auto-delivery, attempt consent, and named activity notifications

Both phones were reattached over wireless adb with the owner present. Installed builds:
X6532 `lastUpdateTime` 16:32:07 (APK built 16:22 — banner and forced prompt removed),
17:25:20, then 17:35:06; X6532C `lastUpdateTime` 16:57:43, then 17:35:35. Both phones ended
the session on the same 17:24 APK (activity notification copy), installed with `-r` only.

### Re-pair — PASSED

Fresh live-backend pairing after an unpair: invitation code entered on the second phone, SAS
compared and confirmed on both, both screen-lock authorizations given by the owner; both
terminals showed "You're paired" and the new enrollment was stored at 15:31:56 local.

### Attempt delivery is consent-gated per pair — finding

The freshly re-paired pair had "Blocked apps, recovery & device — **Not shared**". The
reconciler emits attempts only when `profileConsent.shareCheckins` is true and only for
attempts recorded at/after `checkinsEnabledSinceMs`; rule lifecycle events travel by rule
assignment and are not gated. That explains the owner's "no rule violation on the partner
side" report: rule changes had been delivered, attempts had not.

After turning the switch on (~16:47:35 local):

- `16:47:43.739 MyExodusAttempts: attempt logged: outcome=BLOCKED … target=com.gallery20`;
  sender `drain: 1 event(s) delivered` at 16:47:57; X6532C posted the activity notification at
  16:48:04 (generic copy in that build: "New activity from Musila").
- `16:54:47.138` a second blocked AI Gallery launch; sender drain 16:59:38.646; X6532C
  `rule-delivery.log` `stage=received` 16:59:45.493.

So the pipeline is sound; the switch was off. Whether attempts should be consent-gated at all,
and whether a fresh pair should default the switch on or ask at pairing, is an open product
decision (vault OPD #42) — not changed in code.

### Promptless auto-delivery on the 16:22 build — PASSED (warm window)

The 16:22 build deletes `PendingDeliveryBanner`, `onRetryRuleDelivery`, the prompt cooldown and
the automatic path's `signWithAuthPrompt` call (ADR #259). A partner-shared rule created on
X6532 after its 16:32 install reached X6532C automatically: X6532C receipt
`13:37:56.011Z … change=CREATED label=AI Gallery` → `stored=true` → `notify=posted`, with no
Send-now tap and no device-key sheet; the partner Rules page showed "AI Gallery · Rule
created · 8m ago". The pending-delivery banner cannot appear — the composable and its resources
no longer exist in the build — and the automatic path contains no prompt call.

**Cold-window deferral — PASSED (both halves, 17:49-17:55).** The window genuinely lapsed
while adb held the screen awake. The QA-check batch was created at 17:49:27; the 17:49:53
pass reported `signingBlocked=true`, keystore2 logged `enforcements.rs:656: matching auth
token is expired` → `KEY_USER_NOT_AUTHENTICATED`, **no sheet, banner or prompt appeared**, and
the batch event stayed journaled (the partner had no notification and no feed row). After a
normal owner fingerprint unlock (17:54), the next foreground passes delivered it with no
interaction: subject `drainedCount=1`, partner notification posted — "You were added as
accountability partner on the QA check batch (2 items)." Delay, never drop, never prompt.

### Activity notifications now name the act, the app and the batch (owner ruling 2026-11-05)

Owner report: rule alerts read "Rule update from Musila — AI Gallery was deleted", while a
blocked attempt read only "New activity from Musila". `notifyPartnerEvent` now routes
`TYPE_ACTIVITY` through `activityCopy` (ADR #260):

| Wire | Title | Body |
|---|---|---|
| `blocked:com.gallery20` | "Musila tried to open a blocked app" | "AI Gallery was blocked." |
| `blocked:<pkg>\|<batch>` | same | "Instagram was blocked in the Things I'm trying to quit batch." |
| `override:<pkg>[|<batch>]` | "Musila used an override" | "…was opened with an override[ in the … batch]." |
| `recovery:<technique>:<outcome>` | "Musila logged a recovery step" | "Urge surfing · Completed" |

The app name is resolved locally, else from the sender's `appLabel` (ADR #257), else the package
id. Lockscreen public copy stays the generic `partner_event_*` form (FR-13); undecodable text
falls back to the generic named copy.

Verification: `PartnerNotificationsTest` — `blockedAttempt_namesTheActTheAppAndTheBatch`,
`blockedAttemptOutsideABatch_hasNoBatchTail`, `overrideAttempt_namesTheOverride`,
`recoveryStep_namesTheTechniqueAndOutcome` (all green, including the lockscreen-public
assertion); Android lint clean (run alone per ADR #208); `assembleDebug` green; APK (50,544,300
bytes, built 17:24) installed on X6532 at 17:25:20. **Partner display — PASSED:** the same
17:24 APK was installed on X6532C at 17:35:35 (per-action permission given), and the blocked
attempt at 17:41:33 (delivered 17:41:50) produced "Musila tried to open a blocked app" /
"AI Gallery was blocked." on the partner phone.

### Batch notification shape (answer to the owner's 2026-11-05 question)

Batches are atomic objects (ADR #249): members are read-only rows inside the batch card; there
is no per-item toggle, delete or modify in the UI, and the batch editor is create-only. Each
batch operation journals exactly one batch-level row per assigned pair, so the partner gets one
notification:

- create/assign → "You were added as accountability partner on the Things I'm trying to quit batch (5 items)."
- pause → "The Things I'm trying to quit batch was turned off (5 items)."
- resume → "The Things I'm trying to quit batch was changed (5 items)."
- remove partner → "You were removed from the Things I'm trying to quit batch (5 items)."
- delete → "The Things I'm trying to quit batch was deleted (5 items)."
- whole-batch expiry → "The Things I'm trying to quit batch ended — its set duration ran out (5 items)."

The only member-level rule copy that exists is a partial natural lapse (one member's timed
block runs out while the others continue): "Instagram ended in the Things I'm trying to quit
batch — its set duration ran out." Blocked attempts name the batch when the wire carries it:
"AI Gallery was blocked in the Things I'm trying to quit batch." Adding per-item controls would
need a partial-batch state the model deliberately does not have; not built, not requested.

### Every rule verb reaches the partner — PASSED on X6532C (17:39-17:55)

Both phones on the 17:24 APK. Each event was created on X6532 and read back from X6532C's
notification record (`dumpsys notification --noredact`) and Rules & blocks feed:

| Action on X6532 | Deliver | Partner notification (title / body) |
|---|---|---|
| Rule added (AI Gallery) | 17:39:15 | "Rule update from Musila" / "You were added as accountability partner on AI Gallery." |
| Blocked attempt (AI Gallery) | 17:41:50 | "Musila tried to open a blocked app" / "AI Gallery was blocked." |
| Rule turned off | 17:43:45 | "Rule update from Musila" / "AI Gallery was turned off." |
| Rule changed (re-enabled) | 17:45:50 | "Rule update from Musila" / "AI Gallery was changed." |
| Rule deleted | 17:47:28 | "Rule update from Musila" / "AI Gallery was deleted." |
| Batch created (QA check, 2 apps, partner assigned) | cold 17:49:27 → delivered after the 17:54 unlock | "Rule update from Musila" / "You were added as accountability partner on the QA check batch (2 items)." |
| Batch deleted | 17:55:33 | "Rule update from Musila" / "The QA check batch was deleted (2 items)." |

The partner's Rules & blocks feed listed every verb under the named rule/batch — "Rule
created · 2 items", "Rule updated", "Rule turned off", "Rule deleted" — matching the
notifications. No Send-now control, banner or prompt exists in the build or appeared during
the run; the only user interaction in the whole sequence was the one normal unlock needed to
warm the signing key (see cold-window above).

**Heads-up banners are OS-decided.** Both the create and the delete notifications were posted
to the same per-pair tag, so each replaces the previous tray entry. The batch-create update
landed while the phone was just waking / the app was in front and did not raise a banner;
the delete landed while the phone was in active use and did. The app requests a high-importance
alerting notification in every case (channel `IMPORTANCE_HIGH`, no silent/only-alert-once
flag, record `mIsInterruptive=true`); whether an update becomes a heads-up is the system's
call. Tray and the partner feed were correct for both. This is the known OEM heads-up policy
item — to be re-checked on a second OEM skin.

### Hand-back and release

- Test data removed: the QA-check batch deleted, the AI Gallery test rule deleted, and AnkiDroid
  restored after it was disabled by a mis-aimed tap on the rules list (snapshot v29: 3 app,
  3 URL rules — the pre-test set).
- `/sdcard/ui.xml` removed on both phones; `stay_on_while_plugged_in=0` on both;
  `scripts/rotation-guard.sh` run for both serials and read back `accelerometer_rotation=0`,
  `user_rotation=0`; both phones on the launcher, screens off; wireless adb disconnected.
