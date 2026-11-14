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

## Partner-visibility gap when Enhanced Mode is off (source audit, 2026-11-14)

Found while answering the owner's question "can the user just switch Enhanced off and relapse while
the partner pipeline reports healthy?" **Yes — the in-app switch is a silent kill switch with
respect to partner visibility.** The partner pipeline watches OS-level grants, not this flag:

1. Both capability probes derive ACCESSIBILITY from the OS binding,
   `BlockerAccessibilityService.connected.get()`, not from `enhancedModeEnabled`:
   `AccountabilityModule.kt:1036` (E2EE monitoring snapshots) and
   `PartnerDeliveryModule.kt:196` (signed device-contact reports). Turning Enhanced off leaves the
   a11y service bound, so both keep reporting ACCESSIBILITY as present.
2. `MonitoringSnapshotRecorder.record()` is invoked only from FGS call sites
   (`EnforcementForegroundService.kt:262, 764, 774, 792`). `EnforcementForegroundService.stop()` is
   a plain `stopService` with no final snapshot, so toggling Enhanced off stops the reporting
   instead of reporting the stop. The partner's last snapshot stays ACTIVE.
3. `PartnerHeartbeatClient` beats carry only `{installId, counter}` (`PartnerHeartbeatClient.kt:59-65`)
   and continue via the 15-minute WorkManager fallback (`PartnerSyncCoordinator.checkIn`). The
   device never goes silent, so "no news" is indistinguishable from "healthy".
4. `PartnerPermissionLossReporter` only raises a gap when a capability is actually lost and a rule
   assigned to that partner needs it. No capability is lost, so no alert.
5. Server side, `device-liveness-logic.js` `missingCapabilities()` never sees a missing
   capability, `enforcementState` never transitions to DEGRADED, and `NOTICE_ENFORCEMENT_DEGRADED`
   is never sent.

Net: with Enhanced off, blocking is dead (Finding 1) while the partner sees a healthy,
checking-in device. Only the user's own phone shows the Protection paused banner.

**What the pipeline does cover (unchanged):** OS-level grant loss — accessibility revocation,
usage-access revocation, overlay permission, VPN consent. That path was physically proven
end-to-end (#142 revoke → partner notified → restore; #145). **Strict Mode** is unaffected: its
24-hour cooldown is real in-app friction, but it is not partner-visible either.
**Block check-ins** is unrelated to blocking (recipient-side nudge mute, SOS §8.19) and its
server-side suppression is covered by functions-emulator tests only (`partner-sos.emulator.js`,
`partner-sos-push.emulator.js`), not a handset leg.

**Closure options (owner ruling pending, OPD #44):** (a) remove the Enhanced switch and make it
always-on; (b) keep the switch but wire its off-state into the same partner alert path — include
`enhancedModeEnabled` in both capability probes and force a final UNAVAILABLE snapshot at
toggle-off; (c) status quo, documented as an accepted in-app kill switch.

## Outcome (owner ruling 2026-11-14, ADR #308)

Option (a), in its strongest form: the Enhanced Mode master switch is **deleted**, not defaulted.
`EnhancedModeSettings`, the accessibility service's per-event gate, the FGS start/poll/process-start
gates, the boot gate and the maintenance gate are gone; blocking is always on and authority moves
only with the platform grants. Strict Mode is deleted with it — it had nothing left to lock. The
AAPM explanation from the deleted screen moved to the Protection pane's accessibility row. Block
check-ins were not part of this ruling and keep their Settings row. The sections above remain the
record of the pre-removal behavior and its v31 evidence.
