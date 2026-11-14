# Whole-project code review and cleanup — 2026-11-13

Owner queue item #151: whole-project code review plus cleanup (unused code, principles from
`engineering-patterns-reference.md`, file organization). This is the ledger for that pass. A
verdict below is written only for what was actually checked in this pass; carried verdicts and
scan limits are labelled.

## Method

Three layers, in this order:

1. **Mechanical scans over the working tree** (single pass per file, results counted):
   dead top-level declarations, dead private declarations, TODO/FIXME markers, empty catch
   blocks, `GlobalScope`, main-thread blocking sleeps, non-null assertions (`!!`), deprecated
   API usage, debug-only tooling left in `main`, Room index declarations, and file/folder
   organization.
2. **Targeted reads** of every file a scan flagged, to separate real findings from scan false
   positives. Falsely flagged references resolved against `AndroidManifest.xml` (reflection-
   registered receivers) and Hilt classpath scanning were excluded only after reading the
   reference site.
3. **Carried verdicts** from the prior review campaigns, reused only where the file is unchanged
   since that campaign (see "Carried verdicts").

## Honest limits

- The 2026-10-15 → 2026-10-17 campaign read all 329 then-present main Kotlin files line by line
  (ADR #170). The 2026-10-20 project review and 2026-10-22 deep review extended coverage. This
  pass did **not** re-read all 516 main files line by line; it relied on those campaigns for
  unchanged files plus the scans and targeted reads above for everything since.
- Test-source dead-declaration scanning is not meaningful: JUnit discovers tests by reflection,
  so 275 of 376 flagged test declarations were false positives. The scan is recorded here as
  run-and-dismissed, not as a clean verdict on test code.
- The brace-counting function-length heuristic is unreliable for expression-bodied Kotlin
  functions (one run counted a 3-line function as 1,629 lines). It produced no finding and is
  not used as a gate.

## Findings

### F1 — dead `emitActivity` removed (fixed)

`accountability/sync/PartnerSyncCoordinator.kt` still carried `private suspend fun
emitActivity(...)` (12 lines including KDoc) from the original event path. Commit `f93f3e0`
replaced its last caller with the staged, signed transport; the function itself was left behind.
Removed. Compile and unit gates below verify the removal.

### F2 — unused palette constants removed (fixed)

`ui/theme/Color.kt` declared `SuccessLight` and `SuccessDark`, never referenced by any Kotlin,
XML, or doc since the initial commit. The success semantics ship through `TealLight`/`TealDark`
and the streak gold. Removed; `OnPrimaryLight`, `PrimaryContainerLight`, `OnPrimaryContainerLight`
and `SurfaceVariantLight` stay named on purpose — the block overlay (a plain View outside
Composition) paints from the same constants, and their comments say so.

### F3 — Material card boilerplate between Compose screens (deferred)

Several screens repeat similar `Card`/`CardDefaults` chrome. This is below the DRY threshold
(3+ substantive occurrences of one block), the copies differ in padding/color per screen, and a
shared wrapper would add an abstraction the screens would immediately need to escape. Deferred
per the house rule against pre-abstraction; revisit only if a fourth screen copies the same block
verbatim.

### F5 — URL wall never drew for a pathless blocked host (fixed 2026-11-14)

Device-caught during the #202 wall leg. Chrome's accessible `url_bar` text appends its
security state on an insecure page (`example.com. Connection is not secure`). With a path
(`example.com/?v=3...`) the suffix lands in the path and canonicalizes cleanly — the
2026-11-01 production test saw only that shape. Without a path the suffix landed in the
authority, so the candidate host became `example.com. connection is not secure`, failed
`hostMatches`, and the scanner's Allow path left Chrome's raw NXDOMAIN page as the whole
surface: no wall, no recovery entry. DNS enforcement was unaffected. Fix: `UrlPattern`
truncates the authority at the first whitespace (an authority never contains one). Regression
test `UrlPatternTest.browserSecuritySuffix_stillCanonicalizesToTheHost`.

### F4 — empty catch in `PartnerEventRecorder` (deliberate, no action)

`PartnerEventRecorder.kt:164` swallows non-cancellation exceptions around the delivery kick. The
preceding comment states the invariant: the row is already durable and a failed kick only delays
it to the periodic pass. Cancellation re-propagates first. This is intentional recovery
behaviour, not a swallowed error. No change.

## Scan results and what they mean

| Check | Result | Verdict |
|---|---|---|
| Dead top-level declarations | 1,530 declarations scanned, 15 candidates, 3 confirmed dead | F1 + F2 removed; rest were manifest/Hilt/test-reflection references |
| TODO/FIXME markers in main | 0 | Clean |
| Empty catch blocks in main | 1 | F4, deliberate |
| `GlobalScope` usage | 0 | Clean |
| Blocking sleeps on main thread | 0 | `DnsSinkholeVpnService` sleeps run on the dedicated `vpn-sweep`/drain threads |
| Non-null assertions (`!!`) | 13 | All guarded by an immediately preceding null/branch check; no unsafe dereference found |
| Deprecated API usage in main | 0 | Clean |
| Debug tooling in `main` | 0 | Test-only tooling removed in `dc64b3a` |
| Room indices | 13 of 34 tables indexed; remainder single-row or tiny (schema `31.json`) | Not a finding — query patterns match |
| Repository hygiene | Only `gradle-wrapper.jar` as a binary; no stray build output tracked | Clean |
| File organization | Conventional homes for screens, services, DAOs, stores, widgets, theme | Clean |
| Hardcoded user-facing copy | None found in main UI paths; literals are log tags, keys, protocol values | Clean |

### File sizes (justified, not silently accepted)

53 of 516 main Kotlin files exceed 400 lines. The largest are Compose screen files carrying a
screen plus its private composables (`PartnerScreen.kt` 3,096; `AccountPane.kt` 1,792;
`RulesScreen.kt` 1,754; `MainActivity.kt` 1,651; `HomeScreen.kt` 1,592) and large coordination
classes (`AppRulesStoreFactory.kt` 2,235; `AccountabilityModule.kt` 1,348;
`PartnerSyncCoordinator.kt` 1,247). The screen-per-file convention was examined in the 2026-10-20
review and is kept: splitting a Compose screen into more files buys little and risks navigation/
state regressions, while the file's responsibility is still singular (one screen). The large
coordination classes were verdicted file-by-file in the 2026-10-15→17 campaign. This pass found
no file that had drifted into two unrelated jobs.

## Carried verdicts (unchanged since their campaign)

- 2026-10-15 → 2026-10-17: 329/329 main files individually verdicted at `9c9ce58`, findings
  #12–#66 fixed (ADR #170).
- 2026-10-20 project review: one-responsibility, KISS, reuse, folder organization.
- 2026-10-22 deep review: `deep-review-2026-10-22.md`; closeout 2026-11-05.
- 2026-11-05 security review: `security-review-2026-11-05.md` (pairing-token enumeration fix and
  the rest).
- 1.1.27 change set: task #197 review closed (vault progress note, 2026-11-12).
- Change sets after 1.1.27 — v30/v31 partner sharing (`6eaa621`), owner privacy (`fbec049`),
  onboarding (`ac8ff44`), widget fourth shortcut (`cf35680`) — were authored and verified in
  their own sessions with tests and device passes, and are covered in this pass by the scans
  above plus targeted reads of the files they touched. They were not re-read line by line here.

## Gates

- `./gradlew :app:testDebugUnitTest` — **297 test classes / 2,839 tests / 0 failures / 0 errors
  / 0 skipped, exit 0** (2026-11-13).
- `./gradlew :app:lintDebug` (run alone, ADR #208) — **0 errors, 0 warnings**, exit 0,
  2026-11-14, lint 9.2.1. The three known baseline warnings from the 2026-11-13 run were fixed
  in source: `ModifierParameter` (`BatchEditor.kt`, modifier moved to the first optional
  parameter), `PluralsCandidate` (`partner_permission_lost_more` converted to a `<plurals>`
  resource, consumers switched to `pluralStringResource` / `getQuantityString`), `UseKtx`
  (`AttemptRecorder.kt`, `SharedPreferences.edit { }`). Fixes are behavior-neutral; the
  published 1.1.29 artifact predates them and they ship in the next release.

## Closeout

Both cleanup edits are covered by the gates above (the unit suite compiles the changed sources).
No blocked item is claimed as verified; every remaining open concern is listed under Honest
limits or deferred as F3.
