# MyExodus

MyExodus is a local-first Android app that blocks the apps and websites you choose, tracks usage
against daily limits, records recovery streaks and relapses, and shares only what you opt into
with an accountability partner — without ever handing the partner plaintext access to your
activity.

It is distributed as a direct APK (no Play Store), targets every Android OEM skin, and works
offline: blocking is decided and enforced on the device, and the partner relay is the only
network feature.

- **Package:** `com.example.myexodus`
- **Current build:** `1.1` (versionCode 2)
- **Platform:** Android 8.0+ (`minSdk 26`, `targetSdk 36`, `compileSdk 37`)
- **Backend:** Firebase project `myexodus-53804` (Firestore, Cloud Functions gen 2, FCM, Storage)

---

## 1. Why it exists

Phone-based habits — social media, adult content, betting, doom-scrolling — are easy to start and
hard to stop because the device that delivers them also dispenses willpower credits. MyExodus
takes the decision out of the moment: the user defines what is blocked and when, the app enforces
it locally with no way to "just skip it", and an accountability partner the user chooses receives
the events the user has consented to share.

The V1 product definition — problem, requirements, acceptance criteria, and the honest per-feature
status — lives in [`app/docs/MyExodus V1.md`](app/docs/MyExodus%20V1.md).

---

## 2. What the app does

### Blocking and limits

- **Always-on blocks** for apps and websites, plus **time-range schedules**, **day-of-week
  schedules**, and **total block durations** that expire on their own.
- **Daily usage limits** per app; per-website daily limits metered through a local DNS-observing
  VPN, with a browser URL-bar path for browser traffic.
- **Named batches**: several apps and websites are created, blocked, paused, resumed, unshared
  and deleted together as one named unit (for example "Social media"), assigned to one partner.
  The partner sees one batch-level event with its item count, never N member rows.
- **Block attempts** are recorded automatically (no manual urge log needed), the wall offers a
  friction timer and an escape hatch to the local recovery tools, and attempts alert the
  partner when the rule is shared.
- **Pre-block warnings** for approaching limits and schedules.

### Accountability partner

- **Pairing ceremony** with short-authentication-string verification (SAS), attestation, and
  epoch-scoped keys; one person can support several users and be supported by several, and the
  connection is bilateral.
- **Partner hub**: activity feed, shared streaks, "needs you" items, plans (shared tasks with
  deadlines), motivational messages, nudges, a dedicated **Rules & blocks** page, and a one-tap
  **Reach out (SOS)** priority alert.
- **Every rule change reaches the partner**: create-with-partner, assign, edit, disable, delete,
  remove-partner/unassign, and natural expiry all notify them, naming the app/website and, when
  relevant, the batch. Notification taps open the exact partner and surface.
- **Blocked attempts and plan events** name the specific app, site, or batch — never a generic
  "something happened".
- **Consent is explicit** and per-permission; the partner only receives what the user shares.

### Recovery, streaks and insight

- **Days-free counter**, relapse reporting with a reflection step, milestones with celebration
  alerts to both sides, and recovery resources (techniques, crisis resources, risk windows,
  coaching plans, pacer).
- **Insights**: a local seven-day read model over the journals.
- **Home-screen widget** (Glance): compact and expanded status, day count, and a "Manage urge"
  shortcut; discreet mode hides details on the launcher.

### Data ownership and account

- **Encrypted at rest**: two SQLCipher-encrypted local databases (rules/usage and accountability
  state), with device-keystore-wrapped keys scoped per identity.
- **Backup/restore is logical data only** — habits, streaks, milestones, journal-derived
  content, recovery content and personal settings. Device-specific state (block rules, usage
  meters, pairing keys, outbox, widget snapshots) is deliberately excluded.
- **Identity**: anonymous first, with optional Google or email/password sign-in; session and
  scope fencing; a retained-scope recovery path for identity transitions; and account deletion
  that requires recent authentication.

---

## 3. How enforcement works

Three independent rails feed one decision engine, so a block survives process death, OEM
task-killers, and a missing network:

1. **Accessibility rail** — watches foreground window events, normalizes them, asks the decision
   engine, and draws the blocking overlay. It also reads browser address bars for website limits.
2. **Foreground-poll rail** — a foreground service polls the foreground app about every 1.5 s and
   applies the same decision. This catches cold starts and windows the accessibility rail defers.
3. **DNS sinkhole VPN** — answers blocked hostnames with a sinkhole address, enforces the
   release-mode website rail, and feeds observed domains to the per-site metering engine.

The decision engine's precedence (rule type and specificity) is a contract proven by a
device-run scenario matrix. Every rule write reloads the enforcement snapshot synchronously, so
the engine observes a change the moment the write completes — no background propagation window.
Daily-limit accounting is a ledger over usage events with outage detection and repair.

---

## 4. Privacy and trust model

- **Sealed envelopes end to end.** Partner events are encrypted on the sender's device; the
  server routes opaque blobs through per-pair mailboxes and tracks dispatch, idempotency and
  retention — it never holds plaintext activity. Pairing exchanges keys through the ceremony, and
  key material is epoch-scoped so removing a partner invalidates their access.
- **Server rules are the boundary.** Firestore rules deny every client write outside the user's
  own mailbox surface; callable functions enforce App Check, rate limits, and pairing invariants.
  Firestore App Check is enforced project-wide; the pairing callable carries its own check.
- **The server is not trusted with decisions.** Enforcement, metering and streak evaluation are
  local; the relay only carries what the user chose to share.
- Operational controls, kill switches and runbooks are documented (see §9), not hidden in code.

---

## 5. Technology

| Layer | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose (Compose BOM 2026.08.00) |
| Build | Gradle 9.4.1, AGP 9.2.1 with built-in Kotlin 2.2.10, JDK 17+ |
| Local data | Room 3 (`androidx.room3` 3.0.3) over `androidx.sqlite` 2.7.0, SQLCipher 4.18.0 |
| DI | Hilt (KSP) |
| Background | Foreground services, WorkManager, alarms, boot receivers |
| Backend | Firebase: Firestore, Cloud Functions gen 2 (Node.js), FCM, Storage |
| Widget | Jetpack Glance |

Current local schemas: app rules store **v23**, accountability store **v14**. Both are additive
or rebuilt migrations with rollback paths; the full dependency matrix is in
[`app/docs/MyExodus Build & Dependencies.md`](app/docs/MyExodus%20Build%20&%20Dependencies.md).

---

## 6. Repository map

```
app/
  src/main/java/com/example/myexodus/
    blocking/            rule model, decision engine, snapshots, accessibility + poll rails, wall
    blocking/usage/      daily-limit accounting, ledger, outage detection and repair
    domainusage/         website metering (DNS/TLS observation), engine state machine
    vpn/                 local DNS sinkhole VPN and domain blocklist
    accountability/      pairing, sealed event chain, outbox, mailbox sync, notifications, SOS
    accountability/state/ second encrypted DB: enrollments, chains, epochs, unpair state
    streaks/             habits, dual-clock streak evaluation, milestones
    recovery/            recovery sessions, techniques, crisis resources, risk windows
    data/db/             main encrypted DB: entities, DAOs, migrations, store factory
    settings/            protection checks, strict/enhanced modes, setup state
    widget/              Glance widget and status snapshot writer
    insights/            seven-day local read model
    ui/                  app shell, six-tab navigation, screens and shared UI atoms
    di/                  Hilt wiring (no logic)
  src/test/              JVM tests (Robolectric where platform APIs are needed)
  src/androidTest/       instrumented suites (emulator-gated destructive fixtures)
  docs/                  canonical specs, ADRs, runbooks, audits
  plans/                 implementation plans with status headers
functions/               Cloud Functions source (pairing, mailbox relay, SOS, heartbeat, DMS,
                         backup keys/manifests, account deletion)
firestore.rules          server-side authorization boundary
storage.rules            backup object authorization
firebase.json            emulator + deploy configuration
rules-test/              Firestore rules test suite
scripts/                 tooling, lint/security gates, device harnesses
emulator-test/           emulator-specific test scaffolding
```

---

## 7. Build and run

Prerequisites: JDK 17+ (local machine uses Java 25), Android SDK with platform 37, and `adb`.

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds run the domain-usage engine in `ENFORCE` mode so enforcement is testable on-device.

The in-app updater reads a dedicated public releases-only repository. A build without the
property fails closed (Settings shows "This build has no update channel"):

```bash
./gradlew :app:assembleRelease -Pmyexodus.updateRepository=<owner>/<releases-repo>
```

Release signing, the signed policy envelope and the publisher runbook are described in
[`app/docs/app-update-channel.md`](app/docs/app-update-channel.md).

Backend deploys use the Firebase CLI against `myexodus-53804`:

```bash
firebase deploy --only firestore:rules,storage,functions
firebase emulators:start          # local Auth/Firestore/Storage/Functions emulators
```

---

## 8. Tests and gates

Run tests and lint as **two separate invocations** — combining them is a known lint-worker crash
(ADR #208):

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

- Current unit suite: **265 classes / 2,462 tests / 0 failures / 0 errors / 0 skipped**
  (2026-11-07).
- Lint is the platform gate; the bar is **0 errors / 0 warnings**.
- Coverage (Gate 2.5 / SonarQube): `./gradlew :app:createDebugUnitTestCoverageReport`; the
  JaCoCo number is a floor, not the true reach.
- **Instrumented runs are emulator-only** via `connectedDebugAndroidTest` — UTP uninstalls the
  app when the run ends, which on a real phone would destroy live data and identity. Physical
  devices are verified with `adb install -r` plus `am instrument -w -e class …`, never an
  uninstalling task.

The project works evidence-first: a change is done when it is proven by tests and, where it
touches enforcement, pairing, delivery or UI, by a recorded physical-device run.

---

## 9. Documentation

Canonical sources live in [`app/docs/`](app/docs); the ADR log is append-only and currently
contains 251 decisions.

| Document | What it is |
|---|---|
| [`MyExodus V1.md`](app/docs/MyExodus%20V1.md) | Product definition, requirements, acceptance criteria, honest status |
| [`MyExodus Architecture Decisions.md`](app/docs/MyExodus%20Architecture%20Decisions.md) | Every significant decision, with context, alternatives, verification |
| [`myexodus-system-tour.md`](app/docs/myexodus-system-tour.md) | Guided tour: how the pieces connect, trace-it-yourself exercises |
| [`MyExodus Build & Dependencies.md`](app/docs/MyExodus%20Build%20&%20Dependencies.md) | Toolchain, pinned dependency matrix, verification commands |
| [`MyExodus Identity & Account Transition.md`](app/docs/MyExodus%20Identity%20&%20Account%20Transition.md) | Identity, account, backup/restore design and rulings |
| [`partner-sos-runbook.md`](app/docs/partner-sos-runbook.md) | SOS operations: kill switch, statuses, rollback |
| [`blocked-two-device-runbook.md`](app/docs/blocked-two-device-runbook.md) | Two-device verification procedures |
| [`MyExodus Monitoring.md`](app/docs/MyExodus%20Monitoring.md) | Alerts, dashboards, and operational signals |

Engineering standing instructions for contributors and AI agents are in [`CLAUDE.md`](CLAUDE.md).

---

## 10. Status

The V1 feature set is built and device-verified feature by feature; the remaining work is
hardening, exhaustive two-device verification and release-gate cleanup. The app is distributed
directly (GitHub Releases) and has never shipped through an app store, so store policy is not a
design constraint.

Version 1.1 is the current build. Per-feature status is tracked honestly in the V1 document; the
plan files in `app/plans/` carry status headers, and the progress notes for the current session
are kept outside the repository.
