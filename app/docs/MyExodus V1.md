# **Feature Pitch.**

## Problem:

The problem we are trying to solve is the mindless usage of contents such as social media,  websites and applications that are easily accessed and mis-used via the mobile device. We are specifically targeting people aged 18 and above who end up wasting time on unproductive phone usage and also those who are addicted to certain materials that are accessed via a phone like adult content, betting, social media etc.

## Solution:

The solution is a mobile application that enforces limits and blocked access to certain materials that a user can choose, and also tools that can be able to reduce the urges a user might have to access certain contents and also bring in an accountability partner who will be ensure that users are true to their word about trying to reduce or eliminate usage and viewing of certain materials.

## Domain Glossary:

These are the official vocabulary terms we will use to describe the system's behavior:

* Active Restriction Period: A specific timeframe during which the system blocks all access to a user-specified app or URL
* Block Attempt: An automatically recorded instance of a user triggering the block screen. This replaces the manual "urge log" to provide objective data on temptation frequency.
* Relapse: An event triggered when a user manually presses the 'report a relapse' button, which breaks their current streak
* Days Free Counter (Clean Days): A tracking metric that increments only when a full 24-hour period elapses without a recorded relapse
* Milestone: A predefined target of consecutive "days free" (e.g., 7 days, 30 days) that, when reached, triggers a celebration notification to both the user and their partner
* Accountability Partner: A linked human supporter who receives alerts and can view the user's usage analytics dashboard.
* **Local Enforcement State:** The client-side, mutable cache layers (local Room DB tables) read instantly by background services to draw overlay shields.
* **Server-Observed Event History:** The authoritative, tamper-evident stream of signed domain events stored on the cloud server from which all partner dashboards, milestones, and streak calculations are projected. Events are immutable while retained, but deletable by design on user request (see Req 24 and Req 29) — see the Data Lifecycle note under Req 29.
* **Confidence-Level Tamper Model:** A heuristic system that distinguishes OS process reclamation from manual user permission revocation.
* **Solo-Mode Quarantine:** A temporary local device lockout (defaulting to 15 minutes) triggered immediately when a solo user revokes critical system permissions during an active schedule.
* **Interception Escape Hatch:** A dedicated link embedded directly into the active blocking overlays allowing instant, single-tap navigation back to the local MyExodus recovery dashboard.

**Naming convention:** "MyExodus" is the public product/brand name. All package identifiers, URLs, and code identifiers must be ASCII — e.g., the Android package name is `com.example.myexodus` (Android package names cannot contain non-ASCII characters).

## Domain Invariants:

These are the non-negotiable rules that must always hold true across your entire system:

* **The Bounded Enforcement Invariant:** When the AccessibilityService is actively bound by the system, the application has not been placed in a system-restricted background state, and the OS permits window-overlay presentation, MyExodus shall enforce active restriction rules locally without network connectivity.
* The Strict Block Rule: Submitting an "urge reason" form does not grant access to the blocked material.
* **The Interception Latency Limit (Android):** End-to-end detection-to-overlay interception must complete within **<1000 ms (p99)**, composed of detection latency (AccessibilityService event / polling) plus overlay draw latency (**<100 ms p99 once interception is triggered**). The feasibility spike must validate both sub-metrics on physical devices before the final number is frozen (see Technical Feasibility). On iOS, interception is OS-rendered via Screen Time shields and app-side latency is zero — there is no app-side target to measure.
* The Encryption Mandate: Sensitive user data (urge logs, relapse reflection) must always be encrypted at rest using SQLCipher.
* The Explicit Permission Rule: Partners can only receive alerts if the user has explicitly granted those viewing permissions.

Why now is because screentime and addictions among the 16+ year olds is at a high and current available solutions are either too expensive, permissive or don't have the relevant features that tackle these kind of issues effectively.

## Decision Classification (One-Way Doors)

Major One-Way Doors: The technology stack and the underlying database data model. These are expensive to reverse and require extra scrutiny before writing code.

## Stakeholders:

* The Solo Developer
* Early Testers
* Accountability Partners
* Regulators / Privacy Law (governing the collection of sensitive data)
* Apple & Google App Store Reviewers (for future V2/V3 app store releases)

## Risk Classification:

Risk Level: High

Reason: The system handles highly personal "urge logs" and habit data, meaning the impact of a failure includes severe personal exposure and data sensitivity. This dictates that we must use explicit threat modeling and thorough security reviews later on.

## Pre-Mortem:

1. User data is leaked.
2. Application is slow and tasks are made more difficult to execute
3. Mistakes that shouldn't occur, occur often like blocking the wrong item, blocking at the wrong time, false alarms sent to the partner often.
4. Usage is calculated improperly.
5. Users are able to bypass blocks easily.
6. Mixing up of user data, wrong person sees and receives wrong data or right person receives wrong data.
7. App insecurity such as unauthorized people, add,delete or modify their own data or other peoples data easily.
8. Data inaccuracy and inconsistency .
9. Complex features that users don't know how to use.
10. Poor communication when issues occurs, and too many downtimes
11. OS update or OS itself blocks core features.
12. **Silent enforcement failure (fails open):** the background service is silently killed by an OEM battery optimizer and enforcement stops while the user still believes they are protected — precisely at a moment of relapse risk. Mitigation: foreground service with persistent notification (Step 9 of Epic 2) plus keepalive heartbeat detection (Req 30).
13. **Coercive misuse of partner visibility:** an accountability partner uses the visibility of relapse/urge data to control or pressure the user (see H6 in the audit that produced this revision). Mitigation: easy, discoverable partnership termination; clear disclosure of exactly what the partner can see; DPA deletion flow shreds the partner's copy of the user's history (Req 29).

# Research

## Competitor Analysis.

| Feature | Your product | Competitor A: Opal | Competitor B: Jolt | Gap or opportunity |
| :---- | :---- | :---- | :---- | :---- |
| Main job | Help users stop compulsive phone use, avoid risky content, and stay accountable to a human partner. | Blocks distracting apps and websites through Focus Sessions and Deep Focus. | Lets a real partner see activity, approve overrides, and receive notifications. | Combine blocking, human accountability, and recovery support in one flow. |
| Handling temptation | Should help the user pause before opening a harmful app or site. | Makes it harder to open distracting apps during sessions. | Sends override requests to a partner. [thejoltapp](https://www.thejoltapp.com/accountability-partner) | Add a stronger "in-the-moment" interruption plus partner support. |
| Human support | Core feature. | Not clearly centered in the sources I found. | Core feature. | Make the partner experience simpler, clearer, and more emotionally supportive. |
| Recovery after a slip | Should help users recover, not just block them. | Mostly blocking and focus control. | Mostly approval and accountability. [thejoltapp](https://www.thejoltapp.com/accountability-partner) | Add check-ins, coaching, or a "get back on track" mode. |
| Why it matters | You are building for people who need more than willpower. | Good for focus and distraction control. | Good for accountability with a real person. | The missing piece is a full behavior-change journey, not just one tool. |

# Job-To-Be-Done Statement:

Help users stop compulsive phone use, avoid risky content, and stay accountable to a human partner

# Regulatory Considerations

This data framework is designed to meet the strict legal requirements of Kenya's data protection landscape. My compliance strategy is anchored in the Data Protection Act (DPA), No. 24 of 2019, and its supporting 2021 Regulations. Since this application processes sensitive behavioral tracking data such as urges and relapses it qualifies as sensitive personal data under Section 2 of the DPA, requiring me to integrate rigorous security and privacy safeguards from the start.

## Part 1: Verification of Listed Areas

1. **Data Inventory:** Under Section 41 (Data Protection by Design or Default) and Section 19(2), you are legally required to map your data flows and implement technical measures *prior* to processing.
2. **Consent:** Under Section 32 and Section 30, consent must be express, unequivocal, and informed. You bear the burden of proof for establishing a data subject's consent.
3. **Data Minimization:** Under Section 25(d), you must limit data collection strictly to what is necessary.
4. **Partner Sharing:** Sharing behavioral urges/relapses involves sensitive personal data. Under Section 28, you must ensure granular sharing settings so the user has full control.
5. **Retention:** Under Section 25(g), you cannot keep sensitive behavioral data indefinitely. Once the specific purpose lapses, your justification expires. (See the retention policy under Req 29.)
6. **Security:** Regulation 32 mandates safeguards such as encryption, access controls, audit trails, and secure backups.
7. **DPIA:** High-risk processing (e.g., sensitive personal data) triggers a mandatory Data Protection Impact Assessment (DPIA) to be submitted to the ODPC 60 days prior to processing.
8. **Breach Response:** Under Section 43 and Regulation 38, Data Controllers must notify the Data Commissioner within 72 hours of a breach; Data Processors must notify the controller within 48 hours.
9. **Vendor Review:** Under Sections 25(h), 48, and 49, you must prove adequate safeguards for any data transferred outside Kenya.

## Part 2: Critical Regulatory Obligations

Before finalizing your architecture and launching, address these binding obligations:

* **Mandatory ODPC Registration:** Under Section 18(1), you must register as a Data Controller via the ODPC online portal. Processing sensitive behavioral data makes registration mandatory.
* **Data Subject Rights:** System architecture must support:
  * Right to Access: User request for a copy of their behavioral history.
  * Right to Objection: Objecting to automated tracking/profiling.
  * Right to Correction/Rectification: Correcting inaccurate logs.
  * Right to Deletion: Wiping sensitive history — locally **and** on the server (see Req 29).
* **Appointment of a Data Protection Officer (DPO):** Under Section 24(1)(c), appointing a DPO is mandatory if your core business involves processing sensitive personal data.
* **Children's Data Safeguards:** Under Section 33, processing data for children (under 18) requires parental/guardian consent and robust age verification. **This revision's position:** the app targets 18+ users only and implements an age *gate* (self-declared date of birth plus an explicit 18+ declaration on onboarding — Entity 1). A self-declared date of birth is NOT identity-grade age verification; whether "robust age verification" is triggered by this app's design (and what a proportionate mechanism would be) is flagged for counsel review in Open Research Questions, item 1. The compliance claim in this doc should not be read as asserting identity-grade age assurance.
* **Direct Marketing Opt-In:** Regulation 14 requires specific, opt-in consent for marketing, separate from Terms of Service.
* **Cross-Border Transfer Agreements:** Under Sections 48 and 49, all data transferred to global managed backends (Firebase) is intended to be protected via Standard Contractual Clauses (SCCs). **Open question (not settled):** whether a solo developer on Firebase's standard terms satisfies the Sections 48-49 "burden of proof" — see Open Research Questions, item 2.
* **Serving Copy Localization:** If processing data outside Kenya, a serving copy must be maintained in a data center located in Kenya — this is what ADR#8 addresses; its recommended status is now REJECT (Section 50 verified as a discretionary power, not a self-executing mandate), pending counsel confirmation — see the ADR document and Open Research Questions, item 1(c).

## Summary Checklist for Implementation

1. **ODPC Registration:** Submit as a Data Controller.
2. **DPIA:** Conduct and submit 60 days before launch.
3. **Data Subject Rights Portal:** Build in-app triggers for access, correction, and deletion (Req 24).
4. **DPO Designation:** Appoint and register your DPO with the ODPC.
5. **Age Gate:** Implement the 18+ age-gate flow (declared DOB + declaration; Entity 1) and obtain counsel's read on whether more is required under Section 33.
6. **Direct Marketing Consent:** Add a separate, un-checked opt-in checkbox.
7. **Cross-Border Transfer Agreements:** Establish standard contractual clauses with non-Kenyan vendors (pending the Section 48-49 review above).

# Constraint Discovery.

## Timeline:

3 to 6 months for the first version.

## Budget Ceiling:

**9,000 KES total for the V1 project (one-time, not monthly).** Itemized against it:

* ~~Google Play registration: ~$25 (~3,250 KES, one-time)~~ — **NOT applicable (revision 2026-08-24): the app will NOT be published on Google Play (ADR#15).** Distribution is direct APK only; the ~3,250 KES is freed for other costs.
* Domain name: ~1,100 KES/year.
* AI API costs: remainder (variable).
* Firebase: free tier covers V1 scope; costs only if usage exceeds free tier (monitor monthly).
* Nairobi VPS (ADR#8, ~600 KES/month): **NOT budgeted for V1.** ADR#8 is Pending legal review — if a serving copy is legally required, either the budget must increase or Firebase-only (SCC-based) must be accepted; see Open Research Questions, item 2.

Team & Skill Mix: Solo developer; learning project. Non-expert in frontend/backend, which means the architecture must lean towards simple, well-documented, "boring" technology rather than complex custom setups. We will actively use AI to bridge the coding knowledge gap, treating it as a collaborator rather than a replacement for your engineering judgment.

**Scope triage commitment:** Because this is a solo, non-expert, 3-6 month build, every "clever" architecture in this document is only included if it has a boring alternative explicitly identified. See "Scope Triage" after the requirements register.

# Technical feasibility

## Technical feasibility spike:

The riskiest unknown first, the part most likely to make the whole approach infeasible.

The Riskiest Unknown: Do iOS and Android native OS APIs actually allow a third-party application to intercept, block, and redirect a user away from other apps and websites completely offline, and render a block screen within the <1000 ms end-to-end target (Android)?

Target Device Scope: This application explicitly targets standard consumer devices (fully owned by the user). It will not use Enterprise/MDM (Mobile Device Management) or "Device Owner" privileges.

1. The Android Reality (Conclusion: Go-with-Conditions)

On Android, blocking apps and websites is technically feasible for standard consumers. (Revision 2026-08-24: the earlier "relies on workarounds to comply with Google Play policies" framing is obsolete — the app is not published on Google Play, ADR#15; the mechanisms below are unconstrained by store policy.)

**Detection & Blocking APIs (decided):** **AccessibilityService is the primary interception mechanism.** UsageStatsManager cannot provide sub-second detection (it is documented as non-real-time, batched data), so it cannot meet the <1000 ms interception target alone. The AccessibilityService listens for `TYPE_WINDOW_STATE_CHANGED` events to detect foreground-app changes and triggers the interception flow; SYSTEM_ALERT_WINDOW then draws the full-screen block overlay. Accessibility scope is deliberately narrow: window-state events for foreground detection, plus the specific content reads documented in Epic 2 Step 7 (browser URL-bar parsing for web blocking, Settings-app interception). (Revision 2026-08-24: the footprint is no longer constrained by a Play Store risk assessment — ADR#15 removes Play distribution; the narrow scope is retained for least-privilege and battery reasons.)

**Usage Tracking API:** UsageStatsManager is used to accurately record how long a user spends in foreground apps (the authoritative source for daily time-limit quotas), provided the user grants "Usage Access" permissions.

**Latency Reality (final target):** End-to-end detection-to-overlay <1000 ms (p99) = detection latency (AccessibilityService event delivery) + overlay draw (<100 ms p99 once triggered). Both sub-metrics are validated by the feasibility spike; the frozen number is updated here if the spike measures differently.

**Anti-Uninstall & Resilience:** Device Admin hard locks are **removed as the default enforcement path** to improve user trust; Device Admin remains available as an **opt-in** in all builds (Revision 2026-08-24: the `directRelease`-only scoping was removed with the Play flavor split — there is no Play Store build, ADR#6/#15). The default path is a PACKAGE_REMOVED broadcast receiver; if the app is uninstalled or critical permissions are revoked, the system triggers immediate alerts to the accountability partner and logs the event as a high-severity tamper attempt.

2. The iOS Reality (Conclusion: Go-with-Conditions)

(Note: As discovered in online research, Apple provides a native, privacy-preserving path for digital wellness apps via the Screen Time API.)

App Interception & Blocking APIs: The app will use Apple's Screen Time API.

* FamilyControls: Provides the system UI for the user to select apps and web domains. Apple returns these as "opaque tokens" (meaning our app never learns the actual names of the selected apps).
* ManagedSettings: Takes those tokens and natively applies a "shield" (custom block screen) directly at the OS level.

Usage Tracking APIs: The app will use DeviceActivity to monitor usage time. Because of opaque tokens, we cannot see minute-by-minute app analytics. Instead, we use Threshold Monitoring (e.g., the OS alerts our app the moment a 2-hour daily limit is reached).

Latency Reality: Interception is rendered entirely by the OS — app-side latency is zero; end-to-end latency is bounded by a single OS frame (<16 ms). "0 ms" is not used as an NFR claim because it is not a falsifiable engineering target; the testable claim is "app-side overhead zero; OS-rendered within one frame".

The Conditions/Risks:

* Urge Log Routing: Apple does not allow custom block shields to open the main app directly. Users must tap "Log Urge" on the shield, which triggers a local push notification that routes them to the app.
* App Store Gatekeeper: To use these APIs, we must apply for and be granted the Family Controls Distribution Entitlement from Apple. This is a strict, manual approval process.

3. Core Feature Mapping

By designing our logic around "Thresholds, Schedules, and Shields", we can achieve the core blocking requirements across both platforms despite massive OS differences:

* All-Day (Always On) Blocking: Handled by keeping the AccessibilityService active unconditionally (Android) or applying ManagedSettings shields indefinitely without a schedule (iOS).
* Specific Time Ranges & Days (e.g., 9 PM to 10 PM, Mon-Fri): Handled natively via WorkManager/AlarmManager (Android) and DeviceActivitySchedule (iOS).
* Daily Usage Limits (e.g., 2 hours total): Handled via UsageStatsManager (Android) and DeviceActivity thresholds (iOS).
* Total Block Duration (e.g., 1 month, 6 months, 1 year): Handled via app-level database logic. The app will save an expiration timestamp for the user's rule and instruct the OS to enforce the block until that date passes.
* Website Blocking: Handled via Accessibility URL-bar interception (Android) and ManagedSettings web domain shields (iOS). A local VPN (VpnService + DNS filtering) is the evaluated alternative if URL-bar parsing proves technically fragile across OEMs (Revision 2026-08-24: the "Play policy risk" motivation is gone — ADR#15; see Open Research Questions, item 3).

4. Next Steps: The Feasibility Spike

Before finalizing the system architecture, the following throwaway tests must be run on physical devices:

* **Android Accessibility Test (extended):** Build a minimal AccessibilityService that listens for an app to open and displays an overlay. Measure the detection-to-overlay latency across a cold/warm launch (validates the <1000 ms end-to-end and <100 ms draw sub-metrics), **and measure the service's average CPU utilization during continuous operation** (validates the <2% CPU ceiling in Epic 2 Step 11).
* Android UsageStats Test: Request Usage Access and verify the accuracy of foreground duration tracking.
* iOS Entitlement Application: Immediately apply for Apple's Family Controls Distribution Entitlement, as this determines if the iOS build can proceed to production.
* ~~**Play Store Policy Validation (new):** assess approval odds against Google Play policy~~ — **REMOVED (revision 2026-08-24): not applicable — the app is not published on Google Play (ADR#15).** The disclosure/notification UX it would have required (persistent foreground-service notification, in-app accessibility disclosure, privacy policy) is still good practice and retained, but it is not a store-approval gate. If a Play release is ever reconsidered, this spike reactivates.
* **Scope Deliverability Check (new):** One week after engineering starts, validate that the triaged V1 scope (see Scope Triage) is realistically shippable solo within the remaining timeline. If not, cut scope per the triage list before investing further.

## Domain Invariants recap:

(Single canonical glossary lives at the top of this document; no duplicate glossary is maintained here.)

# Requirements

## Functional Requirements & Acceptance Criteria

Every functional requirement below specifies a testable behavior. Before any of these features can be considered "done" during development, they must pass both the happy path and edge case criteria listed.

### 1. Active Restriction Blocking

**Requirement:** The system shall block access to a specified app or web domain when the user attempts to open it during an active restriction period.
**Happy Path:** Given the time falls within an active block schedule, When the user attempts to open a restricted app, Then the system shall instantly render the block screen.
**Edge Case:** Given the device has completely lost internet connectivity, When the user attempts to open a blocked app, Then the system shall still successfully enforce the block 100% offline.

### 2. Time-Range Schedules

**Requirement:** The system shall allow users to set specific time-of-day restrictions (e.g., "9:00 PM to 6:00 AM") for blocked items.
**Happy Path:** Given a time-range block is set for 9:00 PM, When the system clock strikes 9:00 PM, Then the background service automatically activates the restriction rules.
**Edge Case:** Given the user reboots their phone at 9:15 PM, When the phone finishes booting, Then the system immediately detects the time range and reinstates the active blocks.

### 3. Day of the Week Scheduling

**Requirement:** The system shall allow users to select specific days of the week for a restriction to be active.
**Happy Path:** Given a restriction is set only for Saturday and Sunday, When the user opens the target app on Saturday, Then the block is enforced.
**Edge Case:** Given the schedule is set for Saturday only, When the user uses the app on Friday at 11:59 PM, Then the app remains unblocked until exactly midnight.

### 4. Daily Usage Limits / Thresholds

**Requirement:** The system shall monitor restricted apps and websites to detect when they hit a predefined daily usage limit (e.g., 2 hours).
**Happy Path:** Given a daily limit is set, When UsageStatsManager reports threshold breach, Then SessionStart triggers monotonic elapsedRealtime tracking for immediate enforcement.
**Edge Case:** Given the user crosses the 2-hour threshold while the device has no network connection, When the limit is reached, Then the system successfully intercepts and blocks the app locally.

**Implementation status (2026-09-13): deferred by product decision.** The schema column (`daily_limit_ms`) and the engine branch (`usageTodayMs >= dailyLimitMs`, only evaluated when a caller supplies usage) exist, but nothing feeds usage data: the rule editor creates rules with `dailyLimitMs = 0`, and the enforcement path evaluates with `usageTodayMs = null`. A daily limit can therefore be neither set in the UI nor enforced. Making it real requires a `UsageStatsManager` threshold feed into the enforcement path — enforcement work outside the UI-only scope of this phase. No UI copy promises a daily-limit feature.
**Un-deferred 2026-09-13 (user direction — "nah we need it").** Back on the build queue. Shape of the build: foreground-time accumulation per restricted app in the FGS's existing `queryEvents` poll (the PACKAGE_USAGE_STATS permission and the 1.5s ACTIVITY_RESUMED loop already exist — the feed is mostly bookkeeping — session start/end accumulation, persistence, and a day-boundary reset — not new infrastructure), the `dailyLimitMs` field in the rule editor, the engine branch fed with real usage, and Req 32's pre-block warnings (10-/5-minute heads-up notifications) riding on the same feed. Plan pending.

### 5. Total Block Duration Expirations

**Requirement:** The system shall allow users to set a long-term expiration date (e.g., 1 month, 6 months) for their restriction rules.
**Happy Path:** Given a block rule is set to expire on October 1st, When the current date becomes October 2nd, Then the restriction is automatically lifted.
**Edge Case:** Given a user attempts manual clock rollback, When the Clock State Machine cross-references wall-clock time with SystemClock.elapsedRealtime() to detect reboot-based false positives, Then the block remains enforced if a delta mismatch is confirmed. (Clock-tamper disambiguation rules in Epic 2 Step 7 must apply so legitimate timezone/DST/NTP changes do not trigger lockout.)

### 6. Partner Alerts on Block/Bypass

**Requirement:** The system shall send an automated alert notification only to the specifically assigned accountability partner the instant a user initiates a block bypass.
**Happy Path:** Given the user has permitted partner alerts, When the user hits a block screen, Then the partner receives a push notification.
**Edge Case:** Given the user's device is completely offline and queues multiple events locally, When the device regains its internet connection and syncs, Then the system shall aggregate the queued events into a single summary alert to prevent notification spam.

**Implementation status (2026-09-13): engine-level only; no pairing UI ships.** `PartnerSyncCoordinator` (15-minute WorkManager pass) turns local `attempt_log` rows into chain events and delivers them through the relay, and it degrades correctly with no partner: an unpaired device short-circuits and attempts stay local. But the shell has no pairing screen, so every install is unpaired today and no alert can be delivered end-to-end. Block screen and Recovery copy therefore say "Overrides are logged on this device." — the earlier "shared with your partner" wording was false for an unpaired user. Partner-aware copy returns when a pairing screen ships.

### 7. Accountability Partner Dashboard

**Requirement:** The system shall display a high-level summary dashboard (e.g., daily thresholds met, days free, relapse counts) to the partner when permitted.
**Happy Path:** Given the user has enabled sharing, When the partner opens the analytics tab, Then they see the summarized "days free" and "thresholds reached" data.
**Edge Case:** Given a discrepancy between local and cloud data, Then Server-Side Authority shall prevail for all partner-visible analytics and milestones.

### 8. Recovery Resource UI

**Requirement:** The system shall display a specific recovery resource screen when the user taps the "manage urge" or "log urge" button.
**Happy Path:** Given the block screen is active, When the user taps "log urge", Then the system opens the recovery resource UI.
**Edge Case:** Given the user is on iOS (where custom shields cannot directly open apps), When the user taps "log urge" on the OS shield, Then the system triggers a local notification to safely route them to the main app's UI.

### 9. Automated Block Attempt Tracking

**Requirement:** The system shall automatically increment the target's "Block Attempt" counter whenever the user triggers the active block screen.
**Happy Path:** Given a restricted app is opened, When the block screen renders, Then the system automatically increments the block_attempt_count for that target in the local database.
**Edge Case:** Given multiple rapid attempts to open the same blocked app, When the system processes the events, Then it ensures each distinct interception is counted accurately without database locks.

### 10. Returning to Home Screen

**Requirement:** The system shall maintain the restriction and return the user to their device's home screen when they successfully submit an urge log.
**Happy Path:** Given a successful urge log submission on Android, When the success state triggers, Then the user is natively routed to the home screen.
**Edge Case:** Given iOS prevents programmatic return-to-home from a third-party app, When the urge is logged on iOS, Then the system relies on the Screen Time Shield .close action to force the target app to exit natively.

### 11. Days Free Counter

**Requirement:** The system shall increment a habit's "days free" counter when a full 24-hour period elapses without a recorded relapse.
**Happy Path:** Given no relapses are reported for 24 hours, When midnight passes, Then the streak increments by 1.
**Edge Case:** Given the user travels across time zones, When local midnight occurs, Then the system accurately handles the 24-hour absolute elapsed time without falsely breaking or granting a streak.

**Implementation status (2026-09-12):** the V1 home dashboard (restricted-user view) is built and device-verified on the X6532: app shell with a single Scaffold (NavigationBar below 840dp width, NavigationRail at/above it; content column capped 840dp, 16/24dp side padding by width class), a "Days free" hero (honest zero state — streak counting activates when habit data exists; Req 18 onboarding not yet built), the degradation banner, and a "Your blocks" list rendering one row per rule with **live state computed by the engine's own schedule evaluation** (`BlockDecisionEngine.blocksNow` — the same Rule 1/3 logic as enforcement, so timezone/DST/midnight-crossing semantics are never re-derived in the UI; daily-limit rules show no state pill because no live usage feed exists on the hot path). Per-target attempt counts come from `attempt_log` (Req 9 data). The full Desert Dawn M3 color scheme (all container/dim/outline/inverse roles) is implemented for light and dark. Home content not asked for by this requirement set (partner card, enhanced-mode controls, add-rule FAB) was removed — enhanced-mode controls live under Settings; partner UI waits for the partner plan. Req 12 milestone celebrations/notifications remain unbuilt.

### 12. Milestone Notifications & Celebrations

**Requirement:** The system shall trigger a milestone celebration notification to both the user and the partner when the user's "days free" counter reaches a predefined target, and render a persistent celebratory splash card on the user's dashboard.
**Happy Path:** Given the user reaches Day 7, when the midnight transition occurs, then both the user and partner receive a celebration notification, and the user is greeted with a customized "Milestone Achieved" card on their next app launch.
**Edge Case:** Given the user's device is offline when the milestone is hit, when the local clock records the 24-hour absolute elapsed time, then the system triggers the celebration UI locally, queuing the partner notification to sync to Firebase once a network connection is available.

### 13. Friction Timer

**Requirement:** The system shall enforce an un-skippable 3-to-5 minute countdown timer when the user attempts to bypass an active block, before access is granted.
**Happy Path:** Given a bypass request, When a unique UUID is assigned, Then the countdown initiates as an idempotent operation to prevent timer resets.
**Edge Case:** Given the user force-closes the app during the countdown, When they reopen the app, Then the system resumes the countdown.

**Implementation status (2026-09-13):** built and unit-verified. The friction flow is started in one tap from either block surface — the app block screen's "Open MyExodus Recovery" (`BlockerActivity`) and the URL pause card's button — both landing on the Recovery tab, where the dual-clock countdown and the cooldown-gated override live (`ui/recovery/RecoveryScreen.kt`); the same persisted session surfaces anywhere in the app and resumes after process death from the stored deadlines (Req 13 edge case). BlockerActivity carries no countdown UI of its own (ADR #31). Force-close resume is covered by the dual-clock model, not an in-memory counter. Note: the seeded friction duration is 60s (`recovery_policy`), not the 3-to-5 minutes this requirement's prose states — the shipped engine value stands (see "V1 UI Phase 1" below); no UI copy prints a duration. The un-skippable property is bounded by Home always working, per the §10 escape-hatch invariant in the blocking-core plan.

**Device-caught bypass, fixed 2026-09-13:** a completed session row was never consumed, so after a rule re-arm the next blocker launch found the spent session (`remaining == 0`) and auto-unlocked the rule instantly — zero friction, device-reproduced (blocker flashed and vanished; rule expired again). Fixed by consuming the session in every unlock path: `RecoveryStateStore.clear()` (`DELETE FROM recovery_state`) called from `ui/recovery.unlockRule` — since ADR #31 the Recovery tab owns every unlock, so that single call site is the whole class of paths. The legit crash-resume case is unaffected — a session mid-countdown is not cleared, only a session whose unlock actually fired. Regression guard: `RecoveryUnlockTest` (fails if `unlockRule` stops clearing).

### 14. Relapse Alerts & Supportive User Reflection

**Requirement:** The system shall send a relapse alert to the assigned partner when the user manually reports a relapse, and instantly transition the user to a localized reflection interface.
**Happy Path:** When a relapse is logged, the Notification State Machine transitions the alert from PENDING to SENT, and finally to ACK upon partner viewing.
**Edge Case:** Given the user is offline when a relapse is reported, when they submit the relapse, then the app displays the local reflection resource offline, encrypts and queues the relapse event in the SQLCipher database, and syncs it to Firebase immediately upon network re-connection.

### 15. Partner Motivational Messages

**Requirement:** The system shall display a motivational notification to the user when the accountability partner submits a daily reminder message, and persistently pin this active message to the top of the user's main dashboard.
**Happy Path:** Given the partner has sent an encouraging message or Scripture verse, when it transmits, then the user receives a push notification AND the message is pinned to their main app screen.
**Edge Case:** Given the user is offline when the partner sends the message, when the device regains connection, then the local sync engine downloads and displays the pinned message on the main dashboard.

### 16. Dual-Role Capability

**Requirement:** The system shall allow a single authenticated account to function simultaneously as a restricted user (being monitored) and an accountability partner (monitoring someone else).
**Happy Path:** Given a user has an active monitoring account, When they log in, Then the interface allows toggling between their restricted dashboard and their partner dashboard without re-authentication.
**Edge Case:** Given a user is both a monitor and restricted, When a conflict occurs in notifications, Then the system prioritizes the restricted status to ensure personal accountability.
**Edge Case (multi-device):** Given the same dual-role account is signed in on two devices, When both devices trigger the same local milestone at nearly the same time before sync, Then the server dedupes by (installation_id, event_id) and issues a single milestone event (see Req 27 idempotency).

**Implementation status (2026-08-29):** the E2EE Accountability-Partner Protocol (plan: `app/plans/accountability-partner-protocol-implementation-plan.md`, phases 1-6) now supplies the transport layer this role needs: transcript-bound SAS enrollment, hash-chained signed+encrypted events (`activity` / `deletion_request` types only), Firestore mailbox + animated QR sync, bilateral crypto-shredding. The partner-approval-to-disable flow (the §16-specific consumer of the event channel) is NOT part of that plan and remains unbuilt — `StrictModeManager.PARTNER_APPROVAL_ENABLED` stays `false` with `canDisable()` gated on the 24h cooldown only (plan Decision 15). When the approval flow is specified, it lands as a new `EventPayload` type over this transport; do not flip the flag before an approval event type exists in `serialization/EventPayload.kt`.

### 17. Device-Level Blocking

**Requirement:** The system shall enforce active restriction rules at the local device level, ensuring that blocks remain fully active and partner alerts still trigger even if the user logs out or switches to a partner dashboard view.
**Happy Path:** Given the app is in the background or the user is logged out, When an attempt to access a blocked app occurs, Then the system-level blocking service continues to intercept the attempt and log the event.
**Edge Case:** Given the user switches to the partner dashboard view while a restriction period is active, When an access attempt occurs, Then the background blocking service maintains enforcement and the alert queue remains active.

### 18. Habit-Duration Onboarding & Recovery Timeline

**Requirement:** The system shall capture the user's habit duration and severity during onboarding to generate a personalized, weekly withdrawal expectation timeline.
**Happy Path:** Given a user onboarding for "Betting", when they select "Hooked for 2–5 years", then the system calculates their personalized withdrawal timeline and displays their active weekly milestones (e.g., "Week 1: Expect high anxiety and immediate urge loops").
**Edge Case:** Given the user changes their habit target or resets their streak, when the streak is reset, then the withdrawal timeline recalculates dynamically from Day 1 based on their saved severity profile.

### 19. Willpower-Adaptive Urge Management

**Requirement:** The system shall analyze the local device time when an active block is triggered or an urge is logged, adapting the suggested recovery resources based on estimated willpower thresholds.
**Happy Path:** Given the user opens a blocked app at 3:00 AM (Low Willpower Window), when they tap "Manage Urge", then the system suggests low-effort grounding resources (e.g., box-breathing, listening to calming audio) rather than high-effort activities (e.g., journaling, exercising).
**Edge Case:** Given the device is completely offline during a late-night urge, when the user triggers the block, then the system uses a localized time-of-day mapping to serve the low-willpower resources offline without needing a server connection.

### 20. Custom Coaching & Emergency Action Plan

**Requirement:** The system shall provide a dedicated "Coaching Plan" page where the user can define a sequence of custom emergency steps to execute when facing an urge, which is mirrored on the partner's dashboard.
**Happy Path (User):** Given the user is on the "Coaching" page, when they create emergency action steps (e.g., "Read Galatians 5:16", "Call my partner"), then the system saves this plan locally and displays it during active urge interception.
**Happy Path (Partner):** Given the partner opens their dashboard, when they view the user's profile, then they see the user's active, custom emergency steps so they can ask, "Did you walk through your steps?".
**Edge Case (Offline):** Given the user triggers an active block while offline, when they open the Recovery Resource UI, then the system displays their custom coaching steps completely offline using the local database.

### 21. Solo-Mode Quarantine Enforcement

**Requirement:** The system shall immediately initiate a 15-minute Solo-Mode Quarantine lockout if a user in Solo Mode revokes Accessibility or Usage Stats permissions during an active restriction schedule.

### 22. Build Flavor Strategy (and Uninstall Resilience)

**Requirement:** The system shall provide uninstall resilience on **all builds** — there is no Play Store distribution (ADR#15), so no compliance flavor exists. Settings Shield (Accessibility-based) and opt-in Device Admin apply to every build; Device Admin is never a default enforcement path. Product flavors or variants of the app, if introduced, are product decisions and all carry this same resilience stack (Revision 2026-08-24: replaces the former *playRelease*/*directRelease* split, which existed solely for Play compliance).

### 23. Confidence-Level Tamper Model

**Requirement:** The system shall implement a heuristic model to programmatically distinguish between a manual user permission revocation (tamper attempt) and an OS-initiated background process termination.
**Happy Path (OS-Driven Termination):** Given the system detects that the AccessibilityService has been disconnected, when the secure system settings string contains our package name (com.example.myexodus), then the system shall recognize this as an OS-level reclamation, suppress all tamper lockouts/alerts, and gracefully prompt the user via a local notification to re-engage the app.
**Edge Case (Manual Revocation):** Given a service disconnect, the Confidence-Level Tamper Model uses Settings.Secure queries to verify package presence and initiates quarantine if absent.

### 24. Data Sovereignty Portal

**Requirement:** The system shall provide an offline, user-accessible portal to execute Right to Access and Right to Deletion under Kenya's Data Protection Act.
**Access Path (Export):** When the user requests a copy of their behavioral history, the system shall decrypt, serialize, and export their SQLCipher database as a password-protected JSON/CSV package within 5 seconds.
**Deletion Path (Purge):** When the user clicks "Permanent Purge", the system shall execute crypto-shredding by securely deleting the unique encryption key from the hardware-backed keystore, rendering all historical telemetry instantly and permanently unreadable locally — and shall trigger the server-side deletion flow in Req 29.

### 25. Escape Hatch Integration

**Requirement:** The system shall integrate a Deterministic Offline Panic Recovery mechanism for emergency administrative bypass.

### 26. Cryptographic Installation Footprints

**Requirement:** The system shall mandate a unique installation_id for every instance to prevent state conflicts in cloned environments and ensure event integrity across device lifecycles.

### 27. Sync Queue Backpressure, Eviction & Idempotency

**Requirement:** The system shall enforce a 1,000-event cap on the local sync queue; when the limit is reached, it shall apply a prioritized eviction policy, retaining critical relapse and tamper telemetry while discarding low-priority periodic heartbeats.
**Idempotency:** Every event carries a client-generated event_id; the server rejects duplicates on (installation_id, event_id). This prevents double-delivery of the same event after an offline retry (e.g., a network blip mid-batch), which would otherwise corrupt partner alerts and streak/milestone projections. Implemented as a UNIQUE constraint on the DomainEvent table (Entity 7).

### 28. Hardware-Backed Key Escrow & Device Migration

**Requirement:** The system shall protect local data with a hardware-backed Android Keystore key and support device migration via a user-controlled recovery mechanism.
**Decision (replaces the earlier unspecified "split-key protocol"):** The SQLCipher passphrase is encrypted at runtime by a non-exportable, hardware-backed Android Keystore key (ADR#9). For migration/recovery, the app exports an encrypted escrow copy of the passphrase, protected by a user-chosen recovery phrase (the standard "encrypted backup + recovery phrase" pattern used by mainstream messaging apps). The backend holds only a signed verification blob — it never holds the raw key or the passphrase, so a server compromise cannot decrypt user history.
**Trade-off:** The user must remember their recovery phrase; losing it after a device change means the local history cannot be restored (accepted — mirrors the trade-off of hardware-bound keys). No custom threshold-secret-sharing scheme is implemented (see Scope Triage).

### 29. DPA Deletion & Backup Protocol

**Requirement:** The system shall implement Kenyan DPA-compliant data deletion which includes explicit Auto Backup exclusions for sensitive behavioral data.
**Data Lifecycle (reconciled with the "immutable" server history — the audit's C4):**
* **Local retention:** Raw behavioral payloads (urge logs, block attempts, tamper warnings) are purged after 14 days. Aggregate counters required for milestones and streaks (current_clean_days_streak, total_relapses, milestone history) are NOT "behavioral logs" — they are retained locally for the life of the account because the milestone feature requires them.
* **Server retention:** Raw signed events are retained up to 12 months, then reduced to anonymized aggregates (counts only, no timestamps or identifiers) that carry no personal data. Basis: Section 25(g) — retention no longer than the purpose requires; partner dashboards and milestone verification define the purpose.
* **Deletion on request:** When a user invokes Right to Deletion, the server crypto-shreds the user's raw event records (deleting the per-user server-side encryption key so payloads are unrecoverable), and the partner-facing historical projections of that user are shredded as well. "Immutable, tamper-evident" applies while data is retained (signed events prevent *undetectable alteration*); it does not mean *un-deletable* — deletion is a stated design feature, not an omission.

### 30. Event-Driven System Keepalives

**Requirement:** The system shall use event-driven keepalives to monitor background service health and detect unauthorized process termination.

### 31. Package Querying Limitations

**Requirement:** The system shall respect OS-level broad package querying limitations, targeting only user-selected restricted package IDs.

### 32. Pre-Block Warning Notifications

**Requirement:** When a dynamic usage limit is active, the system shall calculate remaining access time in real-time and dispatch high-priority notifications to warn the user 10 minutes and 5 minutes before the block overlay activates.
*   **Happy Path (Standard Limits):** Given a restricted application daily limit of 30 minutes (1,800,000 ms), when the user's combined usage reaches exactly 20 minutes (leaving 10 minutes remaining) and again at 25 minutes (leaving 5 minutes remaining), then the background service shall push a local system warning notification (e.g., "10 minutes remaining before Instagram is blocked").
*   **Edge Case (Active Foreground Use):** Given the user is actively inside the restricted app when a warning threshold is crossed, then the system shall display a high-priority "Heads-Up" notification to ensure visibility over the active window layout.
*   **Edge Case (Micro-Limits):** Given the daily limit is set to a short duration (e.g., less than 10 minutes total), when the threshold is evaluated, then the system shall programmatically skip the 10-minute warning and instead scale the warning alerts to trigger at 50% and 10% of the total limit remaining.

### 33. Rollback Protection

**Requirement:** The backend shall enforce a minimum_supported_client_version check; any client attempting to sync data or access the partner dashboard using an outdated or rollbacked version shall be rejected and prompted to update.

### 34. Partner Dashboard Cache TTL

**Requirement:** The partner dashboard shall implement a 15-minute Time-To-Live (TTL) for all cached behavioral data to ensure that accountability partners are reviewing current habit metrics and not stale telemetry.

### 35. Partner SOS Signal

**Requirement:** The system shall provide a single-tap SOS action (a floating action button on the restricted user's Home) that sends the paired accountability partner a quiet "reach out to me" signal for moments of craving or relapse risk — no typing required from the user.
**Happy Path:** Given a paired user is struggling, When they tap SOS, Then the partner's device receives a gentle check-in notification and the user's app confirms it was sent.
**Copy direction (user-set 2026-09-13, "chill, subtle"):** the user's own framing ("needs you to reach out... at risk of relapse") is the intent; the shipped wording should stay warm and low-stakes rather than clinical or alarmist — e.g. "Sam could use a quick check-in." Final copy is an open product decision (Open Product Decisions #7).
**Edge Case (Offline):** Given the device is offline, When SOS is tapped, Then the signal is queued and delivered on reconnect, and the in-app confirmation honestly states it is waiting for a connection. Rate-limit: one signal per ~10 minutes; a repeat tap inside the window asks "send another?" — accidental presses must not spam the partner.
**Dependencies (flagged — SOS is not a small feature given the current state):** (1) partner pairing has no shipping UI (see Req 6/16 status) — there is nothing to deliver to yet; (2) **no push path exists in code** — no FCM service or sender; the mailbox is a 15-minute pull and periodic WorkManager does not run on the XOS fleet device (ADR #27); timely delivery is an architectural decision, see Open Product Decisions #7; (3) if the copy names the user, name capture must exist first — anonymous auth today, and nothing populates the schema's `name` column.
**Implementation status (2026-09-13): not built — spec added from user direction; plan pending (should be planned together with the partner pillar, or at minimum after the delivery-channel decision).**

### 36. Home-Screen Widget

**Requirement:** The system shall offer an Android home-screen widget giving the restricted user a glanceable, honest status plus one-tap access to help — designed for the urge moment, where speed to recovery matters most.
**Content (draft for user confirmation):** (a) days-free streak numeral; (b) live protection state (e.g. "Blocking now" / "N rules armed") computed by the same engine evaluation the Home screen uses (`BlockDecisionEngine.blocksNow` — no UI-side re-derivation of schedule semantics); (c) one-tap deep links: open the app, and "Manage urge" straight to the Recovery tab. Read-only over existing local state — no new data, no network, no sync.
**Edge Case (staleness):** Given widget hosts refresh on their own schedule, When state changes (streak increment, rule expiry, block end), Then the widget refreshes at a modest cadence (30-minute floor is the platform norm) and never shows a state it cannot back — a stale widget must understate, not overstate, protection.
**Constraints:** adds `androidx.glance:glance-appwidget` (new dependency) + manifest receiver; size variants (minimum 2×1 streak-only; 4×2 streak + status + action); Desert Dawn light/dark. Device verification must include the Transsion/XOS launcher — the only fleet device.
**Implementation status (2026-09-13): not built — spec added from user direction; content/sizing to confirm (Open Product Decisions #8).**

## Scope Triage (Solo-Developer Feasibility)

Decisions that keep V1 buildable by one non-expert developer in 3-6 months. These are deliberate simplifications, not scope cuts to features — each replaces a high-complexity mechanism with a boring alternative that meets the same requirement:

1. **Key escrow:** Custom split-key/threshold-secret-sharing scheme → Android Keystore + recovery-phrase escrow (Req 28). No custom cryptography.
2. **Server projections:** Full event-sourcing/CQRS (ADR#11) → simple server-side counters updated idempotently from the event stream. Full event replay/audit-trail is deferred; signed events still prevent undetectable tampering of the counters.
3. **Accessibility footprint:** Broad content-reading (URL bars, Settings app, split-screen enumeration) stays, with a narrow-scope fallback — window-state events only + local VPN (VpnService + DNS filtering) for web blocking — kept as a technical fallback if the broad reads prove fragile across OEMs. (Revision 2026-08-24: the "Play-policy risk / Policy Validation gate" framing is removed — no Play distribution, ADR#15.)
4. **APK update verification:** ADR#10's signed-manifest + SHA-256 verification stays (supply-chain risk is not negotiable for direct distribution), but the implementation is restricted to the existing documented pattern — no new crypto invented.
5. **Cloud:** Firebase free tier only for V1. No custom server, no VPS (ADR#8 pending — see Open Research Questions).

## Non-Functional Requirements (Updated)

These define the qualities the system must have while performing the functions above. Each is paired with a measurable target.

| Requirement Area | Specification |
| ----- | ----- |
| Performance (Speed) | **Android:** end-to-end detection-to-overlay interception <1000 ms (p99), with overlay draw <100 ms (p99) once triggered. **iOS:** interception rendered by the OS (Screen Time shields); app-side latency is zero and end-to-end is bounded by one OS frame (<16 ms). Both numbers are validated by the feasibility spike. |
| Compatibility | The system shall support iOS and Android using native OS languages (Swift/Kotlin) to directly access Screen Time and AccessibilityService APIs. (Note: React Native has been removed as a requirement based on feasibility research). |
| Reliability (Offline Support) | The system shall enforce active restriction periods and display the block screen with 100% functionality when the device has zero network connectivity. |
| Security (Data Protection) | The system shall encrypt all sensitive behavioral user data (specifically the "urge logs") at rest and in transit. |
| Usability Security (Overlay Protection) NFR-S10: The system shall implement setFilterTouchesWhenObscured(true) for all block overlays to prevent tapjacking and unauthorized interaction via malicious third-party overlays.  | The system shall require a maximum of 1 tap for the user to access recovery resources from the active block screen. |
| Maintainability | The system shall have all major architectural decisions (like tech stack or database schema) explicitly documented using Architecture Decision Records (ADRs). |
| Performance (CPU) | Foreground checking and layout parsing must maintain an average CPU utilization of **less than 2%** — a target, not an assertion; validated by the feasibility spike (which also determines the fallback if unachievable: narrow the Accessibility footprint). |

# Out of Scope (for future V2)

| Category | Feature | Why it is Out of Scope |
| ----- | ----- | ----- |
| Gamification & Timers | "Do nothing" timer; pixel animation world. | Adds significant frontend complexity and long-term asset development not needed for V1 core loop. |
| Complex Blocking Modes | Easy/Medium modes; Immutable blocks. | V1 prioritizes the friction timer/urge capture flow to prove the behavior change model first. |
| Accountability Partner Features | Bypass requests; Partner response choices. | V1 uses a default sharing state to simplify architecture and minimize real-time sync requirements. |
| Historical Data Undo | Retroactive deletion of previously shared partner data after ending a partnership. | Revoking sharing permissions only stops *future* syncs; already-shared data stays visible to the partner. **Exception (mandatory):** a user's DPA Right to Deletion request overrides this — the server shreds the partner's copy of that user's history per Req 29. |
| iOS Client | iOS subsystem (Screen Time APIs). | Deferred to V2 pending the Family Controls Distribution Entitlement decision. |

# Planning

## Project Decomposition:

# (System → Feature → Epic → Task)

1. Project Decomposition Hierarchy
**System:** MyExodus. (Note: V1 Development is strictly focused on the Android Client Subsystem. The iOS Client Subsystem is a known future requirement mapped to V2, pending Apple Entitlement approval).

* **Feature 1: User Identity & Pairing**
  * **Epic 1: User Accounts & Authentication** (Tasks for this epic will be decomposed just before it is prioritized for development, to prevent stale requirements).
* **Feature 2: Active Restriction & Time-Range Blocking**
  * **Epic 2: The Core Blocking Engine (Android V1)**
    * **Task 2.1:** Setup Local Database Schema (SQLCipher-encrypted tables for rules, target apps, web domains, and expiration dates, including installation_id tracking). Single canonical schema — see the ER Model below.
    * **Task 2.2:** Implement the AccessibilityService foreground-detection service (TYPE_WINDOW_STATE_CHANGED) that triggers interception overlays within the <1000 ms end-to-end target.
    * **Task 2.3:** Implement monotonic elapsedRealtime tracking to accurately monitor foreground usage and detect when daily time-limit quotas (e.g., 2 hours) are hit, resilient to clock manipulation.
    * **Task 2.4:** Implement UsageStatsManager polling service for usage-tracking data (daily limits) and accuracy verification.
    * **Task 2.5:** Build the SYSTEM_ALERT_WINDOW full-screen block overlay UI and the logic that routes the user back to the home screen.
    * **Task 2.6:** Implement the Clock State Machine with the tamper disambiguation rules (timezone/DST/resync vs. rollback) from Epic 2 Step 7.
    * **Task 2.7:** Implement SQLCipher crypto-shredding logic to securely rotate or delete local encryption keys upon DPA-compliant purge requests.
* **Feature 3: Emotional Friction & Support**
  * **Epic 3: Automated Interception Analytics**
    * **Task 3.1:** Build the Recovery Resource UI with un-skippable friction timer, willpower-adaptive content mapping, and integrated Interception Escape Hatch.
    * **Task 3.2:** Implement the automated "Block Attempt" counter logic and database update triggers.
* **Feature 4: Progress Measurement**
  * **Epic 4: Habit Tracking & Milestones** (Tasks for this epic will be decomposed just before it is prioritized for development).
* **Feature 5: Social Accountability**
  * **Epic 5: Partner Integration & Alerts** (Tasks for this epic will be decomposed just before it is prioritized for development).

By writing it this way, we satisfy the principal: Work that doesn't trace to a goal is scope creep with a ticket number. Every single technical task in Epic 2 traces directly up to Feature 2, which traces up to your System.

## Milestones

* **Milestone 1: The Core Blocking Engine (Device-Level)**
  Deliverable: A functional Android build where you can set specific time-ranges (Bedtimes), total block durations, and daily usage limits (e.g., 2 hours). The app successfully uses WorkManager and UsageStatsManager to track these rules and instantly draws the full-screen block overlay when a rule is violated. The blocks are enforced at the local device level and remain active even if the user logs out or switches profiles.
* **Milestone 2: Automated Interception & Recovery Flow**
  Deliverable: From an active block screen, the system automatically increments the "Block Attempt" counter. The user can tap 'manage urge' to view the recovery resource screen, which now displays willpower-adaptive suggested activities based on the time of day, persistent partner motivational messages, their custom coaching action steps, and a direct Escape Hatch to launch the local MyExodus app privately. The system natively routes them back to the device's home screen after the un-skippable 3-to-5 minute countdown timer expires.
* **Milestone 3: Habit Tracking & Streaks**
  Deliverable: The system successfully monitors absolute time to increment the "days free" counter after a full 24-hour period without a relapse. The app successfully triggers a local milestone celebration notification (e.g., 7 days) and accurately resets the streak if a relapse is logged.
* **Milestone 4: Dual-Role Cloud Identity, Partner Sync & Alpha Release**
  Deliverable: Two test devices can successfully pair. A single authenticated account can function simultaneously as a restricted user and an accountability partner. When the user logs an urge or reports a relapse, the data syncs to the cloud and triggers a push notification to the partner. The partner can open their app to successfully view the high-level usage analytics dashboard.

# Modeling

## ER (Entity Relationship) — Canonical Schema (single source of truth)

**Note:** This is the consolidated, canonical data model. Earlier drafts of this document contained two or three divergent copies of several entities; they have been merged here and this section is the only authoritative version. No other copy of any entity exists in this document.

### Entity 1: UserProfile

**Attributes:**

* **user_id**: Primary Key (UUID)
* **name**: String
* **email**: String
* **phone_number**: String (Nullable; optional for recovery)
* **date_of_birth**: Date (Required for the 18+ age gate; self-reported and NOT identity-verified — see Regulatory Considerations, Children's Data Safeguards)
* **has_accessibility_permission**: Boolean (Required for Android overlay/blocking)
* **has_usage_stats_permission**: Boolean (Required for tracking daily limits)
* **has_vpn_permission**: Boolean (Required for web-based blocking via local VPN)
* **has_notification_permission**: Boolean (Required for partner alerts)
* **has_device_admin_permission**: Boolean (Opt-in; all builds — Req 22; the directRelease-only scoping was removed with the Play flavor split, ADR#6/#15)
* **installation_id**: Unique UUID generated on first boot per device instance (distinguishes cloned/multi-device environments; used in the sync idempotency key)
* **device_public_key**: Used for secure, hardware-backed sync verification
* **recovery_escrow_key**: Encrypted blob used for restoring habit history on new hardware instances (see Req 28)

**Relationships:**

* **One-to-Many** with **RestrictionRule**: One user can block multiple specific apps or sites.
* **One-to-Many** with **Partnership**: One user can be involved in multiple partnerships.
* **One-to-Many** with **HabitProgress**: Tracks individual progress for different habits (e.g., "Instagram" and "Betting Sites") completely separately.

### Entity 2: Partnership (Handles the dual-role requirement and consent)

**Attributes:**

* **partnership_id**: Primary Key (Unique identifier for this specific pairing)
* **restricted_user_id**: Foreign Key (Points to the user_id of the person being monitored)
* **accountability_partner_id**: Foreign Key (Points to the user_id of the person acting as the partner)
* **status**: Enum/String: Pending, Active, Rejected, Terminated (Crucial for proving the user explicitly consented to the partnership, as required by Kenya's Data Protection Act)
* **created_at**: Timestamp (To log when the partnership was initiated)
* **terminated_at**: Timestamp, Nullable (Set when status becomes Terminated; triggers the partner-history visibility rules below)

**Relationships:**

* **Many-to-One** with **UserProfile** (A user can be the restricted user in multiple partnerships).
* **Many-to-One** with **UserProfile** (A user can act as an accountability partner for multiple different people).

**Partner data visibility rules:** Ending a partnership stops all *future* syncs to that partner. Already-shared historical data remains visible to the partner unless the restricted user invokes Right to Deletion, which shreds the partner's copy too (Req 29). The UI must make this behavior explicit when a partnership ends (pre-mortem item 13).

### Entity 3: RestrictionRule

**Attributes:**

* **rule_id**: Primary Key (Unique identifier for this specific block)
* **user_id**: Foreign Key (Links back to the UserProfile table)
* **target_type**: Enum/String - 'APP' or 'WEBSITE'
* **target_identifier**: String - The exact Android package name e.g., 'com.instagram.android', or URL e.g., 'instagram.com' (must be ASCII; the app's own package, com.example.myexodus, is always bypassed — Rule 4 in Epic 2)
* **is_always_on**: Boolean - True if they want a strict 24/7 block on this target
* **daily_limit_ms**: Long (Milliseconds for high-precision enforcement)
* **time_range_start**: Time, Nullable - E.g., 22:00 for a bedtime block
* **time_range_end**: Time, Nullable - E.g., 06:00 for when the bedtime block lifts
* **active_days_of_week**: String/Array - E.g., [Mon, Tue, Wed, Thu, Fri] so users can have different rules for weekends
* **block_expires_at**: Timestamp (REQUIRED - This enforces the rule that blocks cannot be infinite)

**Relationships:**

* **Many-to-One** with **UserProfile** (One user can set up multiple rules for different apps/sites).
* **One-to-Many** with **DailyUsageTracker** (One rule will have many daily trackers over time to enforce the daily_limit_ms).

### Entity 4: DailyUsageTracker

**Attributes:**

* **tracker_id**: Primary Key (Unique identifier for this specific day's record)
* **rule_id**: Foreign Key (Links directly back to RestrictionRule)
* **date**: Date (e.g., 2026-07-21)
* **ms_used_today**: Long (Current high-precision usage tally, tracked via the monotonic clock)

**Relationships:**

* **Many-to-One** with **RestrictionRule** (One restriction rule, like "Limit Instagram", will generate many daily tracker records over time — one for each day).

### Entity 5: HabitProgress

**Attributes:**

* **progress_id**: Primary Key
* **user_id**: Foreign Key (links back to UserProfile)
* **target_identifier**: String (e.g., 'Instagram' or 'Betting Sites')
* **current_clean_days_streak**: Integer
* **total_relapses**: Integer
* **access_attempts_count**: Integer (Automatically increments when the block screen is hit)
* **habit_duration_years**: Integer (Saves the user's estimated history with this habit to calculate withdrawal severity)
* **onboarding_severity_level**: Enum (e.g., 'MILD', 'MODERATE', 'SEVERE' based on frequency of use)
* **last_relapse_timestamp**: Timestamp, Nullable (Crucial to calculate exactly where they are on their weekly withdrawal timeline)
* **share_access_attempts**: Boolean
* **share_relapses**: Boolean
* **share_milestones**: Boolean
* **assigned_partnership_id**: Foreign Key, Nullable (Links this specific habit to a partner. If left empty, no alerts are sent!)

**Relationships:**

* **Many-to-One** with **UserProfile**
* **Many-to-One** with **Partnership**

### Entity 6: CoachingStep (Stores the user's custom recovery actions)

**Attributes:**

* **step_id**: Primary Key (Unique identifier)
* **user_id**: Foreign Key (Links back to UserProfile)
* **step_order**: Integer (The sequence of the step, e.g., 1, 2, 3)
* **action_text**: String (The custom instruction, e.g., "Read a Bible verse" or "Go for a walk")
* **associated_trigger_window**: Enum (e.g., 'ANY_TIME', 'LATE_NIGHT_ONLY' to align with the willpower-adaptive logic)

**Relationships:**

* **Many-to-One** with **UserProfile**: One user can have multiple sequential steps in their coaching plan.

### Entity 7: DomainEvent (The Local Sync Queue)

**Attributes:**

* **event_id**: UUID (Client-generated; part of the idempotency key with installation_id)
* **installation_id**: UUID (Associates event with specific instance; UNIQUE constraint on (installation_id, event_id) — Req 27)
* **event_type**: Enum (e.g., RELAPSE, BLOCK_ATTEMPT, MILESTONE, HEARTBEAT, TAMPER)
* **payload**: Encrypted Blob
* **sync_status**: PENDING/SYNCED
* **created_at**: Timestamp (also drives the 14-day local retention purge — Req 29)

**Note:** The local sync queue is capped at 1,000 events (Req 27). The server dedupes on (installation_id, event_id) and rejects duplicates. The earlier draft entity with only event_id/payload/sync_status (no installation_id, no idempotency) is superseded by this canonical version.

### Design Rationale: Granular Partner Sharing

By explicitly tying specific partners to specific blocks, we directly satisfy the privacy requirements from the compliance research, which states that "sharing should be opt-in and granular" and that users must be able to control exactly what their partner can see. This solves both of the edge cases:

* If they don't have a partner (Solo Mode): The link is just left empty (Null). The app still gives them the friction timer to break their urge, but the system knows not to send any alerts.
* If they have multiple partners: The alert goes only to the specific partner assigned to that exact habit, keeping everything else private.

This is implemented via the single column (assigned_partnership_id) on Entity 5 (HabitProgress).

**Backend Compliance Strategy:** To satisfy the DPA, the system utilizes a single managed Firebase backend. Compliance is intended to be established via Standard Contractual Clauses (SCCs) and Data Processing Agreements (DPAs) with Google. **Open question:** whether a solo developer's Firebase terms satisfy the Sections 48-49 burden of proof, and whether a Nairobi serving copy (ADR#8) is required — see Open Research Questions. The earlier sentence in this document asserting "without requiring a custom Nairobi VPS replica" conflicted with ADR#8's accepted status; ADR#8 is now marked Pending and this paragraph reflects that.

# Feature Engineering

## Epic 2: The Core Blocking Engine (Android V1)

## Phase A: Framing Note

1. **Step 1. The Feature in Our Own Words:** This feature is the background engine that enforces time-range schedules, specific days of the week, total block duration expirations, and daily usage limits. It actively listens for a restricted app or website to open (via AccessibilityService window-state events, and/or local VPN) and instantly intercepts the user with a full-screen block overlay via SYSTEM_ALERT_WINDOW.
2. **Step 2. The Business Goal:** This directly serves our core Job-To-Be-Done: "Help users stop compulsive phone use, avoid risky content, and stay accountable to a human partner". Its specific success metric is the Interception Latency Limit — end-to-end detection-to-overlay interception in <1000 ms (p99), with overlay draw <100 ms (p99) once triggered, at 100% offline functionality.
3. **Step 3. The Stakeholders Affected:**
   1. **The Solo Developer:** Affected by strict constraints; must build this using "boring" technology (SQLite/Kotlin) to ensure it can be maintained by one person within a 9,000 KES budget and a 3-6 month timeline.
   2. **The Restricted User / Early Testers:** Highly affected by the engine's reliability; if the block fails offline or has high latency, the friction fails and they succumb to their urge.
   3. **Accountability Partners:** Affected by the engine's alert routing; they rely on this engine to accurately queue and batch bypass attempts and relapse alerts to their dashboard without spamming them with notifications.
   4. **Regulators (Kenya DPA):** Affected by how this engine handles data. Because the engine tracks sensitive behavioral data (app usage and urges), it must enforce granular consent (only sharing with specific partners) and local encryption to comply with Kenyan privacy law.
   5. ~~**Google Play Store Reviewers:** Have the power to block the app's release~~ — **REMOVED (revision 2026-08-24): not applicable — the app is not published on Google Play (ADR#15).** The trust burden shifts to direct-distribution users, who must decide to enable "install unknown apps" and verify the signed APK (ADR#10); this is reflected in the onboarding UX.

## Phase B: Feature Definition (Steps 4-12)

### Step 4. Define Business Rules (Decision Matrix)

The local background blocking engine evaluates rules in real-time completely offline using the local Room SQLite database. For any target app package or URL request processed, the engine applies the following decision rules:

* **Rule 1 (Active Schedules):** IF current device time falls within time_range_start and time_range_end AND current day is in active_days_of_week THEN Block Target app/website.
* **Rule 2 (Usage Quotas):** IF minutes_used_today (retrieved from UsageStatsManager) >= daily_limit_minutes THEN Block Target app/website.
* **Rule 3 (Absolute Expiration):** IF current system timestamp > block_expires_at THEN Lift Restriction & Disable Blocking.
* **Rule 4 (Self-Blocking Safeguard):** IF target identifier equals com.example.myexodus THEN Programmatically Bypass Interception (the app must never block itself under any condition). Enforced by the allow-list in code AND a SQLite CHECK constraint (CHECK target_identifier != 'com.example.myexodus').
* **Rule 5 (Solo vs. Partner Active Mode):** IF assigned_partnership_id IS NULL THEN Enforce Solo Mode (runs purely locally; suppresses all cloud synchronization to Firebase). ELSE enforce Partner Mode (syncs encrypted telemetry logs to Firebase when online).

### Steps 5 & 6. State Model & Transitions

The blocking engine operates as a strict finite state machine (FSM) to maintain the Strict Block Rule (users are always natively forced back to safety after interacting with a blocked app).

* **State 1: Monitoring (Background Service):** The AccessibilityService listens for system window-state changes (TYPE_WINDOW_STATE_CHANGED); a local VPN additionally monitors web domain requests where enabled. Transition: The user launches a restricted package or opens a restricted host. If any rule in Step 4 is active, transition to State 2.
* **State 2: Intercepting (UI Overlay):** The engine immediately draws a full-screen, non-dismissible SYSTEM_ALERT_WINDOW overlay over the blocked target. It increments access_attempts_count in the local Room DB. Transition: Path A: User clicks "Open MyExodus Recovery" (Interception Escape Hatch) -> Transition directly to State 3. Path B: User clicks back or home -> System simulates the home button keypress natively. Transitions back to State 1.
  * **Implementation status (2026-09-13):** Path A now exists on the URL-block path. The overlay renders an interactive card naming the blocked site with "Open MyExodus Recovery" (starts the Req 13 recovery session for that rule, then routes to the Recovery tab) and "Go to a different site" (same browser, blank page; the blocked tab re-blocks on return). Attempt counting and NFR-S10's `filterTouchesWhenObscured` are wired on both overlay paths; the app-window cover renders the same card (since ADR #32 — app name as hero, the same single "Open MyExodus Recovery" action; only the different-site action stays URL-only). Path B is honoured natively — no keypress simulation: the card is bounded to the system-bar-free area of the display (the nav-bar gap was a device-caught defect fixed 2026-09-13, ADR #30), so the navigation bar stays visible and its touches never reach the card; Home/Back exit the browser normally and returning re-blocks. App blocks keep `BlockerActivity` (since ADR #31 it shows the same card content — app name as hero, the same single "Open MyExodus Recovery" action; since ADR #32 the app-window cover renders that same card too, so every block surface speaks one language); the URL intercept uses the accessibility overlay (ADR #21), mechanism unchanged.

* **State 3: Recovery Active (UI Dashboard):** The overlay triggers an un-skippable 3-to-5 minute friction timer countdown. It renders the adaptive recovery tools: Willpower-Adaptive Suggested Activities, Persistent Partner Messages, and Custom Emergency Steps. Transition: Friction timer expires AND user submits their encrypted urge log -> System natively routes them to the device's home screen and transitions back to State 1.
* **State 4: Tamper Lockout Quarantine (Solo-Only Background Lockout):** If a Solo user revokes Accessibility or Usage Stats permissions, the engine initiates a strict 15-minute lockout overlay. Transition: Quarantine timer expires OR the user re-enables permissions -> Transitions back to State 1. Reboots preserve this quarantine state by comparing CPU uptime clocks against the stored timestamp.

### Step 7. Identify Edge Cases & Boundary Conditions

* **The "Offline Time-Traveler" Clock Tamper:** If the user manually adjusts the system calendar past the restriction window while offline, the database compares the system time against both a stored last_known_timestamp and the CPU tick clock (SystemClock.elapsedRealtime()). If an invalid jump is detected, the engine flags a tamper attempt, locks the device, and queues a high-priority partner sync payload.
  * **Disambiguation (required to avoid false positives):** Legitimate timezone changes and DST transitions move the wall clock by whole timezone offsets while elapsedRealtime is unaffected — these MUST NOT trigger lockout. An NTP resync after being offline for days is also legitimate — apply a grace window (e.g., a wall-clock jump of <24h consistent with elapsedRealtime drift is treated as resync, not tamper). Tamper is flagged only for backward wall-clock jumps inconsistent with elapsedRealtime beyond the grace window, and forward jumps past block_expires_at inconsistent with elapsedRealtime.
* **Private DNS / DNS-over-HTTPS Browser Escape:** To prevent users from bypassing local VPN filters by turning on Private DNS in Chrome or Android Connections, the AccessibilityService actively parses active browser URL bars in real-time. If incognito or private browsing hides elements, the engine covers the browser entirely with an overlay instructing them to return to safe browsing. (Revision 2026-08-24: the "Play-policy risk footprint gated by the Policy Validation spike" parenthetical is removed — no Play distribution, ADR#15; the URL-bar parse remains part of the broad-read Accessibility footprint.)
* **Settings App Self-Sabotage Shielding:** If the user navigates to System Settings -> Apps -> MyExodus to click "Force Stop" or "Clear Data" during an active block schedule, the AccessibilityService immediately intercepts the Settings window (com.android.settings) and triggers the block screen, forcing them back home. (Same policy-risk gating as above.)
* **Device Admin "Hard Lock" Protection (opt-in, directRelease flavor only):** If Device Admin is enabled, standard uninstallation is blocked natively by the OS. If the user attempts to disable Device Admin, the app triggers an un-skippable 3-minute timer countdown and immediately alerts their partner.
* **Split-Screen Viewport Escape:** The AccessibilityService queries all active viewports via AccessibilityService.getWindows(). If a restricted package identifier is running in any split-screen viewport, the overlay immediately triggers and covers the entire screen, disabling split focus.
* **Cross-Device Rule Filtering (Infinix to Tecno):** When rules sync from Firebase to a new device, if a synced package name is unique to the old device and not installed locally, the system hides it from the user's active UI list to prevent clutter, while keeping standard global rules (like Instagram) blocked.

### Step 8. Identify Constraints

* **Solo Developer Maintainability:** Handled using native, well-documented, "boring" tech (Android Room SQLite DB, Hilt DI, Kotlin Flows, and SQLCipher) to ensure code can be fully managed within a 3-to-6 month timeline and a 9,000 KES budget.
* **Consumer-Level Hardware:** The app must run on standard consumer hardware without requiring Enterprise MDM enrollment or device-rooting privileges.
* **Foreground Persistence:** Enforcements are handled continuously via active background foreground services, running even if the user logs out or switches to the partner dashboard view.

### Step 9. Identify Risks

* ~~**Google Play Store Accessibility Approvals (High Risk):**~~ — **REMOVED (revision 2026-08-24): not applicable — no Play submission exists (ADR#15).** Replaced by the direct-distribution trust burden: users must enable "install unknown apps" and trust the signed APK verification (ADR#10); the in-app onboarding disclosures and privacy policy are retained as good practice, not as a store gate.
* **Aggressive OS Background-Process Killers (Medium Risk):** Highly customized Android distributions (like XOS on Infinix or HiOS on Tecno) aggressively kill background services to save battery. The engine must register as an Android Foreground Service with a persistent, active notification icon to prevent process termination. (Finding 2026-08-25, Infinix X6532/XOS: the proprietary native `hiber` daemon freezes app/test processes via the kernel freezer cgroup ~40s after start and re-freezes within ~3s of any thaw — `cmd activity unfreeze` cannot outrun it. AOSP freezer/app-hibernation are disabled; there is no adb-controllable kill switch. Whitelisting the app in XOS battery/app management (user's "unrestrict") takes effect immediately and stops the freezing. Instrumented test runs on this device require the app whitelisted first.)
* **"Stalkerware"-type classification (High Risk, distinct from ordinary policy scrutiny):** Reading another app's screen content in real time, intercepting the OS Settings app, and routing behavioral data to a third party ("partner") together overlap with patterns Play Protect and antivirus vendors classify as covert surveillance. Mitigations: persistent disclosure notification, clear onboarding disclosure of exactly what is shared and with whom, in-app partner-management UI (pre-mortem item 13). Severity of this risk must be validated by the Policy Validation spike step before launch.

### Step 10. Define Security Considerations

* **SQLCipher Local Encryption:** To comply with Kenya's DPA No. 24 of 2019, all sensitive behavioral telemetry (urge logs, block attempts, and tamper warnings) must be encrypted at rest on the local device using SQLCipher.
* **Granular Sharing Consent:** Telemetry must only sync to the cloud if the user has actively enabled granular permissions (e.g., share_access_attempts or share_relapses). If these are toggled off or if they are in Solo Mode, no cloud database payload is dispatched.
* **Rolling Data Retention Purge:** To prevent long-term privacy leaks, the local SQLite database must automatically run rolling delete scripts, wiping raw behavioral logs older than 14 days, while preserving the aggregate counters the milestone feature requires (streak, total relapses — see Req 29 for the full data lifecycle).

### Step 11. Define Performance Requirements

**ADR (Accepted) — Secure Direct-APK Update Verification Subsystem:** With no Play Store distribution (ADR#15), **every** user receives direct APK updates, so the native signature verification a store would have provided must be replaced by our own layer. To prevent malicious updates or MITM attacks, the app needs a secondary internal verification layer. The update manager will fetch a signed manifest and verify the new APK's SHA-256 hash against a public key pinned within the current installation before initiating the update intent. Trade-off accepted: increased release-pipeline complexity and manual key management; necessary to secure the supply chain for all users (not just "non-Play Store" ones — revision 2026-08-24). Consequence: users can safely update via direct download with the same cryptographic confidence as a store-mediated update.

* **The Interception Latency Limit (p99):** The overlay blocking screen must draw and obscure the restricted app within <100 ms (p99) of the interception trigger, and the full detection-to-overlay sequence must complete within <1000 ms (p99) — see the Domain Invariant and Technical Feasibility.
* **Battery and CPU Ceiling:** Foreground checking and layout parsing must maintain an average CPU utilization of less than 2% to prevent Android system warnings and forced uninstallation. This is a target validated by the feasibility spike; if the spike shows it is unachievable with the described footprint, the fallback is to narrow the Accessibility scope (window-state events only) and move web blocking to the local VPN.

### Step 12. Define Observability (Monitoring)

* **Cryptographic Permission Audit Trail:** Any programmatic shift in Accessibility, VPN, or Usage Stats permissions must instantly write an immutable, cryptographically hashed entry directly into the local DB audit table.
* **Keepalive Heartbeat Logging:** The background service logs regular, lightweight background heartbeats. If a user force-stops the service, the absence of a running heartbeat will be flagged by the sync manager on the next manual launch and recorded.

## Phase C: Specify and Freeze

### Step 13. Write the Specification Document (The 4-Gate Validation Pass)

The technical specification is validated against: Testability (via Given/When/Then criteria for monotonic enforcements — including the single, frozen <1000 ms end-to-end interception target), Consistency (Conflict resolution via Server-Side Authority), Traceability (Event-driven auditing), and Completeness (DPA protocols and build flavor mapping).

### Step 14. Peer Review & Critique

**Compliance Review:** The Kenyan DPA Data Deletion Protocol (local + server-side shred, Req 29) and localized serving copy question (ADR#8, pending) ensure the legal positions are stated accurately. **Technical Review:** Monotonic time tracking with tamper disambiguation and build flavors address Android-specific fragmentation and tamper risks.

### Step 15. The Formal Rules Freeze

All architectural boundaries and enforcement rules for Epic 2 are frozen as of August 8, 2026. Future changes require an Architecture Decision Record (ADR) and versioned amendment. (The audit-driven consolidation of 2026-08-24 — this revision — is itself an amendment to the frozen text: duplicated requirements and entities were merged into the canonical versions above, the latency target was unified, and the detection mechanism was corrected. Any further change requires an ADR.)

# Testing Strategy (V1)

* **Unit tests:** FSM transitions (States 1-4), clock-tamper disambiguation (timezone/DST/resync vs. rollback — Step 7), retention purge logic (14-day / aggregates), sync-queue eviction and idempotency key generation (Req 27).
* **Instrumented tests:** Room schema (canonical ER above) with SQLCipher; overlay draw latency measurement on physical devices; partner alert end-to-end (two devices). (Task 2.1 suite status 2026-08-25: all 8 SQLCipher+Keystore persistence tests green on Infinix X6532/XOS, including wrong-key open rejection — plan §17 acceptance criterion; WAL journal mode confirmed active on-device. Gotcha: Room creates the schema lazily on first DAO access — tests that inspect the raw connection must trigger a DAO call first.)
* **Device fragmentation matrix:** Physical devices on Infinix XOS and Tecno HiOS (known aggressive background killers) plus a stock-Android device — the background-service survival invariant (Step 8/9) is tested on all three.
* **Enhanced Mode (implemented 2026-08-26):** the AccessibilityService blocking engine per `app/plans/myexodus-enhanced-mode.implementation-plan.md` (ADR #17). Unit suite 93/93 green (normalizer, state machine, decision engine, emergency exclusion, publisher/repository, URL patterns, snapshot, latency profiler, overlay controller, URL scanner, health monitor, strict mode, settings). The §12 device matrix remains: life-safety, tapjacking, latency targets, freezer survival, cache consistency, stale events, multi-window, browser URL, AAPM unsupported-config — on Infinix XOS + two additional OEMs.
* **Blocking-core Policy Domain (gap-fill 2026-08-29, per `app/plans/myexodus-app-blocking-core-implementation-plan (1).md` §4/§6/§7.1):** plain rules store (myexodus_rules.db, Room) now owns the versioned policy — `policy_version` row (single id=0) bumped in the same DAO transaction as every rule write (failed writes never advance it; `RestrictionRuleDao.insertAndBump/updateAndBump/deleteAndBump`), snapshot publisher reads version from Room (no publisher-local counter). `recovery_policy` singleton row seeded 60/600 (default friction / override cooldown seconds). Per-rule explicit `timezone` column (default `'UTC'`; migration 1→2 adds it plus the two policy tables and seeds); the decision engine evaluates windows in the rule's zone with midnight-crossing + DST unit-tested (`BlockDecisionEngineScheduleTest`).
* **Blocking-core Recovery state machine (gap-fill 2026-08-29, plan §6 "Recovery/escape hatch" + §8.3):** dual-clock deadline persisted per recovery session in `recovery_state` (single-row, migration 2→3): `deadlineWallClockUtcMillis` (reboot-safe authority) + `deadlineElapsedRealtimeThisBoot` (same-boot authority, sub-second precision) + `ruleId` + boot-session id (elapsed-realtime sample; a fresh sample below it means reboot — `RecoveryTimer.sameBootSession`). `RecoveryCountdownTracker` freezes the display at the last known-good value and surfaces a time-change-detected state on detected wall-clock jumps (`RecoveryTimer.detectClockJump`), while the authoritative countdown keeps running so the escape hatch stays reachable. Escape-hatch overrides are always allowed (§10 invariant); `overrideCooldownSeconds` (600) gates forced-override frequency (`RecoveryStateStore.overrideEligible`). No in-memory tick counter is ever the source of truth. §12 tests: process-death recreation, simulated reboot, clock-jump freeze (`RecoveryTimerTest`, `RecoveryCountdownTest`, `RecoveryStateStoreTest`); migration 2→3 validated end-to-end against Room's generated v2 DDL (`AppRulesStoreMigrationTest`).
* **CI:** GitHub Actions running lint + unit tests on every push; instrumented tests gated on connected devices for release candidates.
* **Feasibility-spike gates:** The spike results update the frozen latency number and the CPU target before Epic 2 implementation starts. (Revision 2026-08-24: the "Play-policy risk assessment" gate is removed — no Play distribution, ADR#15.)

# Open Research Questions

1. **DPA legal review (counsel):** (a) whether the household-activity exemption (Section 51(2)(a)) applies to individual-to-individual partner sharing — this could change which obligations attach to the partner data flow specifically; (b) the DPO-appointment threshold for a single-developer entity; (c) **counsel confirmation of the Section 50 reading, verified against the Act's primary text on 2026-08-24:** Section 50 grants the Cabinet Secretary a *discretionary* power ("may prescribe") to designate certain processing categories — it is **not** a self-executing requirement that every controller of sensitive personal data maintain a local serving copy, and there is no evidence it has been invoked for consumer habit-recovery apps. On that reading ADR#8's mandatory-serving-copy premise fails and the recommended action is **REJECT** (Firebase-only stands, per ADR#8's revised Decision). Remaining counsel work: confirmation of this reading and identification of any narrower obligation that does apply — its conclusion closes ADR#8's Pending status; (d) whether the app's self-declared age gate satisfies Section 33's "robust age verification" (Regulatory Considerations).
2. **Cross-border transfer basis:** Whether Firebase's standard terms + SCCs actually satisfy the Sections 48-49 burden of proof for a solo developer (currently asserted as intended, not verified).
3. **Local VPN alternative:** Evaluate VpnService + DNS-layer filtering as a website-blocking mechanism vs. Accessibility URL-bar parsing on technical grounds — robustness across OEMs and battery cost — since the Play-policy motivation no longer applies (ADR#15; mentioned in Technical Feasibility and Step 7). **Prototyped and passing 2026-08-29 (ADR#19):** full-tunnel sinkhole spike (7 gates, 3 consecutive ALL=PASS on the Infinix X6532); open item remains multi-OEM verification when a second device exists.
4. **Monetization / sustainability:** No revenue model exists despite recurring infrastructure costs; V1's Firebase free tier + 9,000 KES total budget cover the build, but post-launch hosting continuity is an open question.
5. **Behavioral-science basis:** The un-skippable 3-5 minute friction timer is asserted as effective without literature on urge-surfing/delay-based craving interventions, and without considering backfire risk (frustration-driven disengagement).
6. **Kenya/Africa-specific research:** Only two global apps (Opal, Jolt) were evaluated; local context (M-Pesa billing expectations, connectivity profiles) is unexplored.
7. **Device Admin availability:** Whether Android's Device Administrator API remains functionally available for this consumer (non-MDM) use case at build time; the API has been progressively restricted for non-enterprise scenarios. (Revision 2026-08-24: "Play-permitted" removed — Play distribution doesn't exist, ADR#15; relevant to Req 22 and ADR#6.)

# V1 UI Build Status (2026-08-31)

* **Theme:** user-chosen **Desert Dawn** palette (light + dark schemes in `ui/theme/Color.kt`/`Theme.kt`; dynamic color deliberately disabled so the brand identity survives on Android 12+). Typography: **Work Sans** (data/UI) + **Fraunces** (hero numerals) — variable TTFs in `res/font/`, wired through the full Material type scale in `ui/theme/Type.kt`.
* **Icons:** `material-icons-extended` pinned at 1.7.8 — icons froze at 1.7.x and left the Compose BOM, so the pin is required (BOM-only reference would silently break); `gradle.lockfile` updated for the new artifact (lockAllConfigurations is on).
* **Home dashboard (`ui/home/HomeScreen.kt`):** status chip (Protecting / Blocker off from the health monitor), degradation banner (moved here from MainActivity — 4 branches: usage access, app stopped, notifications, overlay), days-free hero (Fraunces gold numeral, milestone ladder 7/30/90, Req 11/12), KPI cards (block attempts Req 9 + active rules, live DAO flows — no invented numbers), enhanced-mode controls (reuses the tested `EnhancedModeScreen`), partner card (honest solo state — Req 15 slot until pairing UI ships).
* **Shell (`ui/AppShell.kt`):** 5-tab NavigationBar (Home/Rules/Recovery/Streaks/Settings) + Add-rule FAB on Home; Rules/Recovery/Streaks/Settings/Partner show `ComingSoonScreen` stubs. MainActivity's old engine-testing UI was replaced by AppShell; the database health gate (Opening/RecoverableDataFound/DatabaseIssue/KeyUnavailable/Transient) is kept — it's failure-path handling, not test scaffolding.
* **Gates (2026-08-31):** 338 unit tests green (0 failures/errors), lint zero, `assembleDebug` green. Phase 1 real pages (Rules/Recovery/Settings) are the next workstream; onboarding and the partner UI (Milestone 4) follow.

# V1 UI Phase 1 — Rules / Recovery / Settings (2026-09-13)

Executed from `app/plans/myexodus-v1-blocker-implementation-plan-v2.md` as the **UI-page spec only** (the five deviations recorded at the top of that doc): shipped enforcement engine, schema, FGS, and `RecoveryTimer` untouched; no Google Play requirements (direct APK per ADR#15); recovery friction = V1 Req 13 countdown only (no breathing/typed stages).

* **Rules (`ui/rules/RulesScreen.kt`, `RuleEditor.kt`, `AppPicker.kt`):** list with live per-rule state from `BlockDecisionEngine.blocksNow` (same evaluation as enforcement), pause toggle per row (writes `is_enabled`, engine excludes paused rules from the snapshot), attempt counts from `attempt_log`, honest empty state. Editor covers Req 1/2/3/5: app target via launcher-app picker (a dialog, not a route, so editor state survives; real app icons), website target with validation + duplicate detection, Always-on vs time window (overnight supported), day presets + per-day chips, per-rule timezone with device-zone suggestion, expiry (Never or a date). Optimistic-write failures surface inline via `notSavedMessage` (§6.1/§9 copy); self-block / rule-cap / duplicate errors use the typed exceptions from the write path. Rules FK a profile row, so a single local `UserProfile` is bootstrapped idempotently on first launch (`RulesRepository.ensureActiveProfile`, fixed id — a racing double-insert fails on the PK).
* **Settings (`ui/settings/SettingsScreen.kt`, `settings/ProtectionChecks.kt`):** one row per mechanism, re-read on every ON_RESUME: accessibility, usage access, exact alarms (row hidden below API 31 — no permission exists there), Android battery optimization (opens the settings list; never claims to grant), OEM background restrictions (manufacturer-keyed deep link with App-info fallback — all OEMs via `Build.MANUFACTURER`, generic fallback for unknown; no API reports OEM state, so the row says so), display over other apps (Transsion-only, "not needed" elsewhere), enforcement integrity (StrongBox→Verified / TEE→Unverified / no key→Unavailable / read error→Failed — never "root detected"). Enhanced-mode controls stay in their own section below.
* **Recovery (`ui/recovery/RecoveryScreen.kt`):** app-side surface of the persisted recovery session (Req 13). Shows the live countdown for an active session by driving the shipped `RecoveryTimer`/`RecoveryCountdownTracker` (since ADR #31 this is the only countdown UI — `BlockerActivity` is a pure wall whose single action lands here; the frozen-on-clock-jump state lives here) — so a session started from either block surface, or one that survives process death, resumes here from the stored dual-clock deadlines. Override is cooldown-gated (`RecoveryStateStore.overrideEligible`) and logged to `attempt_log`; every unlock — countdown completion or override — runs through `unlockRule` (Rule 3 expiry via `blockExpiresAt`, session consumed via `clear()`) — no new exemption path. Session liveness uses the engine's own `blocksNow` evaluation, so a paused/expired/deleted/window-ended rule reads as idle instead of a countdown for a block that no longer exists. Idle state explains the mechanic — no invented numbers.
* **Interception Escape Hatch pause card (`blocking/BlockOverlayView.kt`, Phase 1.5, user-directed 2026-09-13):** V1-doc conformance on the shipped URL-block overlay, which until now was message-only — a user who landed on a blocked site had no in-overlay action, so the escape hatch the V1 doc requires (State 2 Path A / glossary / NFR "≤1 tap to recovery") did not exist. The overlay now renders an interactive card: it names the blocked site and offers **Open MyExodus Recovery** (starts the Req 13 session for that rule via `RecoveryStateStore.startRecovery`, then deep-links to the Recovery tab through `MainActivity.EXTRA_START_TAB`) and **Go to a different site** (`about:blank` in the same browser — the blocked tab re-blocks on return). Two further V1 gaps closed in the same pass: URL blocks now record to `attempt_log` (State 2 requires `access_attempts_count`; previously only app blocks did), and the overlay sets `filterTouchesWhenObscured` over an opaque background (NFR-S10, and the blocked page is no longer readable through the scrim). The app-window cover rendered the same card the same day (ADR #32) — app named as hero, the same single "Open MyExodus Recovery" action, no different-site action — so the cover is the whole wall (FGS-down case included) in the same block language, and it stays touch-consuming and opaque. **Enforcement unchanged:** no overlay-API re-architecture (plan v2 deviation 2 stands), no new service, no schema change. **Device-verified on X6532 (2026-09-13):** card renders with the site named; both actions reach their targets; returning to a blocked tab re-blocks; countdown completes → unlock; URL blocks record to `attempt_log` (rows verified in DB). The two device-caught persistence defects recorded at ADR #29 (browser window-state events lifting the card; mid-load text deciding Allow with no later event to re-block) and a nav-bar touch loss (a full-display card sat above the navigation bar and swallowed its touches) were root-caused and fixed the same day in a device-test sweep (ADR #30), then re-verified: 12 consecutive fresh loads stayed blocked with exactly one recorded attempt each, zero hidden-after-shown events, and the phone's own Home/Back buttons were visible and functional with the card up (real taps). The app block screen (`BlockerActivity`) was brought to the same card content the same day, user-directed — one block language across app and URL blocks, with the recovery action as the single escape (ADR #31). The app-window cover — the a11y instant bridge, and the whole wall when the FGS is down — was brought to that same card the same day (ADR #32); device-verified on X6532: the cover renders the card the instant the blocked app's window appears and hides once `BlockerActivity`'s window lands (the activity's Compose card then shows its own hint), the cover's own recovery button (tapped during the transient cover window, before any `BlockerActivity` start) opens the Recovery tab with the live session ("Blocked: AnkiDroid"), Back is swallowed on the block screen while Home always exits (real key events). The persistent FGS-down wall was staged the same day via a debug-harness suite that stops the service in-process and backgrounds the app task (the FGS stays down — `am stopservice` is refused for the non-exported service): the cover alone walled a restricted app (no `BlockerActivity` start, service count 0, cover held 20 s+ through the Hiber `float_window` event), its "Open MyExodus Recovery" opened the Recovery tab with the live 60 s session ("Blocked: com.example.myexodus.test"), and the FGS restarted ~65 ms after the MainActivity start — the cover-only → recovery → restore cycle verified end to end.
* **Deviations / notes (recorded, not silent):** (1) §11's `key = it.id` lands on section/card items rather than per-row — the Rules list groups rows inside one card per section, so rows are not lazy items; (2) the usage-access Settings row is an addition to §2's row list (app blocking cannot run without it); (3) §10's `FLAG_SECURE` on "Recovery and Settings screens" assumes per-screen activities — the shell is one Activity with tabs, so it cannot be applied per-tab and is not applied at all; (4) the vault workstream note's "recovery resources" has no spec anywhere and is not built; (5) seeded friction is `default_friction_seconds = 60` while Req 13 prose says "3-to-5 minute" — the shipped engine value stands and no UI copy prints a duration; (6) two scope decisions taken during the device pass are recorded at their requirements: Req 4 (daily limits) deferred, Req 6 (partner alerts) engine-level only with device-only override copy until a pairing UI ships.
