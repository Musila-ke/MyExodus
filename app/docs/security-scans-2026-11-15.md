# Security scans and final gates — 2026-11-15 (v40 / 1.1.38)

Final-wrap ledger for the #240/#232 partner-delivery change set (v37–v40). Every tool below ran
against this working tree on 2026-11-15/16, after the #247–#250 cleanup and code review. Commands,
versions, and outputs are recorded so the next closeout can re-run them. Tool versions: Opengrep
1.33.1, Gitleaks 8.30.1, Trivy 0.76.2, SonarQube Community Build 26.8.0.126808 (SonarScanner CLI
8.4.0.16527, local `localhost:9000`, database reset fresh for this run — no carryover from the
2026-11-14 analysis).

## Opengrep (SAST)

- **Project rules** (`scripts/myexodus-opengrep-rules.yml`, 7 Kotlin rules) — scanning 1,224
  git-tracked files, 579 Kotlin files scanned, 30 partially parsed (Kotlin parser limitation, not
  findings), 7 directories skipped by `.semgrepignore`: **0 findings**. Log:
  `/private/tmp/opengrep-custom-20261115.log`.
- The community-registry cross-check from 2026-11-14 (22 advisory-only findings: GCM nonce
  construction and the VPN's own plaintext resolver hop) is not re-run here; the code at every
  site those findings named is unchanged (compare `git diff` for `vpn/`, `crypto/`, `backup/`).

## Gitleaks (secrets)

- `gitleaks git . --redact -v` — **223 commits / 20.72 MB scanned: 0 leaks**
  (`/private/tmp/gitleaks-git-20261115.log`).
- `gitleaks dir . --redact -v` — **19.44 MB worktree scanned: 0 leaks**
  (`/private/tmp/gitleaks-dir-20261115.log`). `.cache/` remains allowlisted as regenerable tool
  state (`.gitleaks.toml`).

## Trivy (dependencies)

- **App** — `trivy fs --scanners vuln --severity HIGH,CRITICAL app/gradle.lockfile` (ghcr DB
  mirror env vars required on this machine): **45 findings (41 HIGH, 4 CRITICAL)**, byte-for-byte
  the accepted set from 2026-11-05/14. 44 are configuration-scoped `androidLintTool`/build-tooling
  entries; the one application-scoped finding is guava CVE-2026-102554, accepted under task #216
  (no reachable vulnerable path: GWT-RPC deserialization is not used by the app).
  Log: `/private/tmp/trivy-app-20261115.log`.
- **Functions** — `trivy fs --scanners vuln functions/`: **0 vulnerabilities**
  (`/private/tmp/trivy-functions-20261115.log`).

### npm audit — Functions dependency tree

- **Full tree**: 11 vulnerabilities (6 moderate, 5 high, 0 critical).
  - The 5 HIGH are devDependency-only (`firebase` CLI → `@firebase/firestore` →
    `@grpc/grpc-js` ≤1.13.5, plus `@firebase/rules-unit-testing`). They are absent from the
    deployed set (`npm audit --omit=dev` reports 0 high). The only offered fix is
    `npm audit fix --force`, which **downgrades `@firebase/rules-unit-testing` 3.x → 2.0.7** —
    rejected: it would break the emulator suites to silence advisories that never ship.
  - The 6 moderate are production-transitive: `uuid@9.0.1` under `gaxios@6.7.1`
    (GHSA-w5hq-g745-h8pq, buffer-bounds in v3/v5/v6 with a caller-provided buf),
    `minimatch`/`glob`/`rimraf`/`google-gax@5.0.8` under `@google-cloud/firestore-api`, all
    upstream of `firebase-admin@14.4.0`. No non-breaking fix exists (`npm audit fix` reports the
    tree up to date); remediation requires an upstream firebase-admin release that moves gaxios to
    7.x / google-gax to 6.x. None of the vulnerable APIs sit on the Functions request path: uuid
    v4 is used for multipart boundaries (never the buf overload), and glob/minimatch patterns are
    never attacker-controlled. Tracked as an accepted residual, re-checked each gate.
- `npm audit fix` was applied earlier in the wrap (3 patch-level lockfile bumps); the redeployed
  Functions carry the updated `functions/package-lock.json`.

## SonarQube (quality) — fresh database, final tree

Ce task `b8c67393-db46-447f-94e3-c98026debd7b` — **SUCCESS**, analysis
`3c991a19-1c06-498a-8bbc-fe527bf64bc3`, 40.6 s. One CE warning: `sonar.java.test.binaries` /
`sonar.java.test.libraries` are not supplied (see Honest limits).

- **327 open issues** (effort 5,354 min): 221 CRITICAL, 98 MAJOR, 7 MINOR, 1 INFO.
  324 code smells, 3 "vulnerabilities", 0 bugs. **ncloc 73,200.**
- Ratings: **reliability A (1.0)**, **sqale A (1.0)**, security hotspots **0**;
  security rating C (3.0) solely due to the 3 text:S6389 test-fixture findings below.

Composition and dispositions (all 327 reviewed — nothing waived unseen):

| Rule | Count | Disposition |
|---|---|---|
| kotlin:S3776 (cognitive complexity) | 135 | Accepted baseline. Pre-existing large Compose/coordinator functions; refactor tracked, not blocking. |
| kotlin:S1192 (duplicate string literals) | 86 | Accepted baseline. Mostly test assertions and log/error keys. |
| kotlin:S107 (too many parameters) | 62 | Accepted baseline. Mostly Compose private composables and DI factories. |
| kotlin:S6619 (case-insensitive comparison of string literal) | 11 | Reviewed; no case-insensitivity bug (literal comparisons are exact-match by design). |
| kotlin:S6517 (nested functions should be extension/member) | 9 | Style; reviewed, no behavior change. |
| kotlin:S6511 (function parameter order) | 7 | Style. |
| kotlin:S1313 (hardcoded IP) ×4 | 4 | **False positive** — `vpn/DnsSinkholeVpnService.kt:112,117,118,119` sinkhole address/prefix constants, same disposition as 2026-11-14. |
| text:S6389 (bidirectional chars) ×3 | 3 | **False positive** — `EnrollmentPayloadTest.kt:75`, `PartnerLabelTest.kt:16,23`: RTL/override characters inside test payloads that exist to prove the label sanitizer strips them. These are the only "vulnerabilities" and the sole reason for security rating C. |
| kotlin:S1871 (duplicated branch bodies) | 2 | Reviewed (`AttestationVerifier.kt:179`, `PartnerScreen.kt:2680`); branches are intentionally identical pending distinct copy. |
| kotlin:S1125 (unnecessary boolean literal) | 2 | Style (`UrgeAssistScreen.kt:520,526`). |
| kotlin:S4144 (identical function implementations) | 1 | `AppLockCoordinator.kt:156` `onScreenOn`/`onScreenOff` are semantically different triggers with identical bookkeeping; kept explicit. |
| kotlin:S6514 (interface delegation) | 1 | Style (`BackupPayload.kt:304`). |
| kotlin:S6516 (explicit functional interface → lambda) | 1 | Style (`BlockerAccessibilityService.kt:164`). |
| kotlin:S1481 (unused local) | 1 | `PartnerDashboardSource.kt:139` — reviewed, the binding documents a decode step; removal is safe, kept for now. |
| kotlin:S1172 (unused parameter) | 1 | `SettingsScreen.kt:218` — kept for the call-site signature contract. |
| kotlin:S1135 (TODO) | 1 INFO | `PartnerScreen.kt:3142` — tracked backlog note, not a defect. |

## Compiler warnings and test gates

- **JVM unit + Robolectric suite** (`:app:testDebugUnitTest`, run alone): **BUILD SUCCESSFUL**
  in 12 m 50 s — 295 test classes, **2,848 tests, 0 failures, 0 errors, 0 skipped** (summed from
  the fresh `app/build/test-results/testDebugUnitTest/*.xml`).
- **Functions emulator suites** (Firestore emulator 8080; suites run under their own
  `demo-myexodus-*` project namespaces with `FIRESTORE_EMULATOR_HOST`/`GCLOUD_PROJECT`, as
  `emulators:exec` would inject — the rig holds 8080, so a second emulator cannot start):
  `guest-transition.emulator.js` **16/16 passed**;
  `partner-ingest.emulator.js` **17/17 passed** — re-run on the final functions tree before the
  v40 deployment and again after it.
- **Android lint** (ADR #208 gate, run alone, never in the same invocation as tests):
  **"No issues found."**

## Device / release verification

- Release APK v40 / 1.1.38 built from this tree (`versionCode 40`, `versionName 1.1.38`),
  39,251,849 B, SHA-256 `219c6ea0387c455dac08ab341eb526922b39a15ff15c7e0635e150706bde7b10`.
- Installed on the X6532 with `install -r` (no uninstall; data preserved), dumpsys confirms
  `versionCode=40 versionName=1.1.38 minSdk=26 targetSdk=36`; app launches to `MainActivity`.
  Copy delivered to `~/Documents/MyExodus Release/MyExodus-1.1.38-v40.apk`.
- Functions deployed (4/4 "Successful update operation") with the updated lockfile.

## Honest limits

- Sonar CE had no `sonar.java.test.binaries` / `sonar.java.test.libraries`, so test-code analysis
  is less precise than main-code analysis. Production code is unaffected.
- Opengrep's Kotlin parser skipped or partially parsed 30 files; those files are covered by
  compilation + the JVM suite, not by SAST.
- Trivy reports lockfile facts, not exploitability; the dispositions above rest on reading the
  vulnerable call paths, not on a proof of non-reachability.
- No ZAP or Nuclei run: the only hosted endpoints are Firebase Functions behind App Check, and no
  DAST authorization window was opened for this wrap.
- Emulator coverage (rig) and physical coverage (single X6532) are reported separately in the
  partner-delivery phase log; no two-phone leg is claimed for this tree (#203/#214 remain BLOCKED
  pending a second device).
