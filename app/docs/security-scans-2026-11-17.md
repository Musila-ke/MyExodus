# Security scans and final gates — 2026-11-17 (v41 / 1.1.39)

Final-wrap ledger for the ADR #314 change set (widget-action crash fix: `HabitRepository` +
`RecoverySessionRepository` lenient vault open, two regression tests, docs). Every tool ran
against this working tree on 2026-11-17 ~00:05–00:15 EAT. Tool versions unchanged from the
2026-11-15 ledger: Opengrep 1.33.1, Gitleaks 8.30.1, Trivy 0.76.2. SonarQube was **not** re-run:
the change is two private helpers plus call-site substitution and two tests; the scan from
2026-11-15 (327 open, reliability A) stands as the baseline for this tree's unchanged code.

## Opengrep (SAST)

- `opengrep scan --config scripts/myexodus-opengrep-rules.yml app/src` — 7 rules, 576 files,
  0 findings; 30 files partially parsed (known Kotlin-parser limitation, same class of files as
  before). Note: this opengrep build rejects `--metrics`; run without it.

## Gitleaks (secrets)

- `gitleaks git . --redact -v` — **225 commits / 20.96 MB: 0 leaks**.
- `gitleaks dir . --redact -v` — **19.51 MB worktree: 0 leaks**.

## Trivy (dependencies)

- **App** — `trivy fs --scanners vuln --severity HIGH,CRITICAL app/gradle.lockfile` with
  `TRIVY_DB_REPOSITORY=ghcr.io/aquasecurity/trivy-db`,
  `TRIVY_JAVA_DB_REPOSITORY=ghcr.io/aquasecurity/trivy-java-db`, and
  `DOCKER_CONFIG=/tmp/trivy-dockercfg` containing `{"config.json":"{}"}` (the
  `docker-credential-desktop` helper is absent on this machine): **45 findings (41 HIGH,
  4 CRITICAL)** across `guava`, `netty-*`, `bcprov-jdk18on` — the same count and package families
  as the accepted 2026-11-05/15 baseline, all configuration-scoped (lint/uTP/test classpaths;
  the one application-scoped guava CVE stays accepted under task #216).
- Functions lockfile unchanged in this session (deployed 4/4 on 2026-11-16); the 2026-11-15
  0 HIGH/CRITICAL result stands.

## Android lint (ADR #208, run ALONE)

- `./gradlew :app:lintDebug` — **"No issues found."** (10 m 53 s, exit 0).

## JVM suite

- `./gradlew :app:testDebugUnitTest` — **2,850 tests / 0 failures / 0 errors / 0 skipped**
  (XML tally across `app/build/test-results/testDebugUnitTest`).
- `--tests 'com.example.myexodus.update.UpdatePolicyTest'` green against the published
  `policy-000016`.
