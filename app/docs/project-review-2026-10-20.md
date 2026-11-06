# Project-wide code review — 2026-10-20

Owner mandate (2026-10-20): review the project for one-file-one-responsibility, KISS without
re-litigating the plan, code reuse (a shared piece lives in one place and is called from
everywhere), and file/folder organization; permission was given to create files/folders. The
review ran alongside the blocking-engine defect investigation of the same day. Nothing here is
claimed as fresh evidence that was not actually read in this pass — carried verdicts are labeled
as carried.

## Method and honest limits

- **Campaign of record:** the 2026-10-15 → 2026-10-17 exhaustive review closed at commit
  `9c9ce58` (ADR #170): 329/329 main Kotlin files individually verdicted, findings #12–#66 fixed
  with regressions. That verdict is reused only where a file is unchanged since `9c9ce58`.
- **Freshly read end-to-end in this pass:**
  - every main Kotlin file changed since `9c9ce58` — 35 files, all read line by line (none was
    assumed clean because it had passed the earlier campaign);
  - every worktree-changed file — 16 files (overlapping the 35);
  - the complete `blocking/`, `domainusage/` and `vpn/` packages — the enforcement surface the
    owner asked to stress, including files untouched since the campaign.
- **Structurally scanned, verdict carried:** the largest unchanged files (`PartnerScreen`,
  `MainActivity`, `HomeScreen`, `AppRulesStoreFactory`, `PairingCeremonyScreen`,
  `AccountabilityModule`, `RecoveryScreen`). Their outlines and public surface were inspected in
  this pass; line-by-line freshness is the campaign's, not this pass's.

## Verdict per area

| Area | Files | This pass | Result |
|---|---|---|---|
| `blocking/` | 49 | Fresh, full package | Clean after the fixes below |
| `blocking/usage/` | all | Fresh | Clean after the fixes below |
| `domainusage/` | all | Fresh, full package | Clean |
| `vpn/` | 9 | Fresh, full package | Clean |
| `accountability/` changed files | 2 | Fresh | Clean |
| `streaks/` changed files | 2 | Fresh | Clean |
| `ui/rules`, `ui/settings`, `ui/streaks` changed files | 5 | Fresh | Clean; one deliberate non-split (below) |
| `widget/` changed files | 3 | Fresh | Clean |
| `data/db/dao/AttemptLogDao.kt` | 1 | Fresh | Clean |
| Everything else | 294 | Campaign verdict carried | Unchanged since `9c9ce58` |

No new SRP violations, no new real duplication (3+ occurrences), no misplaced files were found in
the freshly read set. The two reuse defects that existed are fixed below.

## Findings fixed in this pass

### 1. Carryover identity collision — CRITICAL (business logic / quota correctness)

Repeated re-enables in one boot credited the platform delta once and then silently credited 0 ms:
zero-length carryover intervals produced identical `(source, package, start, end, boot)` ids, so
the second top-up deduplicated as a retry. This is the mechanism of the owner's reported ~1 h 29 m
Instagram undercount. Fixed by giving carryovers an identity that names the recorded state —
`carryover:<package>:<wallClock>:<targetTotal>` — and pinning it with
`reconcile_repeatedTopUps_sameBoot_eachDeltaLands`. Full record: ADR #190.

### 2. Duplicated `isLimitTracked` — HIGH (drift risk on an enforcement predicate)

`BlockerAccessibilityService` and `EnforcementForegroundService` each carried a private copy of
the "tracked package" predicate (positive limit, day scope covered). Two accounting paths agreeing
on which packages may accrue is a correctness contract, and copies can drift silently. Moved to
`RulesSnapshot.isLimitTracked(packageName)` — one predicate read off the cached snapshot, no DB
I/O on either hot path — and both services now call it. Added
`RulesSnapshotTest.isLimitTracked_requiresARuleWithAPositiveLimit_onACoveredDay` (positive limit /
zero limit / no rule / off-day). The removed bodies and the new function are textually the same
predicate; verified in the worktree diff.

### 3. Duplicated runtime-receiver registration — MEDIUM (four copies, security-shaped)

The API-33 `RECEIVER_NOT_EXPORTED` / legacy `RECEIVER_EXPORTED` block existed four times
(`USER_UNLOCKED`, dialer change, two screen-off registrations). Every copy touches the
exported-receiver security boundary; four copies are four places to get it wrong. Extracted
`Context.registerSystemReceiver(receiver, filter)` in `ReceiverRegistration.kt` and routed all
four sites through it. The helper's KDoc states the 26–32 flag-shape rationale once.

### 4. `PlatformUsageReader` midnight seam — LOW (test/debug seam honesty)

`readForegroundMs` took a `nowMillis` but derived the midnight anchor from `LocalDate.now(zone)`.
Production behavior was unchanged (the default argument *is* now), but any caller passing an
explicit clock got a window whose start belonged to a different day. The anchor now derives from
the asked instant, matching the documented contract.

## Considered and rejected

- **Merging the two DNS codecs** (`vpn/DnsMessages` and `domainusage/DnsMessage`) — rejected. The
  first is the baseline sinkhole's minimal negative-answer builder; the second is the metering
  engine's total parser plus the shaping replies (SOA negative TTL, AAAA/SVCB/HTTPS NODATA). They
  answer different questions and have different failure contracts; merging would put the total
  parser's cap/completeness guarantees on the baseline path and add a shared-surface hazard for no
  caller.
- **Splitting `RuleEditor`'s pure validation helpers into their own file** — rejected for now.
  The ~165 lines at the file's foot (`validateBlockMode`, `composeRuleFields`, `siteLimitTarget`,
  `siteLimitPatternsOf`, `clampStoredLimitMs`, `parseLimitTotalMinutes`, `targetKey`,
  `isValidWebsiteTarget`) are used only by this screen's save chain plus its three test classes
  and the debug test receiver. They are the editor's save policy, already pure and fully tested.
  Moving them changes five import sites with no behavior change; revisit if a second screen ever
  needs them. Recorded here so the decision is not silently rediscovered.
- **Splitting `PartnerScreen`'s ~660-line stateful shell** — rejected for now. The file is one
  screen with ~25 private composables already carrying the cards; it is unchanged since the
  campaign that read it line by line. Re-open when that screen is next touched.

## File-size inventory (evidence)

335 main Kotlin files, 51,751 lines total (average ~154). The claim that prompted the review was
"files with like 14,000 lines" — no such file exists in this project. The largest:

| File | Lines | Why it is one file |
|---|---:|---|
| `ui/partner/PartnerScreen.kt` | 2,064 | One screen; 1 public composable + ~25 private composables. The shell function is the largest single composable (~660 lines) — noted above, unchanged since campaign. |
| `MainActivity.kt` | 1,251 | App shell: navigation host, lifecycle/intent wiring, dialogs. Campaign-read. |
| `blocking/BlockerAccessibilityService.kt` | 1,240 | Path A enforcement service; fresh-read this pass. |
| `ui/home/HomeScreen.kt` | 1,147 | One screen composed of private composables. Campaign-read. |
| `blocking/EnforcementForegroundService.kt` | 1,097 | Path B enforcement service; fresh-read this pass. |
| `ui/rules/RuleEditor.kt` | 1,029 | One editor screen + its tested save policy (non-split recorded above). |
| `data/db/AppRulesStoreFactory.kt` | 1,027 | Single store factory: open/recovery state, DAO providers, KEK handling. |
| `ui/partner/PairingCeremonyScreen.kt` | 1,009 | One ceremony state machine + its composables. |
| `vpn/DnsSinkholeVpnService.kt` | 989 | The VPN service; fresh-read this pass. |
| `di/AccountabilityModule.kt` | 899 | Hilt module — wiring only, inherently a long list. |

The files over the 300–400-line guideline are all either (a) one enforcement service with a single
responsibility, (b) one screen with extracted private composables, or (c) pure DI wiring. No file
was found doing two unrelated jobs.

## Organization

- New this pass: `ReceiverRegistration.kt` (root package, beside `MyExodusApp` and
  `ReceiverRegistration`'s callers — app-level runtime glue). `RulesSnapshot.isLimitTracked` lives
  in the class it reads. No new folders were warranted.
- `blocking/usage/` (accounting + ledger + diagnostics), `domainusage/` (metering engine), and
  `vpn/` (packet path) retain clean feature boundaries; the review found nothing scattered.

## Verification

- `:app:testDebugUnitTest --tests "com.example.myexodus.blocking.*" --tests
  "com.example.myexodus.domainusage.*" --tests "com.example.myexodus.vpn.*"` — **82 test classes,
  840 tests, 0 failures, 0 errors, 0 skipped** (exit 0, 2026-10-20 21:13 local; Gradle XML
  reports). This task depends on `compileDebugKotlin`, so the compile is included in the same
  evidence. The carryover regression (`reconcile_repeatedTopUps_sameBoot_eachDeltaLands`), the
  shared-predicate test (`isLimitTracked_requiresARuleWithAPositiveLimit_onACoveredDay`), and the
  ledger mapper pins are in that set.
- The reuse changes are behavior-preserving by diff inspection (same predicate text, same receiver
  flags), and were compiled and tested rather than assumed.
- Device confirmation of the carryover fix through the real toggle seam is part of the 2026-10-20
  scenario campaign evidence, not this document.
