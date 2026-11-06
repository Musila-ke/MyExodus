# UI/UX Improvement Session — Plan

**Status:** COMPLETE (2026-09-19) — all 22 findings implemented (+2 found during the pass, #23/#24), suite green (673/0/0/0, 90 classes), every item device-verified on the X6532 (light + dark) with the two close-out legs (three install-pending items, #2/#3 emulator captures) documented in the Verification log. Approved 2026-09-19 (user: "proceed"). Original request 2026-09-18: "we also need a session for UI UX improvements cause bro its crap".
**Theme:** Desert Dawn (user-confirmed 2026-09-18) — no color-scheme change unless the user asks.
**Target device:** X6532 (physical, primary); emulator only for stock-Android checks.

---

## Method (standing rules apply)

1. **Research before building** (user standing rule): award-winning UI references + senior UI/UX guidance online before touching any screen; official Material 3 guidance where the question is a platform one.
2. **Screenshot audit first:** every surface × every state (loading / empty / error / success; light + dark) captured on-device and catalogued in the findings table below — no fixes from memory.
3. **Fix systematically:** one screen at a time, kept buildable; device-verify each with before/after screenshots.
4. **State + error rules already standing:** three-part error copy (what / why / action), UX timing thresholds (1 s / 10 s / skeleton), no backend internals on screen.

## Scope

All app surfaces: Home, Rules, Recovery, Streaks, Partner, Settings, all dialogs/sheets, onboarding wizard, block screen, widget, notification copy. (Partner UI assets not yet built — pairing screen, approval UI, unpair — are audited when they ship, not here.)

**Scope additions (user, 2026-09-19 — mandatory, apply to the audit AND the fixes):**

1. **Graceful degradation everywhere** — every page *and every individual section* audited in
   its degraded states (blocker off, a11y service off, usage access off, notifications denied,
   overlay denied, DB issue, offline/no network, no partner, unpaired, wiped). A section that
   degrades must say what happened, why, and what to do — never a bare error or a silent hide.
2. **States per page and per section** — empty / loading / error / success, each captured or
   explicitly marked unreachable-with-reason. No section may be graded only on its happy path.
3. **Improvements across everything** — including the bottom navigation menu — every single
   feature, not a subset of screens.
4. **Color consistency** — no random/ad-hoc color schemes. One Desert Dawn token system across
   all surfaces; off-token colors (stray hex, vibe greys, one-off accents) are themselves
   findings. Semantics stay stable: teal = primary/protecting, gold = streak/milestone only,
   red = degradation/error, and the same role uses the same color on every screen.
5. **Logical/functional coherence (user, 2026-09-19)** — every surface must account for the
   app's real multiplicities, not assume a single instance: a user may have multiple habits,
   multiple rules/schedules, multiple blocks. User's example, verbatim: *"ex the widget we have
   mltple or might have multiple tracks not just tracking one habit but multiple can the widget
   handle that? thisis an example dont lmit to this"*. Also required: **state coherence between
   sections** — two surfaces describing the same state must never contradict (status chip vs
   banner, notification pairs, widget vs app at the same moment); completed actions must clear
   their stale alerts; and any deferral (known limitation) must be visible to the user wherever
   it bites, not only in a code comment.

## Constraints

- **No IA changes without user sign-off** (e.g. moving Settings out of the bottom bar, merging tabs).
- **Bottom nav:** labels-on-selection-only is deliberate — 6 destinations, Material caps labeled bars at 5 (ADR #47). Do not re-add always-on labels; do not shrink them. (The nav bar itself IS in scope for polish/consistency — item 3 above.)
  - **Amendment 2026-09-22 (supersedes the line above; ADR #74):** the user signed off on a notched floating bar with **all six labels always visible** (answers: "Desert Dawn tokens", "All labels, keep 6 tabs"). Measured on the X6532 (360dp), six labels coexist at 10sp with zero tracking with ~7dp between neighbours, so the constraint's premise ("physically cannot coexist") is retired. The ship rule is adaptive, keeping this line's behaviour as the fallback: labels are all shown only while the measured widest label still leaves ≥4dp of pitch — a narrower bar or a larger system font scale reverts to selected-only. Tab count, tab order and the deep-link escape hatch are unchanged.
- **State semantics are ADR-pinned** — a visual pass must never soften or hide honesty copy (AD-05 staleness, chain-status distinctness) or permission banners.
- Existing tests must stay green; UI copy changes keep the string keys they replace.

## Persona (locked 2026-09-19)

**The restricted user.** 24-year-old Kenyan student mid-quit of compulsive social-media/porn
use; one-handed on an Infinix XOS phone; often mid-urge, stressed, low patience; moderate tech
comfort; his accountability partner is a friend who checks the dashboard occasionally.
Every finding must be defensible from this persona's view. The **first-time-user lens** runs on
every surface alongside it (could a brand-new user complete the task without docs?).

## Research digest (2026-09-19 — the bar findings are judged against)

**From award-winning habit/wellness apps** (Streaks — Apple Design Award; (Not Boring) Habits
— ADA 2022; Finch; Tiimo; Loop):
- Celebration feedback is *load-bearing*, not decoration — immediate, satisfying confirmation
  on each logged win ("compressed celebration loop").
- Logging/primary action ≤2 taps; the 5-second check-off is the single most reliable predictor
  of sticking.
- Forgiveness beats punishment on a miss; streak UI must handle the lapse without shame.
- Constraint is design (Streaks' 12-habit cap): protect the user from over-tracking.

**From friction-blocker evidence** (One Sec CHI '24 longitudinal study; Opal; ScreenZen;
ScreensDesign's 13-app review):
- Guilt/shame and scary stats backfire (Opal CEO); copy must be encouraging, never moralizing.
- Rule/limit cards must state in *text* (never color alone): what's blocked, allowance, what
  counts toward it, reset time, strictness, behavior at the limit, and the edit path.
- Schedules read as a verifiable sentence, incl. overnight windows and timezone.
- Enforcement and recovery live in one view; recovery actions distinctly named with
  consequences disclosed before confirmation; emergency/escape access never gated.
- Reports connect every chart to an action the user controls; label period/unit/source.
- Fixed friction habituates — vary the intervention; keep the app installed and gate entry.

**From the senior mobile-polish checklist** (Android Build-for-Billions UI guidance; M3 visual
design references; pre-delivery checklists):
- 8dp grid (4dp fine); card padding ≥16dp; touch targets ≥48dp; safe areas respected; no
  content hidden behind sticky bars.
- Type: ≤5 sizes, hierarchy via weight, body line-height 1.4–1.6, tabular figures for numbers,
  titles ellipsize at 2 lines.
- Contrast: body ≥4.5:1, large text/UI ≥3:1, never gray-on-gray; dark theme uses tonal
  surfaces; every state tested in both themes.
- Pressed/active feedback on every tappable, without layout shift.
- Empty state = short heading + one sentence + primary CTA; never a bare "nothing here".
- Loading: skeletons for content (>1s), no indicator <300ms, reserve space (no layout jump).
- Errors: specific, calm, field-level when possible, retry/edit action, never a dead end.
- Motion 150–300ms, platform easing, reduced-motion respected; no decorative motion.
- One icon family; filled = active, outlined = inactive; color never the only indicator.
- Anti-patterns: <48dp targets, <11sp text, hardcoded spacing/colors, spinner-only loading,
  empty states without CTA, errors without retry, modals as first resort.

## Findings (fill during the session — do not guess ahead)

Evidence lives in `/Users/musila/Obsidian/MyExodus/ui-audit-2026-09-19/` (filenames below). All rows captured on X6532, dark theme unless noted.

| # | Screen | State | Issue (observed) | Severity | Fix | Status |
|---|---|---|---|---|---|---|
| 1 | All screens | dark (every tab) | Selected bottom-nav pill, Rules' "+ App"/"+ Website address" buttons, and the Partner chain-status card render **lavender/purple** — off-token (Desert Dawn has no purple). Root cause: `secondaryContainer`/`onSecondaryContainer` are never defined in `ui/theme/Theme.kt` (both schemes) → M3 baseline purple leaks into NavigationBarIndicator, `FilledTonalButton` (RulesScreen.kt:299 AddButton), PartnerScreen.kt:730 neutral card. Ev: `15-*-dark.png`, `16-settings-dark-top.png`. | High | Define both slots in Light+Dark (warm taupe container + on-container ink, distinct from errorContainer as the Partner card requires); re-verify nav pill, Rules buttons, partner card in both themes. | Fixed + device-verified (`fix-E1/E2/E3/E4/E5-*-dark.png`, `fix-D1..D5-*-light.png` — taupe pill, taupe tonal buttons, no purple anywhere; `fix-E1-home-dark.png` background is the 11:22 capture re-taken after a mis-tap). |
| 2 | Home | no-habit (`04`), loading (`01`) | Hero numeral slot renders the em-dash placeholder as a **stray gold bar** — reads as glitch/redacted content, not "no value". In loading it sits above "Reading your streak…"; in no-habit it sits alone above the explanation. | Medium | Loading: skeleton bar in the numeral slot (reserve space). No-habit: drop the placeholder glyph entirely. | Fixed (code); loading/empty capture on the emulator leg (data-disposable) — see §Verification log. |
| 3 | Home | empty (no habit `04`, no blocks `04`/`15-home-dark`) | Both empty states name the destination but offer **no CTA** ("set one up in the Streaks tab"; "Blocks you create under Rules show up here…"). Violates ≤2-tap + first-time-user lens. | Medium | Add "Set up a habit" → Streaks and "Add a block" → Rules (both already tab-switchable). | Fixed (code): "Set up a habit" → Streaks tab, "Add a block" → Rules tab; empty-state capture on the emulator leg. |
| 4 | Home | blocker off (`03`) | "Blocker off" chip is a dead end: no why, no action, no route. The good pattern (`01`: Task-Manager banner = why + "Re-enable" button) is not reused for the generic degraded state. Mandated degradation axis | High | Reuse the banner treatment whenever blocker is off (cause + action); make the chip tappable → Settings. | Fixed + device-verified (`fix-F22-degradation-home-dark.png`: red "Blocker off" chip + cause/action banner with Fix; `fix-D0-chip-to-settings-light.png`: chip tap → Settings). |
| 5 | Rules | empty (`15-rules-dark`) | Empty state itself is good (honest copy, both add-CTAs present). Populated / limit-reached states not yet captured. | Low | Capture populated state after fix pass starts. | Captured: populated `fix-E2-rules-dark.png` + light; retired-rule "Expired" + toggle states seen live; rule editor `20-rule-editor*.png`. |
| 6 | Partner | paired (`15-partner-dark`) | "Paired with dcee1e380ead…" shows a raw ID fragment — first-time lens fails ("who is this?"). | Medium | Check what partner-facing data actually exists (no invented data); show a human-readable label or explain the code in the card. | Fixed + device-verified (`fix-E4-partner-dark.png`: card explains the code is the mutual device identifier both sides see). |
| 7 | Settings | dark (`16`,`17`) | Protection / Enhanced Mode / Widget sections coherent; status dots map to semantics (teal=on, red=not exempt, grey=unknown); toggles teal-on. | Pass (pending light) | Light-mode + contrast check. | Pass both themes: dark `fix-E5-settings-dark.png`; light `fix-D5-settings-light.png`, `81`–`83`. |
| 8 | All | foreground deep link | `am start --es extra_start_tab X` with app on top → "intent delivered to top-most instance" and the tab does **not** switch (tap on tab does). Candidate — possible same-value-state swallow or singleTop path in MainActivity.onNewIntent / AppShell LaunchedEffect. | Medium | Focused repro with logcat, then fix re-delivery of same tab value. | Fixed + device-verified (`fix-F8-deeplink-recovery.png`: app on Home, `--es extra_start_tab Recovery` → Recovery tab). Root cause: launchMode + onNewIntent re-delivery. Note: values are capitalized ("Recovery"), lowercase silently no-ops. |
| 9 | Streaks | populated (`14-streaks-populated-dark`) | Withdrawal-timeline week bar renders two-tone (teal + purple + dot). Root cause (code): `WithdrawalTimelineCard.kt` `LinearProgressIndicator` — M3 default track resolves to an undefined slot in `Theme.kt` (baseline purple); active/dot = primary teal. Same undefined slot family as finding 1. | Medium | Fixed by finding 1's `secondaryContainer` definition; re-verify on device. | Fixed + device-verified (`fix-E3-streaks-dark.png`: week bars teal on taupe track, "You are here" teal; light `79`, `fix-D2-streaks-light.png`). |
| 10 | Widget | compact dark (`41`), light (`51`), full degradation (`66c`) | Status word rendered in **gold** (`GlanceTheme.colors.tertiary`) — gold is streak/milestone-only; expanded variant correctly uses `onBackground`. Compact also truncates the state itself: "Day 0 · Not protec…" — the incomplete-claim the design comment claims to forbid. | High | Non-discreet compact: status in onBackground (state-honest, never gold); fix truncation (status wins the single line, or shorter label). | Fixed + device-verified (launcher dark: compact shows "Protected" alone in neutral ink, no gold, no truncation; light leg `54`/`56`/`61`). |
| 11 | Widget | tall-narrow (`46`,`47`) and wide-1-row (`53`,`54`) vs expanded (`49`,`56`) | `SizeMode.Responsive(COMPACT, EXPANDED)`: launcher picks a preset only if it fits in BOTH dimensions. Tall-narrow 2×2 renders compact stretched (dead lower half); 4×1 stays compact — though the code comment claims "height alone decides". Intent/implementation mismatch. | Medium | Add a narrow-tall preset or let compact fill height gracefully; align comment with actual gating. | Fixed + device-verified (`fix-F11-widget-2x2-dark.png`: resized 2×1→2×2 on the launcher, bounds `[32,136][360,516]`, expanded face renders — "Day 0 / Social media / Protected / Manage urge"). `SizeMode.Responsive` gains `NARROW_SIZE`. |
| 12 | Widget | multi-habit (user-raised 2026-09-19) | Widget shows only the app's *displayed* habit and nothing names it; snapshot store is global (all widget instances identical). With 2+ habits the user cannot tell which track the widget watches — the deferral is in a code comment only, never on the surface. | Medium | Minimal: habit name/short label on the widget; per-widget config/switcher stays deferred but must be recorded, not silent. | Fixed + device-verified (`fix-F11-widget-2x2-dark.png` shows the habit label "Social media"; label clears when the habit is removed — `WidgetSnapshotTest.removedHabit_clearsTheStaleLabel`). Per-widget switcher deferral recorded in ADR #56. |
| 13 | Home | post-degradation + Re-enable (`73`) | Status chip reads "Protecting" while the banner directly below says "Time limits stay paused…" — same-instant contradiction. Root cause (code): `HomeScreen.kt` StatusHeader chip is driven by `monitor.enabled` (service running) while the banner is driven by `ProtectionDegradation` (can anything actually detect?) — two different signals, and the chip's binary can't express "service up but blind". Chip strings are also hardcoded Kotlin literals, not resources. | High | Drive the chip from the same authority signal as the degradation banner (tri-state), or suppress/merge when degraded. | Fixed + device-verified (tri-state live on device: "Protecting" teal / "Limited" taupe (a11y off, usage on) / "Blocker off" red — `fix-F22-degradation-home-dark.png`; chip strings moved to resources). |
| 14 | Notification | after Re-enable (`74` + dumpsys) | "Protection was stopped…" HIGH alert (id=1002) persists >2 min after re-enable, stacking with ongoing "Protection active" (1001) — stale alert never cancelled. | High | Cancel/supersede the stopped alert on successful re-enable. | Fixed + device-verified (synthetic APP_STOPPED → 1002 posted → tapped Re-enable action → `dumpsys notification` grep id=1002 → 0 matches; 1001 remains; prefs show `reenable_required=false`). Root cause: `ProtectionStatusMonitor.publish()` change-gate skipped reconciliation; `reenable()` now only clears the flag so publish() runs the notification sync. |
| 15 | Launch | cold start, dark (`66`) | White splash + stock template Android robot icon while system+app are dark. Root cause: `Theme.MyExodus` = `android:Theme.Material.Light.NoActionBar` (`res/values/themes.xml`), no splash config, default template `ic_launcher` mipmap. | Medium | DayNight window theme + real launcher icon + splash theming. | Fixed + device-verified (window background = SandLight/SandDark via `values`/`values-night`; Desert Dawn adaptive icon (`mipmap-anydpi-v26` + gradient background + sun/horizon foreground) renders on the XOS launcher — `fix-A1-launcher-light.png`). Known limitation: legacy `mipmap-*/ic_launcher.webp` rasters (API 24/25 installs only) still show the template robot. |
| 16 | Block overlay | night (`27`) | Block screen renders a light card over a dark app at night. BlockerActivity has no theme override (inherits Material.Light); overlay uses `displayContext` (`OverlayController` `BlockDialog(displayContext)`), theme path unverified. | Medium | Force the dark Compose theme for block surfaces; verify overlay displayContext theming. | Fixed + device-verified, with a deliberate deviation from the literal fix text: both block surfaces follow the **system** night mode (`BlockOverlayView` resolves night at every `bind()` and paints from the shared palette constants; BlockerActivity inherits the DayNight window theme) instead of forcing dark — forcing dark in day mode would contradict the app's light Desert Dawn surfaces. Dark overlay `fix-F16-block-dark.png`, light activity `fix-F16-block-light.png`. |
| 17 | Recovery | override confirm (`28`–`33`) | Ending an urge session early permanently retires the rule (Rule-3 expiry) — irreversible consequence not disclosed at the confirmation point. | Medium | Disclose the consequence at confirm; mechanism unchanged. | Fixed + device-verified (`override-dialog-dark.png` capture: "The block ends for good — it won't restart on its own. To block it again later you'd create a new rule under Rules. This override is logged on this device." + "Keep waiting"/"End the block"; mechanism unchanged, timer-end path independently observed retiring the rule). |
| 18 | Dialogs | delete rule (`25`) | Destructive "Delete" confirm button styled teal — identical to affirmative/save color; destructive action must be visually distinct (error role). | Medium | Error-colored destructive confirm. | Fixed + device-verified (delete confirm action + the editor's Delete both wear `colorScheme.error`; rule editor capture). |
| 19 | Home | populated blocks (`26`) | Block row tap does nothing — row affords tapping (card affordance) but has no route/feedback. | Medium | Row → Rules edit route, or restyle as non-interactive. | Fixed: Home block row now opens **that rule's editor** directly (AppShell carries the rule id → RulesScreen consumes it). Device-verify pending on the next installed build (the first pass routed to the Rules list only — deepened to the exact editor). |
| 20 | Home | block row (`62`,`68`,`70`) | Row subtitle truncates mid-word ("Always on · 2 atte…") on the primary list — information loss in the common case. | Low | Smarter ellipsize (2 lines / shorter template). | Fixed + device-verified (`fix-E1-home-dark.png`: subtitle wraps to two lines in full); the same allowance extended to the Rules row (which truncated "Always on · un…"). |
| 21 | Recovery | active session (`28`) | Big timer numerals use gold (tertiary) — gold reserved for streak/milestone; an urge timer is neither. | Low | Neutral/onBackground numerals. | Fixed + device-verified (`fix-F21-recovery-dark.png` dark: pale on-container ink on the teal card; `fix-F21-recovery-light.png` light: dark-teal numerals on the pale-teal card). |
| 22 | Home | full degradation (`68`) | "Blocked now" teal chip still present while the banner says blocks won't start. Root cause (code): block rows render `ruleRowState` (`ScheduleFormat.kt:42`) = the rule's *schedule* state, with no degradation input — technically not false (the rule would be blocking), but on screen it contradicts the banner. | Low | When degradation is active, block rows should show a degraded-consistent state (or the banner + rows must visually agree). | Fixed + device-verified (`fix-F22-degradation-home-dark.png`: with detection impossible, the block row reads "Not running" while the banner explains why — same frame, no contradiction). |
| 23 | Rules | row subtitle (`fix-E2` pass) | Same information loss as #20 one screen down: the Rules row truncates its schedule line ("Always on · un…") even though the card has room. New finding, found during the fix pass. | Low | Same 2-line allowance as the Home block row. | Fixed (code); device-verify on the next installed build. |
| 24 | Recovery | override dialog | Same class as #18 one dialog over: "End the block" (irreversible) rendered in the affirmative teal. New finding, found during the fix pass. | Low | Error-colored destructive confirm, matching the #18 treatment. | Fixed (code); device-verify on the next installed build. |

**Verified-good patterns (keep + reuse, do not regress):** degraded-state banner = cause + "Fix" button on Home (`66`,`68`) — the treatment finding 4 generalizes; "Protection was stopped… Re-enable" notification copy (`69`); discreet-mode widget toggle verified OFF→ON→OFF on device (`40`,`41`,`61`); widget polling-authority stays "Protected" by design under a11y-off (`63`–`67`; spec-correct, not a bug).

## Verification log (2026-09-19)

Device: X6532 (`DEVICE-SERIAL-REDACTED`), app driven over adb; evidence in
`/Users/musila/Obsidian/MyExodus/ui-audit-2026-09-19/`.

- **Suite:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL; parsed
  results 673 tests / 0 failures / 0 errors across 90 classes (includes the widget
  habit-label round-trip `WidgetSnapshotTest.removedHabit_clearsTheStaleLabel` and
  `ProtectionSnapshotWriterTest.habitLabelChange_isWritten`).
- **Light pass (first):** Home, Rules, Streaks, Recovery, Partner, Settings, block overlay —
  `fix-B1`, `fix-D0..D5`, `fix-F16-block-light`, `fix-F21-recovery-light`.
- **Dark pass:** all six tabs `fix-E1..E5`; block overlay `fix-F16-block-dark`; recovery +
  timer numerals `fix-F21-recovery-dark`.
- **Widget:** dark compact "Protected" neutral ink (launcher capture); resize 2×1→2×2 committed
  on the launcher (`AppWidgetHostView` bounds `[32,136][360,516]`) with the expanded face
  (`fix-F11-widget-2x2-dark.png`).
- **Degradation:** a11y unbound → "Limited" taupe (polling-only, honest); + usage access revoked
  → "Blocker off" red + cause/action banner + rows "Not running" (`fix-F22-degradation-home-dark.png`);
  logcat `MyExodusBlockerFgs: usage=false authority=DEGRADED_NO_DETECTION`.
- **Notifications:** F14 end-to-end (1002 cancelled by the Re-enable action; `dumpsys` clean).
- **Deep link:** F8 foreground re-delivery verified (`fix-F8-deeplink-recovery.png`).
- **Close-out pass (2026-09-19, after the fresh 673-test build was installed on both devices):**
  1. #19 verified on the X6532 — Home block row tapped → landed directly on "Edit block —
     AnkiDroid" under Rules (`fix-F19-home-row-to-editor-dark.png`). #23 verified — the Rules
     row subtitle now wraps to two lines with the full "Always on · until Sep 19, 2026 · 4
     attempts" (`fix-F23-rules-row-2lines-dark.png`). #24 verified through a live flow (rule
     re-armed via Clear+Save → AnkiDroid blocked → "Open MyExodus Recovery" → friction timer →
     override confirm): "End the block" renders in the error role
     (`fix-F24-override-confirm-dark.png`). Light-mode equivalence for #19/#24 rests on
     mode-independence (routing) and the same error token verified in light elsewhere; the one
     light dialog capture attempt was dismissed by the night-toggle activity recreation and not
     retaken — stated, not hidden.
  2. #2/#3 emulator leg done (emulator-5554, data-disposable, new build installed then
     `pm clear`): first-run wizard steps captured (`leg-onboard-step2..5`), Home no-habit state
     with the degradation banner + "Blocker off" chip + habit/blocks empty CTAs
     (`leg-home-nohabit-light.png`), Rules empty ("No blocks yet" + Add app/website CTAs,
     `leg-rules-empty-light.png`), Recovery empty (`leg-recovery-empty-light.png`). Loading
     state is the themed splash first-frame (F15) — local DB reads have no skeleton by design.
     Emulator quirk recorded: two cold-start ANRs while the host build ran (13 s GC pause from
     CPU contention; `cmd package compile -m speed -f` AOT then started cleanly).
  3. F15 cold-start splash captured on the X6532 (`fix-F15-cold-splash.png` — themed window
     background, no white flash; dark mode). Known limitation stands: API 24/25 legacy rasters
     still the template robot.
- **Side effect of testing, recorded:** the friction timer was allowed to run to zero while
  observing Recovery (twice across the session), which retired the test AnkiDroid rule via the
  Rule-3 expiry path — the documented mechanism, not a defect (and the exact behavior finding
  #17 asked to disclose). Final state after close-out: rule reads "Expired" (same state the
  session found it in), consenting to the `blockExpiresAt` date it already carried; a11y +
  usage access were restored to baseline after the degradation test.

## Home dashboard redesign (2026-09-22, ADR #76 — amendment)

The owner asked for Home to take the structure of the supplied reference dashboard, in the same
process as the nav bar (ADR #74): extract → present the pick with honest deviations → sign-off →
build → device-verify → ADR. Two sign-off rounds: the structure ("Strip + teal hero card",
"90-day, 3×30"), then the header anchor ("Date eyebrow + greeting").

**Built:** date eyebrow (`formatHeaderDate`, no year) + the existing tri-state chip on one row,
a time-of-day greeting as the page anchor, a stat trio (best streak / blocks / blocked) with
skeleton loading slots, and a 30×3 = 90-day milestone grid whose rings are `MILESTONE_DAYS`
(7/30/90) and whose fill is the hero's own `habitDays(currentStreakElapsedMs)` value.

**Refused, for lack of data — not deferred as future polish:** the reference's stat deltas
(+8 % / +3 % / +41 %), its 30-day dot grid, and both chart cards. `HabitState` keeps no per-day
history, so every one of those would have been an invented number. Also dropped: the reference's
subtitle, its chips row (would restate the trio 16dp lower) and its always-on CTA (no honest
equivalent on this screen).

**Findings affected:**

- **#13 — the "moved to resources" claim was false in the code.** The Fix column above says the
  chip strings moved to resources; they had not (`HomeScreen.kt` still held Kotlin literals).
  Corrected in this pass (`protection_state_protecting|limited|off`). Plan-vs-code divergence,
  recorded per the plan-fidelity protocol §5.
- **#2/#3 — unchanged in intent, re-expressed.** The numeral slot still reserves height and
  still shows no placeholder glyph; the no-habit path still carries its CTA in the hero. The
  redesign adds two absences rather than new empty states: the milestone grid is not rendered at
  all without a habit, and the stat tiles hold skeletons until their first emission.
  **Superseded in part (2026-09-29, ADR #118):** the owner asked the milestone section back as a
  permanent four-state section (loading/empty/success/error) rather than a data-gated absence —
  the grid now renders always, with explicit copy and actions per state, and the error state
  gained a real signal (`loadFailed`) at the repository and view-model layers.
- **#19/#20/#22 — untouched** (row routing, 2-line subtitles, degradation-consistent row state
  all still live on the same rows, now under a "Your blocks" section label).

**Pending at the time of writing (not done):** the light-theme re-capture and the
MEDIUM/EXPANDED (`wm size`/`wm density`) passes — the test device took an incoming call mid-run.
Dark, at 360dp, is verified loaded and in the pre-first-emission loading state.

## Standing rule — dense pages become subpages (owner, 2026-09-22 — applies to every remaining page and future ones)

*"if u see a page with soo much we can split them into subpages with their own icons … but i dont
want to see an entire page dedicated to one thing like a one toggle … this is just an example
dont limit yourself to this think critically"*

Two edges, both binding: an overloaded page splits into subpages reached by **icon + label**
navigation; a page carrying one trivial control is never created. Recovery was named as the
example, not the scope.

**The constraint that keeps the split honest (from this plan's own research digest):** the
primary action stays on the hub. The persona is mid-urge with low patience and the digest's rules
are "primary action ≤2 taps" and "emergency/escape access never gated" — so a split may move
*reference material* (full lists, plans, history, diagnostics) behind tiles, and must not move
the live action, the live state, or the crisis path. IA changes still need sign-off before
building.

**Audit (2026-09-22, by section count per rendered state):** Recovery idle = 8 blocks (title,
state card, 4 stacked technique cards, coaching plan, action row, crisis card) → overloaded,
confirmed. Partner paired = up to 7 cards at once (identity, chain status, sync, activity, my
requests, approvals, unpair) → overloaded. Home after the ADR #76 redesign = 5 blocks with one
anchor → clear. Rules = single-purpose list → clear. Settings and Streaks were first written here
as "single-purpose lists/toggles → clear"; **both were later revised.** Streaks was a *weight*
problem rather than a block-count one (ADR #79 — a ~13-line card whose own comment concedes the
bars only order the weeks). Settings was the rule's **second edge doing real work**: of its six
sections, two were a single toggle and one was exactly two, so splitting them would have produced
three pages holding one control apiece — the index plus one Protection subpage was the shape that
followed (ADR #82). Rules keeps its flat list, and the measurement below says why it must.

**Applied — Recovery (2026-09-22, ADR #77; signed off: "Recovery now, Partner next", "one
recommended technique" on the hub).** Hub = title/subtitle, live state, one suggested technique
(`RecoveryResourceCatalog.recommended(rating = null, inRiskWindow)` — its first UI caller), the
log-urge action full-width, a three-tile icon+label row (Techniques · My plan · History) and the
crisis card. Subpages: `VIEW_TECHNIQUES` (the full catalog via `ordered(inRiskWindow)`, i.e. the
"choose another" path), `VIEW_PLAN` (the plan items), `VIEW_HISTORY` (already existed). Idle hub
6 blocks vs 8 before; the four stacked cards + plan section collapse to one card + one tile row.
Honest cost: a *non*-suggested technique is now 2 taps (tile → card) instead of 1 — the suggested
one, logging, and crisis are unchanged. JVM suite 1040/0/0/0; **device-verified 2026-09-22** —
hub and all three tiles' targets confirmed on the X6532 (5 in-page targets: "Paced breathing", "Log
an urge", the Techniques/My plan/History 3-up sharing one y, "Need urgent help?"). The gate this
line originally carried was false; see §Device pass.

**Applied — Partner (2026-09-22, ADR #78; signed off: "Lean hub", "nothing extra, Notifications
banner stays on hub").** The audit row said "up to 7 cards", but three code facts narrowed it
before the split was designed: `ChainStatusCard` renders *nothing* when the chain is HEALTHY
(so the healthy hub was never 7 cards); unpaired / wipe / ended states are already a single card
(the rule's second edge forbids splitting those); and `SyncCard` is the AD-05 staleness surface.
Hub (paired) = identity, the pending approval card, `SyncCard` **only when it needs attention**
(`syncNeedsAttention`: never synced, stale, or recently rate-limited), and a three-tile row
(Requests · Activity · Pairing). Subpages: `VIEW_REQUESTS` (pending + mine + empty state),
`VIEW_ACTIVITY` (the alerts list), `VIEW_PAIRING` (PartnerCard + SyncCard + UnpairCard).
Honest costs: "Check now" is one tap deeper (which is why the degraded `SyncCard` is *also* on the
hub — a stale chain must still shout without navigating), and the pair-identity card moved behind
the Pairing tile. Unpair dialog hoisted above the state branch so it survives the state change it
causes. The subpage owns the screen (header + notifications banner are suppressed while one is
open). `BackRow`/`NavTile` extracted to `ui/` (4 and 2 call sites); the two coaching-plan screens
that inline their own back rows were deliberately left alone — recorded as follow-up in ADR #78.
JVM suite 1040/0/0/0; **device-verified** 2026-09-22 (hub 4 in-page targets: "Check now" + Activity/Pairing/Requests; the degraded branch keeping `SyncCard` — the `syncNeedsAttention` route — confirmed live). The earlier "device pass pending, same battery-day-log gate" was **false reasoning**, corrected in §Device pass below.

**Applied — Streaks (2026-09-22, ADR #79; signed off: page pick "Streaks", card's fate "Move
behind a spoke").** The first page where the reference folder did **not** survive contact with
the data model, so the refusals are the substance of the entry:

| Reference structure | Refused because |
|---|---|
| Mon–Sun week day-dots (`13.05.58`) | `HabitState.kt` — *"Day counts derive from `currentStreakElapsedMs`, not from this (Decision 17: no calendar math)"*; no per-day held record exists, so the row could only restate the hero across seven cells |
| Labelled bar chart (`a6d7fc70`) | `withdrawalWeeks` is a static estimate identical for every user; per-bar values are exactly the "measured data" claim `TIMELINE_DISCLAIMER` exists to refuse (Decision 12) |
| 2×2 %-grid, 7-day/Month/Year tabs | No denominators, no per-day history — each an invented number, the class ADR #76 already refused for Home |
| "Claim" CTA | Already ships as `MilestoneSplashCard` ("Nice — keep going"), the REQ-12 guaranteed-delivery path |

The real problem was weight, not structure: `WithdrawalTimelineCard` rendered ~13 content lines
and its own comment concedes the bars only *order* the weeks. Hub keeps hero + milestone ladder
(live countdowns) + relapse action; "What to expect" becomes a `LinkRow` opening a `VIEW_TIMELINE`
spoke that renders the card unchanged, disclaimer included. The row's body pre-frames it —
*"A general estimate of the first four weeks — not a clinical measurement."* — so Decision 12's
honesty reaches the hub, not only the destination. The spoke needs a habit, so it falls back to
the hub rather than stranding a `BackRow` over an empty page. **Honest cost, stated plainly: the
block count does not drop** (5 before, 5 after — the ladder earns its place with live
countdowns); the win is weight and reading order. `LinkRow` is the promoted
`SettingsScreen.SettingsLinkRow` (three consumers now), not a copy — its one delta is
`Surface(onClick=)`, which gives those two Settings rows merged TalkBack semantics and the 48dp
minimum. JVM suite 1040/0/0/0; **device-verified** 2026-09-22 — hub, `VIEW_TIMELINE` spoke (full timeline card, "You are here" on Week 1, taupe bar tracks honouring finding #9, disclaimer present) and the back route all captured on the X6532 at 360dp dark. See §Device pass below for the corrected gate and the `FLAG_SECURE` capture constraint.

**Applied — History summary (2026-09-22, ADR #81; signed off: "Three-way, no switch").** Not a
dense-page split — the owner read the screen and could not tell what it showed: *"what the hell does
w m completed ended early all reference or mean heack what does the history page show? what its
purpose as a user i dont know what it does"*. Three defects in one card: `W`/`M` were initials with
no legend (invented by the reference's income/spending switcher and carried across without the
legend that made them readable there); "Ended early" existed only to fold `Stopped` and
`Interrupted` into one word — a word chosen for the schema's convenience, describing which row
changed rather than what changed for the user; and the worst one, **a bare log counted as a
completed session**, because `logOnly` writes `STATE_COMPLETED` with technique `none`, so the
summary said "3 completed" while the list one row below called the same row *"Logged, no
technique"* — the plan's own state-coherence rule (line 39) broken inside a single screen. The fix
is three buckets that distinguish what changed *for the user* (completed · not finished · logged
only), zeros rendered so periods stay comparable, `STARTED` excluded because a running session has
no outcome yet, and the range spelled out **Week · Month · All time** — the reference's daily and
yearly ends refused on data, since a day of urge logs is 0–3 rows and a year bucket is empty for
nearly every V1 install. The summary scopes only itself: the log below stays the full chronological
record, because hiding a relapse from the record is the wrong affordance in a recovery app.
**Device evidence** (X6532, a11y tree — the surface is privacy-gated and secure, so `screencap`
returns 0 bytes by design): eyebrow "Urges logged" y=240–273, figure 5 y=297–385, all three chips
on one baseline y=425–458, buckets **Completed 2 · Not finished 3 · Logged only 0** y=498–582.

**Applied — Settings (2026-09-23, ADR #82; signed off: "Index + Protection subpage").** The rule's
**second edge** decided this one, not the first. Six sections, but two of them were a single toggle
each and one was exactly two — a subpage per section would have created three pages holding one
control apiece, which is precisely what the owner's ruling forbids ("i dont want to see an entire
page dedicated to one thing like a one toggle"). The overload was a different kind: eight things to
scan, measured at **6 in-page choices · 2.58 bits · 28 text chunks** — the second-highest chunk
count in the app on a screen whose job is to be scanned. So the shape is an **index**: one card,
eight rows (Protection · Enhanced Mode · Strict Mode · Discreet widget · Coaching plan · Risk
windows · Crash reports · About), each row either *is* the control (switch toggles in place) or
*opens* the control (chevron → pane). The six permission/enforcement rows — the only ones carrying a
live status word and an action — move behind **Protection**, whose index row carries the verdict in
words (*"Everything we can check is on"* / pluralised *"N settings need attention"*) backed by the
tested `attentionCount`. Honest cost: the six rows are one tap deeper. Same bargain as ADR #77–#79,
same answer — those rows are warnings and actions, and the index states the verdict in text, so a
user who never opens the subpage still learns the thing that matters. Three threads tied off in the
same pass rather than left dangling: **About now exists** (three documents claimed a
"self-help tool, not medical treatment" footer stood on Settings → About; neither surface nor string
existed), **the unsupported Advanced-Protection case stops hiding the feature** (the old code
early-returned a whole section, so the user could not see Enhanced Mode exists but is unavailable),
and **two more unnamed switches** got their row titles as accessible names (finding #26's class).
Deviations from the reference, stated: one card instead of its bare list (the app has one card
convention), and no back-arrow header (Settings is a tab root — every other root renders a header
without one, and a back affordance would imply a route out that does not exist). JVM suite
1051/0/0/0; **device-verified** 2026-09-23 on the X6532 at 360dp in both themes — index, Protection
subpage, Coaching plan editor (add → overflow with Move up/Move down correctly disabled for a single
item and Delete enabled → delete → confirm), Risk windows editor, About. The two recovery panes both
returned **0 bytes** to `screencap`, which is finding #29's fix confirmed from the route that was
exposed.

**Applied — Rules (2026-09-23, ADR #83; signed off: "All 7 findings + real icons", "Yes, use
BackRow").** The page whose finding had been open longest, and the sweep found the same defect class
one file over, worse: finding #25's six identically-announcing pauses, plus the editor's three
mechanism switches which had **no** accessible name at all. Also: `onClickLabel` on the row,
`BackRow` in the editor (its KDoc promises one shape on every screen; the Rules editor was the only
screen contradicting it), action-labelled add buttons via new strings (the old ones are shared with
the picker, where they must stay nouns), "Dismiss" on the failed-write banner instead of "Cancel", and
`AppMark` — the app's real icon, reusing the technique `AppPicker` already had and deleting its
private duplicate. **The reference pick was `9edc905b`** (transaction list: leading entity mark ·
title + muted subtitle · right-aligned trailing value) — Rules already matched every element but the
mark. Refused with reasons: the date-group headers (Rules groups by target type; a second axis has no
data behind it), the *Show all* pill (nothing overflows), dropped dividers (its rows carry no control),
and the 3-up action row (two target types, not three). Two layout defects surfaced only on the device
screenshot and are fixed: the mechanism hint wrapped flush against its switch (0dp gap), and a long app
label could push *"Change"* off its row. `NamedSwitchTest` now fails the suite on any unnamed toggle
control in `ui/`, with `onClick = null` carved out as presentational. Verified: 1052/0/0/0 across 136
classes (the +1 class is the guard); device — six distinct switch names in the a11y dump where there
had been six identical ones, real Instagram/Bolt icons in the list, globe glyphs for websites,
dividers inset to the text, the `BackRow` editor header, and all three mechanism switches named.

**Not verified, stated rather than implied:** the banner's *"Dismiss"* string — triggering a failed
rule write on the device needs a failure the UI can reach, and it is a literal string with no branch
behind it.

**Applied — Partner (2026-09-23, ADR #84; signed off: "All 5 findings", "Yes — live second line",
reference delegated).** The second pass over the page ADR #78 had just split, judged against the
degraded hub the earlier pass could not reach. Five findings, all five confirmed live on the X6532
before any code changed:

1. **The hub named nothing.** `partner_paired_with` and the pairing date lived behind the Pairing
   tile, so the screen showed a partner's data without ever saying whose. `PartnerIdentityCard` now
   heads the hub.
2. **The identity mark is the chain verdict, not a portrait.** The pairing model holds no name and no
   photo, so a monogram or avatar would be invented identity; the disc changes *shape* with the
   state (`VerifiedUser` / `Sync` / `History` / `GppBad`) and carries the words as
   `contentDescription`, so the verdict never rests on colour alone.
3. **Eight cards each rebuilt the same `Surface`.** Collapsed into one `PartnerSurface` + a
   `CardTone` enum — tone is data now, not a colour literal repeated at each call site.
4. **The alert rows were text with no entity.** `AlertRow` gained a leading `AppMark` (the real app
   icon for `AppActivity`, `SelfImprovement` for Recovery), reusing the same technique Rules used.
5. **Four tiles were links that made you tap to find out they held nothing.** `NavTile` gained an
   optional `status` line; all six call sites keep the default except Partner's three.

The healthy `SyncCard` **stays quiet** — that is ADR #78's rule and this pass does not touch it; what
this pass added is what the *degraded* card needed (a `NOTICE` tone, and `Check now` demoted to a
text button so it stops competing with the tiles' targets).

**Reference pick: `13.14.48`** — the only file in the folder built around **another party** (named
recipient, state row, per-party actions) rather than around self-tracked data, which is exactly the
Partner page's shape. Refused with reasons: `204def2b`'s category grid (the model has no categories),
`13.14.07`'s day strip and search (no per-day partner history; nothing to search), and
`13.14.48`'s own day strip, View More, per-row `⋮` menu, and gradient hero — each would have
invented data or a control with no action behind it. `ADR #78`'s unpaired / wipe / ended states are
untouched: the standing rule's second edge still forbids splitting those.

**Device-caught defect, fixed:** the Requests tile read `Nothing waiting`, which ellipsized to
"Nothing waiti…" in the tile's ~88dp text box at 360dp. Now `None waiting`, with the width constraint
recorded next to the string.

Verified: JVM **1052/0/0/0 across 136 classes** (unchanged — the sweep added no test). Device (X6532,
360dp, dark then light): the hub's identity row in the a11y tree (`"Shared activity is verified"`,
`"Paired with dcee1e380ead…"`, `"Since Sep 19, 2026"`), the `NOTICE`-tone sync card with `Check now`
as a text button, and tiles reading `None waiting` / `0 this week` / `Synced 4d ago`. Light theme
checked on the hub (taupe notice card, no purple leak), then dark restored.

**Not verified, stated rather than implied:** the **healthy** hub dropping that card. Producing it
needs a fresh partner sync, which needs a second device; on this device the chain is 4 days stale, so
that half is verified by reading `syncNeedsAttention` and not by observation. Likewise the identity
disc against `CATCHING_UP` / `INCOMPLETE` / `UNVERIFIED` — each needs a partner to drive the state.

**Applied — Streaks (2026-09-23, ADR #85; signed off: "Fold it into the hero", "Composable chrome
only")** — task #26, the first *sweep* on a page that had already been split (ADR #79). Both
findings were re-measured live on the X6532 before any code changed, and the first one was not the
defect its label described:

1. **Finding #28 was drift, not a forgotten cap.** Home's `StreakHeroCard` and Streaks'
   `StreakSummaryCard` render the same habit label, and *Home's copy had the cap*. On Streaks the
   live 95-character habit name wrapped to four lines — `[72,274]–[608,467]`, **193px** against 41px
   for one line. Fixed at the mechanism, not the symptom: one shared
   `HabitLabelRow(text, onSwitch)` capped in both branches, called by both cards.
2. **Finding #27 could not be closed by the cap alone.** Capping the label lands *"Log a relapse"*
   at ~1420 against the 1320 viewport edge, because `MilestoneLadderCard`'s first row restated the
   hero's caption (*"Next milestone: Day 7 — 4 days to go."* above *"Day 7 · 4 days away"* — the
   same fact, one card apart). `MilestoneLadderCard` is **deleted**; its three rungs now render as a strip inside the
   hero — day, reached / days-away, and the next rung in gold — carrying every fact the ladder
   carried. The page now fits one screen: label **33px**, hero ends ~676, *"Log a relapse"* at
   `[266,1210]–[455,1250]`, fully above the fold.

**The new test is the deliverable for #28.** `HabitLabelGuardTest` (2 tests) fails the suite if any
surface renders the label under its own `Text(` — the nearest enclosing call open must be
`HabitLabelRow(` — or if the shared row loses either cap. The defect is invisible until a habit name
is long enough to wrap, which no reviewer types by hand, so it is enforced mechanically.

**Reference pick: `13.02.04`** — one composition taken from it, its **week strip**: a horizontal row
of labelled units with exactly one marked current, which is the shape the rung strip needed. Refused
with reasons: its greeting/avatar header (no user identity in the model beyond `BrandMark`), its
2-up stat tiles (that is Home's `StatTrio`), its *"See All"* (a bare word where ours must name the
destination), and its day-strip *data* (per-day history this app does not keep — Decision 17 forbids
calendar math). Also refused: `13.01.43`'s day strip for the same data reason, and `e8c8e77c`'s chip
action row (our one action is the relapse card, and it belongs last).

**Copy converted to resources, scoped by owner call:** all composable chrome moved to `strings.xml`
(`streaks_*`). `"What to expect"` had existed as **three independent literals across two files** and
is now one resource; `WithdrawalTimelineCard` also dropped its internal title, which repeated the
spoke's `BackRow` header three lines below it. `StreaksPresentation.kt` (pure, unit-tested, plural
branching) and `StreaksViewModel.kt` (error mapping at its own layer) stay Kotlin deliberately —
that boundary is the decision.

Verified: JVM **1054/0/0/0 across 137 classes** (+2, the guard test). Device (X6532, 360dp, dark then
light, `install -r` only): the label on one line, rungs reading `Day 7 / 4 days away` (gold),
`Day 30 / 27 days away`, `Day 90 / 87 days away`, the relapse button fully visible; the picker
sheet, the "Add another habit" setup card with all five duration and three severity options, and the
timeline spoke all rendering from resources.

**Not verified, stated rather than implied:** the spoke's fallback route (habit removed while the
spoke is open must return the hub rather than a `BackRow` over an empty page) is still owed — the
pass rendered the spoke from resources but did not remove the live habit to drive it. The relapse
dialog was opened and **cancelled**; confirming it resets the live streak, so the mutation was not
performed on the device.

Finding #23's device-verify note and finding #19's Home→rule-editor route were both reachable
during the Rules pass and are confirmed: the row subtitle holds its two lines and Rules stays a
**flat list** — the standing rule's second edge cuts the other way here.

**Task #27 (Recovery subpages) was opened and stopped the same day on token budget, before any code
changed** — no file modified, no ADR. Four findings are recorded in the Obsidian progress note:
`SessionHistoryScreen.kt:98` is the last bare `TextButton("Back")` in the app (ADR #83's class; the
two coaching-plan screens it recorded as follow-up now use `BackRow`); the urge log's 1–10 scale is a
`LazyRow` whose ten chips ≈472dp wide against ~312dp of content, so the top of the scale likely
scrolls off with no affordance — **unconfirmed, dump the sheet before touching it**; two literal
`"• "` bullets remain (`TechniqueRunner.kt:380`, `RecoveryScreen.kt:602`); and the crisis
empty-state copy was checked and is fine. Also found: the **whole Recovery tab** is `FLAG_SECURE`
(`RecoveryScreen.kt:197`), so `screencap` returns 0 bytes across the hub and every spoke — this
surface's device pass is a11y-tree-only, permanently, by design.

**Applied — Block walls + warning banner (2026-09-27, ADR #86; no reference pick — the findings
came off the device, and the surface is not a page).** The sweep covered the four
`BlockOverlayView` states (url block, app cover, daily-limit wall, warning banner) and
`BlockerActivity`. Five defects, each re-proved on the X6532 before any edit, three of them only
visible with temporary `BuildConfig.DEBUG` log lines in place:

1. **The banner had no anchor.** It rendered as a bare line of text that re-parented the wall's
   title — a mid-urge surface whose whole job is to be noticed read as a formatting glitch.
   Now an inset surface card: 20dp radius, 1dp `outlineVariant` stroke, its own 16sp `TextView`,
   a leading timer glyph, teal at L1 / error tone at L2, over a transparent 96dp strip.
2. **The overlay never took the ADR #75 type step.** `applyTypeScale()` read the *display
   context's* `Configuration`, which is stale after a display change; `resources.displayMetrics`
   is live. Verified at 800dp MEDIUM: banner 574→640px = ×1.125 exactly, log factor=1.125.
3. **The banner was killed by its own window churn ~5ms after attaching**, and again at ~270ms
   by the URL-scan Allow path. `OverlayContent.Warning` now carries the package it was shown
   for, and both Allow branches (`decideApp`, URL-scan) refuse to hide the wall or banner they
   would otherwise clear. Log proof: shown 13:40:10.908, Allow at +11.066s and +11.729s, no hide
   until the auto-dismiss at +5.0s.
4. **The wall's title stayed hidden after any banner** — the rebind's visibility toggle was
   one-way `GONE`. All per-part toggles are now two-way; pinned by
   `BlockOverlayViewTest#warning_usesTheInsetCard_notTheWallColumn`.
5. **The walls named nothing visually.** Both the a11y cover and `BlockerActivity` now anchor
   the restricted app's **real launcher icon** (`getApplicationIcon`; fallback `ic_block` /
   `ic_globe` on `onPrimaryContainer`) on an 88dp `primaryContainer` disc — disc 96→108px at
   MEDIUM, activity icon face 58→66px.

Device evidence (X6532, dark + light + 800dp): four ~1.06s wall eviction cycles captured
mid-flight, cover → `BlockerActivity` handover rendering the identical card, both themes. JVM
suite **1055/0/0/0 across 137 classes**. The device's display overrides, night mode and usage
seeds were restored in the same command chain that changed them; one test-created `example.com`
rule was deleted through the Rules UI. EXPANDED (≥840dp) remains unverified — task #10.

**Applied — The widget (2026-09-27, ADR #87; reference pick: the gallery's `11.54.56` — a status
band card whose every band carries information, which is exactly the structure the stretched cell
was missing).** Six defects, the owner report first:

1. **"No streak yet" flashed over a good streak at every process start** (owner report). `state`
   is null until the first durable read; the publish triggered by that transient null treated it
   as "no habit" and blanked a known count. `streakFieldsFor` now distinguishes the two nulls — a
   *resolved* repository is an answer (including "no habit"); an unresolved one is not a reading,
   so the stored streak is carried. Pinned by
   `ProtectionSnapshotWriterTest#unresolvedState_carriesTheStoredStreak` (+ `resolvedWithoutAHabit_…
   clearsTheStreak`, `unresolvedFirstRun_publishesNothing`). Live on the X6532:
   `carried=true days=7` across a process start.
2. **Every process start opened the durable store** (owner-flagged — XOS's freezer makes process
   starts frequent). The record now carries its own proof: `StreakSample` (accumulator + verified
   clocks), and `needsStreakRefresh` proves the cached day count current from that pair alone —
   the accumulator advances 1:1 with `elapsedRealtime` (Decision 17), so only a day boundary or a
   reboot can invalidate it; fail-closed on every unreadable input. A young sample owes no read.
   And when the proof fails, the number renders with its age ("Checked 2h ago") — the spec §16 Q5
   answer, visible rather than silent.
3. **The resized card rendered a hero over a void** (owner-flagged). Root cause: `TALL_SIZE` was
   declared at the size the card is *drawn*; launchers match on the nominal AOSP grid, so a
   visual-size preset is never matched and the card stretched the 110dp one. Declared at the
   reported 312×174 it is matched exactly, and the tall composition is bands: hero row, the
   milestone band (label + "Day 30 in 23d" + the library's `LinearProgressIndicator`, gold on
   `surfaceVariant`), hairline, data strip.
4. **The 2×2 cell re-voided at the bottom**: the action was bottom-pinned and launchers draw this
   cell taller than the preset they match (~187dp drawn / ~193dp reported, AOSP), so the slack
   became a void. The stack centers; the slack lands as margin (light + dark verified).
5. **Copy misstatements**: "Day 30.23d" (owner: *"confusing, replace the ."*) → "Day 30 in 23d" /
   "Day 30 in 23 days"; "Open ›" promised the main page (owner: *"someone may assume it opens the
   main page"*) → "Tools ›" discreet / "Manage urge ›" detailed, both landing on Recovery.
6. **The number had no destination**: the day count is now a tap target into the Streaks tab (the
   same `TAB_STREAKS` extra as the milestone notifications), with the milestone line added.

**The containment model, measured on both launchers:** the host picks the largest declared preset
that *fits inside the cell*. AOSP 2×1 → COMPACT, 2×2 → NARROW; XOS 1-row → COMPACT (~312×89),
2-row → TALL (312×174 pinned). **EXPANDED (260×110) composes clean and is unreachable on both
test launchers** — recorded as an honest gap, kept as the correct 4×2 shape for a host that
reports it.

Device evidence — X6532 dark: TALL "Day 7" + "NEXT MILESTONE · Day 30 in 23d" + gold band at
7/30 + "Tools ›"; 1-row COMPACT sentence form. Emulator (AOSP launcher), light + dark: NARROW
discreet ("No streak yet" + "Tools ›") and non-discreet ("Not protected" chip + "Manage urge ›").
Emulator prefs restored (`discreet_mode=true`) and night mode back to light in the same chains.

**Applied — Recovery's spokes (2026-09-27, ADR #88; reference picks: the gallery's `11.56.04`
task rows → History, `12.01.19` numbered stepper → SoberWalkthrough).** Seven defects, and the
first thing the mandated pre-touch dump did was correct the plan's own candidate: the urge log's
scale was not "the top scrolls off" — as a `LazyRow` it cut chips **7–10**, the bottom of the
scale, leaving 1–6 to read as the whole thing. The rest:

1. **History's last bare `TextButton("Back")`** (the finding task #27 opened with) → `BackRow`,
   matching the rest of the app.
2. **History rows had no anatomy**: now the reference's task-row shape — accent bar (teal only
   for a finished run or a bare log), title, **trailing day stamp**, one details line.
3. **The stamp is a pure function** (`historyTimestampLabel`, clock/zone/words injected):
   "Today · 3:42 PM" / "Yesterday · 3:42 PM" while current, "Sep 25" / "Dec 30, 2025" after;
   zone = the device's at read time. `HistoryStampTest`, 7 cases, midnight and New Year pinned.
4. **The scale is a `FlowRow`** — all ten chips on two rows, second tap clears, each chip named
   "Intensity N of 10".
5. **Two literal `"• "` bullets** → one `BulletLine` (glyph column + text), shared by the
   runner and the plan spoke.
6. **SoberWalkthrough was a text list, not a walkthrough** → the reference's rail: numbered
   circles (Done = check, Current = number, Upcoming = outline), connectors filling teal behind
   the user, body under the current phase only, eyebrow "Phase N of M".
7. **Both sheets opened at M3's halfway anchor**, hiding their own buttons under the fold (the
   dump caught it on the urge sheet; the habit picker has the same shape with unreachable rows
   at enough habits) → `skipPartiallyExpanded = true` + a scrolling column on both.

Device evidence (emulator at a forced 360×640dp and at its native size; X6532): the sheet whole
on both devices with chips 7/10 confirmed on the phone; a bare log, a completed walkthrough
(intensity skipped) and a stopped one written through the UI, then History reading **3 / 1 / 1 /
1** with "Today · …" stamps; `BulletLine` rendered live from a plan item; JVM **1082/0/0/0**.
Not covered, stated: the picker's fix is code-verified only (deferred to the next Home/Streaks
pass); the rail's circle fills are decorative-silent to a11y and FLAG_SECURE blocks captures, so
their evidence is structural. ~~**New finding, deferred:** the coaching-plan editor's three add
buttons + delete button have no accessible name (`content-desc=""`, same class as #25/#26) —
recorded for the editors sweep (task #3).~~ **CORRECTED 2026-09-27 (task #3, ADR #89): false
positive — the buttons have carried names since 2026-09-22; the item quoted the raw dump's parent
clickable node while the named child was in the same XML. See the ADR #88 correction.**

**Applied — Settings' spokes (2026-09-27, ADR #89; no reference pick — this pass was invoked as
the defect sweep of what the index's rows open, not a reference-driven redesign).** Pre-touch dump
first, per the ADR #88 lesson, and it retired the pass's own headline item: the editors' "unnamed"
buttons were a raw-dump misread (named children present, same XML; code unchanged since Sep 22).
Two genuine defects, one class — **disabled with no stated reason**:

1. **Enhanced Mode's switch dies silently for 24h** under Strict Mode's cooldown (the common
   path: every user who enables Strict Mode meets it). Fix: the same countdown line the Strict
   row has, with its own ticker kept in each row's composable scope — the tick is what lets
   `enabled = …canDisable()` free the switch at expiry, so a shared ticking child would have
   broken it.
2. **Strict Mode's switch greys out silently under Advanced Protection** (the Enhanced row swaps
   in the reason; the Strict row didn't). Fix: new `enhanced_mode_strict_unsupported` + the same
   subtitle swap.

Device evidence (X6532, physical): staged 23h cooldown → both toggleable nodes `enabled=false`,
Strict `checked=true`, new line reading "Strict Mode active — in-app controls unlock in 22h 59m";
cooldown moved to the past → both `enabled=true`, warnings gone, Strict still checked; restore =
file deleted (pre-state: absent), Strict `checked=false`, Enhanced untouched at `checked=true`,
blocking still "Limited". Also verified clean this pass (no change): Protection subpage (six rows,
named "Open — {row}" actions), the index's ToggleRow switches, Coaching plan editor (three named
adds + named row actions), Risk windows editor (named add/toggle/overflow over a real overnight
window), About (named Back, version, disclaimer). JVM **1082/0/0/0**.

**Cross-cutting guard tests — applied (2026-09-27, ADR #90; no reference pick — the deliverable
was mechanical).** Task #29's three rules: two guards already shipped and were verified
(`NoLiteralTypeSizeTest` + `ScaledTypographyTest`, ADR #75; `SecureSurfacesTest`, ADR #80), so the
work was the subpage rule's mechanical half — *a pane you can enter offers the same way out*.
Enumeration against `BackRow(` found two stragglers: `RulesScreen.EditorUnavailable` (bare
`TextButton` + the string `editor_back`, now the shared `BackRow`, string deleted) and
`ComingSoonScreen` — which turned out to be **unreachable** (`AppShell` is only composed with the
store open; all six tabs enumerated; both call sites dead). Deleted it, made `AppShell`'s store
non-null, dropped the `when` fallback, and clamped a restored tab index. New guard:
`PaneBackAffordanceTest` (files declaring `onBack:` must render `BackRow(`; no `ArrowBack` /
`ui_back` / `"Back"` literal outside `BackRow.kt`). Device (X6532, physical): the rare
`EditorUnavailable` pane reached deterministically by killing the process with `run-as … kill -9`
while the editor was open (NOT force-stop — that wiped the a11y grant, re-cycled and restored in
the same chain) — the pane renders the shared BackRow (named arrow + "Edit block"), self-heals
into the editor when the Flow emits, and its back arrow lands on the list; shell tab switching
intact. JVM **1084/0/0/0**.

**Applied — Shell + onboarding (2026-09-27, ADR #91; no reference pick — the deliverable is
mechanical, same standing as #29).** Task #6, in two halves plus a guard:

1. **The shell stops speaking in literals, and the guard found the class alive tree-wide.** ADR
   #85's "Streaks was the last surface whose copy lived as Kotlin literals" is falsified: the new
   `LiteralCopyGuardTest` (4 patterns: `Text(`, notification setters, `*Copy(`, `NotificationChannel`
   name-arg) caught **six** files the per-page audits had each passed — `MainActivity`'s
   `DatabaseHealthScreen`, `HomeScreen`, `ProtectionNotifications`, `DnsSinkholeVpnService`,
   `StreakNotifications`, `PartnerNotifications`. 42 strings extracted; channel **ids** stay
   literals (protocol, not copy — the first guard run's `"vpn_spike"` hit was the false positive
   that forced the pattern onto the constructor's second arg). `DomainUsageDebugSection.kt` was
   exempt by filename while it existed: debug-gated, never shipped; both the file and the
   exemption were removed 2026-11-05.
2. **Display copy and wire value separate.** Tab labels were literals *and* the deep-link protocol
   at once; `ui.NavKeys` now owns the six values, `MainActivity.TAB_*` delegates, `NavTab` carries
   `labelRes`, and the rendered labels are unchanged ("Home"/"Rules"/… — same title strings).
3. **The wizard's backs agree.** `SetupScreen` gets `BackHandler(enabled = index > 0) { index-- }`;
   at step 0 the platform default stands. The suspected `checks == null` wrong-action window is
   refused with reasons (unreachable: the gate needs a fresh install where "not granted" is true).
   `SetupHarnessActivity` (debug-only) exists because the once-per-install gate makes the wizard
   otherwise unverifiable on the never-uninstalled phone.

Device (X6532, physical, a11y tree): the harness walked 1→5 — statuses "Granted", no Back at step
0, no Skip when granted, no Skip on disclosure; **system BACK from step 2 → step 1 with the harness
resumed** (`topResumedActivity` = the harness; the new handler), BACK at step 0 finished it
(platform default), on-screen Back → step 1, Done finished it; `dumpsys accessibility` still shows
the blocker **Bound** after the install. Shell: six tab labels from resources; `--es
extra_start_tab Recovery` still switches tabs (protocol preserved). JVM **1085/0/0/0** (the guard
is the +1). Dumps removed, app left on Home.

**Applied — Bottom nav (2026-09-27, ADR #92; signed off: "Icons only → 28dp").** Task #11.
Pre-change measurement (X6532, dark, fontScale 1.0): pitch 105.5px = 52.7dp, widest label
"Recovery" (semibold 11sp) 48.5dp → six labels clear the 4dp guard by 4.2dp. The label half of
the ask is measured shut — 12sp needs ≈52.9dp against the 52.7dp pitch (the guard would flip to
selected-only), and buying width back means dropping the semibold-active weight or shaving the
slab margin, both declined. A bigger bubble is refused by ADR #74's own arithmetic (radius 18→21dp
→ end inset 17.4dp → pitch 51.5dp → labels drop). One constant changed: `IconSize` 24→28dp (a 4dp
ring in the 36dp bubble — Material's own proportion). ADR #74's text-vs-code drift also gets its
overdue revision markers (slab 78dp, margins 8dp, labels 11sp against its 70/12/10). Device:
Settings (rightmost) and Home (leftmost) captured by screencap, cropped and reviewed — even icon
pitch, the notch clear of both corner arcs, all six labels rendered. JVM **1085/0/0/0**.

**Next page:** chosen from `/Users/musila/Documents/ReferenceInspirationUI` against this same
process (extract via `component-reverse-engineer` → present the pick + honest deviations →
sign-off → build → device-verify → ADR). **Nav, Home, Recovery hub + spokes, History, Settings
index, Rules, Partner, Streaks, the block/warning surfaces, the widget, the Settings
spokes, the guard tests, the shell + onboarding, and the bottom nav are done.** The pairing-plan
re-audit (#12) and both bug legs (#13 daily-limit undercount, #14 milestone notification) are
closed. **Review + adaptive passes (#9/#10) applied 2026-09-28 — ADR #110**: token scan clean in
both themes (only real brand icons flagged), a11y scan clean on all six tabs (two uiautomator
clipped-row artifacts ruled out), adaptive type now device-verified on all three window classes
(EXPANDED ×1.25 within 1px rounding + the ≥840dp rail switch), JVM **1295/0/0/0**. Remaining per
the task list: the V1 feature review (#16) — the close-out pass (#8) and the commit (#15,
user-authorized) **landed 2026-09-28: `a248af2`, 340 files, `.tmp-auth-*` credential stores
gitignored** — plus the Partner test-residue cleanup flagged in #110, queued with #66's
production unpair verification.

*Status revision 2026-10-17 (docs audit): both items that paragraph lists as remaining are closed — the V1 feature review (#16) ran 2026-09-28 (20 shipped / 10 partial / 1 by design / 5 not built; progress note), and the Partner test residue was removed through the production unpair UI as #66's device verification (six residue pairs, roster 7→1; ADR #114).*

## Device pass (2026-09-22 — the gate was wrong, and screencap had a reason)

**The battery day-log gate was false.** Recorded three times above as "device pass pending — an
`install -r` tears down the VPN/enforcement services being measured". It does not survive contact
with the device: (a) the sampler is a detached device-side shell loop at
`/data/local/tmp/battery-day-log.sh`, not a child of the app, so package replacement leaves it
running; (b) the thing it measures was already down — every log row reads `vpn=STOPPED` and the FGS
was `isForeground=false`; (c) wireless-vs-USB was never the variable — `install -r` replaces the
package and kills the process, and there is no `--dont-kill`. Installed over wireless adb in 9.4s.
**Grants survived**, contradicting `scripts/dns-sinkhole-device-test.sh:34`'s XOS warning.

**Screencap returning 0 bytes was the feature, not a fault.** `logcat` gave it away:
`SurfaceFlinger: FB is protected: PERMISSION_DENIED`. `RecoveryScreen.kt:195` sets `SecureSurface()`
(W6/INV-11) and `MainActivity` mirrors it onto the window as `FLAG_SECURE` — the Recovery tab is a
privacy surface by design, so no capture can read the framebuffer. Proven both directions:
`dumpsys window` flags MainActivity's window SECURE while Recovery is up, and a capture on Home the
next moment returns 115,639 bytes. **Recovery is the only such screen** (`SecureSurface()` has one
call site). Consequence for this audit: a Recovery screenshot is not obtainable and never was —
Recovery is verified structurally instead, via the `FLAG_SECURE` state (which *is* W6/INV-11 on
device) plus `uiautomator dump`, which reads the a11y tree rather than the framebuffer.

**Verified (X6532, 360dp COMPACT, dark):** Streaks hub · Streaks `VIEW_TIMELINE` spoke (full card,
"You are here" on Week 1, four bars on **taupe** tracks — finding #9's fix holds, no purple,
disclaimer present) · the back route · **Recovery hub** (5 in-page targets: "Paced breathing", "Log
an urge", the Techniques/My plan/History 3-up sharing one y, "Need urgent help?") · **Partner hub**
(4 in-page: "Check now" + Activity/Pairing/Requests — the degraded branch that keeps `SyncCard`).

## Cognitive-load measurement (2026-09-22 — owner request: "check the cogntive overoad too")

Measured, not eyeballed. `scripts/ui-cognitive-load-probe.sh` deep-links each tab, dumps the
device's accessibility tree (`uiautomator dump`) and counts **interactive targets** (choices) and
**text blocks** (information chunks) per screen. It reads the a11y tree rather than the
framebuffer, so it works on Recovery, which `FLAG_SECURE` makes uncapturable. Run on the X6532 at
360dp COMPACT, dark. Nav items are excluded (the current tab is not clickable — 5, not 6).

| Screen | In-page choices | Hick bits | Chunks | Read |
|---|---|---|---|---|
| Streaks | 2 (+1 below fold) | 1.00 | 22 | Lowest decision load in the app |
| Home | 3 | 1.58 | 23 | Healthy |
| Partner | 4 | 2.00 | 15 | Healthy |
| Recovery | 5 | 2.32 | 21 | Healthy |
| Settings | 6 | 2.58 | 28 | Acceptable |
| **Rules** | **14** | **3.81** | 25 | **Overloaded — 2.3× the next worst** |

**Re-measured 2026-09-23, after the Settings redesign (ADR #82).** Same probe, same device, same
exclusions (the 5 non-current nav items are subtracted; the probe prints them in the raw count):

| Settings, at rest | Before | After |
|---|---|---|
| In-page choices (viewport) | 6 | **6** |
| Hick bits | 2.58 | **2.58** |
| Leaf text chunks | 28 | **19** (−32 %) |

**The choice count did not move, and that is the honest result rather than a disappointing one.**
At rest the viewport holds six targets either way — before, they were the six permission rows'
"Open"/"Fix"/"Recheck" buttons; after, they are six of the index's eight rows, two of which
(Protection, and the switches that toggle in place) *do* something on the spot instead of routing
to a button that routes. What changed is the **density**: 9 fewer text chunks on the same viewport,
because the permission rows' explanatory prose left the index and the verdict collapsed into one
line ("1 setting needs attention"). The scrolled half measures 5 in-page choices / 17 chunks.

Two limits worth naming rather than smoothing over: (a) the probe counts the **viewport only**, so
the index's true choice count is 8 rows + 2 add-actions, not 6 — a whole-page sampler is a known
improvement to the script, recorded and not yet made; (b) a same-count result means this split
cannot claim a Hick's-law win, only a load-density one. The claim is written as what was measured.

**What this validates:** the hub-and-spoke splits worked in the dimension they were aimed at.
Every hub now sits at 2–5 choices (1.0–2.3 bits), inside Miller's 7±2 and well under Hick's
knee. Before the splits, Recovery and Partner each carried their full page in one scroll.

**What it exposes — three findings the structural reads had not caught:**

| # | Screen | Issue (measured) | Severity |
|---|---|---|---|
| 25 | Rules | **The rule list's "14 choices" is data-driven, not design bloat** — 2 add buttons + 6 rows (each row is the edit target) + 6 pause switches = 14, i.e. 2 targets per user-created rule. The count rises with the user's own data, which is not a defect. The **real** defect is narrower and now precisely located: `RulesScreen.kt:473-475` sets `Modifier.semantics { contentDescription = if (enabled) pauseLabel else resumeLabel }` on each switch, so all six announce the identical "Pause this block" — the custom description *replaces* rather than supplements, and the rule's own label never reaches the announcement. A screen-reader user hears the same six words with no way to tell which rule they act on (WCAG 2.4.6 / 2.4.4). Sighted layout is fine: the switch sits beside its row's label. **One-line fix per row** — fold `label` into the description — not a page redesign. | Medium — **FIXED 2026-09-23 (ADR #83, the Rules sweep):** `rules_pause`/`rules_resume` became `%1$s`-parameterised and each row passes its label, so the six switches announce *"Resume Chrome"*, *"Pause Instagram"*, *"Pause a.192.0.2.100.nip.io"* …; `NamedSwitchTest` fails the suite if any switch ships unnamed. This cell was simply never updated after the fix shipped. **Independently re-verified 2026-09-28 on the X6532** (fresh a11y dump, task #8 close-out): six distinct subject-bearing descriptions — each on the checkable node's child, while the parent node itself reads `content-desc=""`, the same dump shape as the corrected #26. |
| 26 | Settings | Same class, independently: five of six targets read "Open"/"Fix"/"Recheck" (y=318/475/664/897/1088/1236). These *are* distinct `TextButton`s, but each label is generic and the row title is a sibling node, so the accessible name carries no subject. Fix is the same family as #25: let the button announce its row's subject. | Medium — **FIXED 2026-09-23 (ADR #82):** the Settings index redesign retired the generic buttons entirely (each row now names itself), and the same defect was found and fixed on two switches that had it too (`Discreet widget`, `Crash reports`). The Rules half is finding #25. |

**Correction (same day, on reading the source):** #25 was first written up as "six targets labeled identically — *Pause this block*" and read as though Rules rendered six redundant buttons. It does not; the probe surfaced the switches' `contentDescription` and the finding was overstated from the measurement alone. Severity revised High → Medium. The count is still the highest in the app, but it is the user's rule count showing through, and Rules stays a **flat list that must not be split** — the standing rule's second edge cuts the other way here.
| 27 | Streaks | The page's **primary recovery action sits below the fold** and the card is visibly amputated — at rest the user sees a card titled "Had a slip?" clipped by the viewport edge (title at y=1289–1320, nav inset at ~1356), which reads as broken content rather than "scroll for more". Confirmed causal link to finding 28 below. | Medium |

| 29 | Coaching plan · Risk windows · Session history | **INV-11 / W6 not implemented on two of the three surfaces the code says it covers.** `SecureSurface()` has exactly **one** call site (`RecoveryScreen.kt:197`), but its own KDoc asserts three: *"the recovery tab, the session history, and the coaching editor each [SecureSurface] themselves."* Verified on the X6532: **Coaching plan** — no SECURE window, capture succeeded (91,265 bytes); **Risk windows** — no SECURE window, capture succeeded (70,358 bytes). Both open as panes of the **Settings** tab, so nothing else registers and they are screenshot- and screen-recording-exposed. **Session history** is protected only *incidentally* — it renders inside the Recovery tab's composition so `RecoveryScreen`'s count stays >0 (capture 0 bytes on device, biometric gate fired) — meaning protection silently disappears if that spoke is ever reached from elsewhere. The comment is why this survived review: a reader sees coverage asserted and stops looking. | **High** (privacy invariant; content is recovery/mental-health data) — **FIXED 2026-09-22 (ADR #80):** each of the three now declares its own `SecureSurface()`; both Settings panes return **0 bytes** to `screencap` on device. The guard test that would catch a fourth surface forgetting is tracked separately, not claimed. **2026-09-27: that guard now exists** — `SecureSurfacesTest`'s source scan + exemption-liveness assert, delivered with the cross-cutting guard set (ADR #90). |

**Finding 28 (the root cause of 27) — `StreakSummaryCard` habit label has no line cap.**
`StreakCards.kt` ~line 61 renders `"Days free from ${state.targetLabel}"` with no `maxLines`/
`overflow`. The live habit is labelled `qqqq…` (49 chars) and wraps to **four lines**, inflating
the teal hero from ~180px to ~435px — which is what pushes the relapse card under the nav. Found
on device during this pass; untouched by the ADR #79 change (pre-existing). Capping the label to
one line with ellipsis recovers ~3 text lines (~145px); that brings most of the card into view but
is **not** claimed to fully solve #27 — the honest fix for the action's placement is a separate
call. `HomeScreen` renders the same label (`Days free from qqqq…`, y=643) and inherits the same
risk.

Corrections this forced: the "Rules is clean" verdict above was a **layout** read and stands as
one; it was not a load read, and on load Rules is the worst screen in the app by 2.3×. The two are
not in conflict — the layout is well-formed *and* the page carries the most decision weight — but
"clean" unqualified was wrong.

## Deliverable

Per-screen before/after screenshots + a summary entry in `Obsidian/MyExodus/MyExodus Progress.md`; any resulting ADR-worthy decision recorded in `app/docs/MyExodus Architecture Decisions.md`.

## 2026-09-29 — Reference-driven visual pass redo (#102, ADR #124)

Owner correction the same day the #97/#99 tasks were closed: those shipped the **declutter**
(ADR #121) but not the reference-driven visual port, so #102 re-ran the pass against
`/Users/musila/Documents/ReferenceInspirationUI` only (no web research). Landed: the shared
`StatusPill`/`PillTone` atom (`ui/StatusPill.kt`) adopted by partner request rows, Recovery
technique effort and the withdrawal timeline marker; `StatColumn` + `HeroStatRow` replacing
`StatBlock` on the partner summary and Activity cards, with the days unit as a plural riding the
hero number; `ComposeMessageCard` mark + full-width CTA; the Recovery crisis card folded onto the
shared `LinkRow` (it was a copy without the chevron); request-row dividers. **Owner ruling on the
device (live):** the Requests subpage keeps no `PartnerContextBar` — "requests doesnt need viewing
section ie viewing mama switch" — Activity/Pairing keep theirs (narrows ADR #121). Evidence and
honest limits in ADR #124; V1 doc has the matching section.

## 2026-10-21 — Partner plans become a real shared surface (owner redo of the coaching-plan ask)

Owner verdict on the shipped coaching plan: "incredibly wrong." Root cause of the mismatch: the
shipped surface was the reference's local recovery editor, and the owner's actual ask was a
sponsor-authored checklist that reaches the other phone. Both now exist, kept separate on purpose
(ADR #193).

Decisions landed this pass:
- **Partner tab → Plans** (a `LinkRow` under the 2×2 tile grid, not a fifth tile — the grid is a
  2×2 by construction). Full-screen pane with three groups: "From your partner", "You shared",
  "Just for me". Rows name the counterpart; incoming unchecked counts surface on the hub row, and
  the row never claims "no plans" before a successful empty read (store refusal → no subtitle).
- **Editor**: create a plan for a chosen partner (by name) or "Just me — stays on this phone";
  title + checklist lines; ≤ 40 lines, ≤ 80-char title, ≤ 200 chars per line, enforced at input;
  blank lines dropped, blank title refused; a failed save keeps the draft; revision bumped on
  edit with task ids preserved so the recipient's checks survive a reword.
- **Recipient copy is checkable; the author's copy of a shared plan is display-only.** Checking is
  the progress signal, and it is also the consent act. A self plan is checkable by its owner (this
  was a bug in the first cut — the guard refused every authored plan).
- **Notifications**: creation and every edit alert the recipient (`onlyAlertOnce=false`); progress
  is silent by design.
- **Navigation**: editor → detail → list → hub, each with its own back handler; the hub stays the
  single entry point and the tab's scroll stays clean.
- **Settings loses the duplicate "Coaching plan" row** (it opened the same Recovery editor through
  a second entry point; that pane was itself a Finding #29 privacy surface). One entry point:
  Recovery → Coaching plan.
- **Research prompt for the next feature** (partner accountability for permission revocation,
  primary-vs-consented analytics, per-rule partner assignment, block invitations, redaction,
  uninstall alerts, partner analytics dashboards) stored verbatim in
  `Obsidian/MyExodus/Research Prompts.md` with the "plan may miss" checklist; the owner will drop
  the resulting plan into `app/plans/` and execution starts from it.

Verification so far: JVM 22 tests green (`PlanWireCodecTest` 6, `PartnerPlanRepositoryTest` 7,
`PartnerPlanReceiverTest` 9); v16↔v17 migration legs added to the instrumented suite. Device pass
pending (Task #8).
