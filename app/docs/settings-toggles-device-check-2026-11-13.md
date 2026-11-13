# Settings toggles — physical device check (2026-11-13)

Owner challenge: "have u physically tested the toggles instead of looking at the source code"
(the earlier #205 answer was source + unit tests only). This run answers it with device evidence.

**Device:** MyExodus_API37 AVD (the sanctioned emulator exception; NOT the X6532), release
v31 / 1.1.29, cold boot, physically completed onboarding. Display override 720x1600.

## Enhanced Mode (master switch)

Baseline: `enhanced_mode_settings.xml` did not exist (default **on**), `EnforcementForegroundService`
present in `dumpsys activity services`, accessibility bound, Chrome block rule active and
BlockerActivity proving enforcement.

| Step | Observed |
|---|---|
| Tap toggle at (612,824) | switch `checked=false` |
| Prefs | `enhanced_mode_settings.xml` created, `enabled=false` |
| Service | `EnforcementForegroundService` **gone** from `dumpsys activity services` (a11y remains) |
| Launch Chrome | `topResumedActivity=com.android.chrome/.Main` — **no BlockerActivity; Chrome opens freely** |
| Tap toggle back on | switch `checked=true`, pref `enabled=true`, service back |
| Launch Chrome | `topResumedActivity=.blocking.BlockerActivity`, screen: "Chrome is blocked / Blocked by your MyExodus rules" |

Conclusion: switching Enhanced Mode off disables blocking *entirely* — the owner's read is
correct. The toggle is the plan's sanctioned in-app escape hatch
(`myexodus-enhanced-mode.implementation-plan.md` §1/§6: impulsive in-app disabling is
discouraged only through Strict Mode friction; OS-level disable is always possible and never
intercepted).

## Strict Mode (24h cooldown)

Tapping Strict ON wrote `strict_mode.xml`:

```
<boolean name="enabled" value="true" />
<long name="cooldown_until_epoch_millis" value="1794655200673" />
```

Device clock 1794568811951 → remaining **23 h 59 m 48 s** (exactly 24 h at the tap).

| Observed | Result |
|---|---|
| Enhanced switch | `checked=true` `enabled=false` (locked) |
| Strict switch | `checked=true` `enabled=false` (locked) |
| Copy under both rows | "Strict Mode active. In-app controls unlock in 23h 59m" |
| Tap the disabled Enhanced switch | no state change — input genuinely rejected |
| Discreet widget switch | still `enabled=true` (only the two protection switches lock) |

Source: `app/src/main/java/com/example/myexodus/settings/StrictModeManager.kt` —
`canDisable() = !PARTNER_APPROVAL_ENABLED && !cooldownActive`; the countdown is persisted and
fails open on corrupt/out-of-range records (`cooldownRemainingMillis`). Boundary (plan
Decision 6): Android Settings can always disable the accessibility service — Strict Mode is
in-app friction, not an OS lock; it says so in its own doc comment.

## Block check-ins

Absent from Settings with no partner pair — matches the source gate
(`SettingsScreen.kt` `partnerBlock?.let`). Purpose per source (SOS §8.19):
recipient-side mute of the named partner's "Reach out" (check-in request) nudges;
`settings_partner_block_body` = "Don't let reach-outs from %1$s reach this phone."
It does not touch streak check-ins. Only rendered when an active pair exists.

## Restore (same session)

- `strict_mode.xml` rewritten to `enabled=false` / cooldown `0` (root), app restarted →
  both switches unlocked, Strict copy back to its idle subtitle.
- `am force-stop` side effects found and recovered: it disables the accessibility service
  (re-enabled via `settings put secure enabled_accessibility_services`), and the app raised
  its designed "Protection is paused — something on your phone closed MyExodus" guard;
  tapping **Re-enable** resumed protection (`reenable_required=false`, last-known-good
  re-stamped).
- Chrome test rule deleted via the rule editor (list back to "No app blocks yet"); Chrome
  confirmed unblocked.
- Verified no Block check-ins row without a partner after restart.

## Findings worth keeping

1. Enhanced OFF → enforcement service stops and blocking is dead; Chrome opens. Proven both
   directions. Any product decision to make Enhanced always-on removes the in-app off path —
   and leaves Strict Mode gating only itself (the two toggles are coupled through the shared
   `enabled = !advancedProtection && strictMode.canDisable()`), so Strict's meaning must be
   re-decided in the same breath.
2. Cosmetic: flipping Enhanced off shortens the Protection subtitle ("Everything we can check
   is on" collapses to a one-liner), shifting every row below it up ~40 px; flipping back
   restores it. Observed on device; not a defect.
3. Emulator-only evidence. The X6532 was not used for this leg.
