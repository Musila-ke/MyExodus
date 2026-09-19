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

## Deliverable

Per-screen before/after screenshots + a summary entry in `Obsidian/MyExodus/MyExodus Progress.md`; any resulting ADR-worthy decision recorded in `app/docs/MyExodus Architecture Decisions.md`.
