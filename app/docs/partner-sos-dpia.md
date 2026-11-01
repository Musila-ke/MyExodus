# Partner SOS ("Reach Out") — Kenya DPA Data Protection Impact Assessment (DRAFT)

**Status: DRAFT — parked for owner review (owner ruling 2026-10-03: "Draft it, park for review").** Companion paper to **ADR #133** (draft, server-visible metadata ratification). Plan §10.10 / §13.1 item 3. Nothing user-visible changes while the feature ships inert (kill switch off); this document is ratified, with ADR #133, before any rollout beyond internal testing (§13.2).

*Framework: Kenya Data Protection Act, 2019 (DPA) — sections 24 (duty), 30–31 (DPIA), 25–27 (principles), and the ODPC guidance posture. Scope: the Partner SOS feature only — the app's existing domain-usage/blocking processing is outside this document's scope.*

## 1. What the feature does

A paired user can send their accountability partner a one-tap "Reach out" nudge. The recipient's phone shows a local notification and an in-app card that names who needs the check-in; the card is a one-way signal — it offers no reply, and X dismisses it (owner ruling 2026-11-01, ADR #253). The sender is told only that the nudge reached the partner's device — never whether it was read or answered (FR-10). Either side can block incoming nudges at any time. The feature is server-mediated only because push delivery requires a server (see ADR #133).

## 2. Data flows

```
sender device ──callable (auth + App Check)──► Firestore (pairings/{pairId}/nudges + nudgePushQueue)
        ▲                                                    │
        │ accepted (no read state)                           ▼
sender device ◄──── nothing further              FCM (Google) ──► recipient device
                                                                      │
recipient device ──ack mutation (ackedAt) / block switch (nudgeSettings)──► Firestore
```

Two devices hold the pair CEK; the envelope is decrypted only on the recipient device. The server stores and forwards ciphertext.

## 3. Data inventory (server-visible, deployed 2026-10-03)

| Data | Where | Why (necessity) | Retention |
|---|---|---|---|
| `senderUid` / `recipientUid` (anonymous Firebase uids) | nudge doc | delivery target; suppression; membership check | 7 days (TTL, `expiresAt`) |
| `nudgeId`, timestamps (`createdAt`, `expiresAt`) | nudge doc | idempotency; TTL | 7 days |
| `envelope` (AES-256-GCM **ciphertext**, AAD-bound) | nudge doc | the message itself — **content never server-readable** | 7 days |
| `status`, `suppressedAt`, `ackedAt`, `terminalAt` | nudge doc | suppression-before-send; ack exactly-once; recovery | 7 days |
| `pushAttempts`, `lastPushAtMs`, `targetDeviceCount`, `sentDeviceCount`, `lastPushErrorCode` | nudge doc + queue doc | at-least-once dispatch lease, backoff, dead-lettering | 7 days |
| rate state (`dayKey`, `dayCount`, `lastAcceptedAtMs`, `lastNudgeId`) | `nudgeState` | abuse caps (per-partner cooldown, daily cap) | overwritten per send; deleted with pair |
| block state (`blocked`, `updatedAt`) | `nudgeSettings` | recipient's own control (§8.19) | until changed |
| `pushEligible` | push installation registry | deliverability routing | registry lifetime (pre-existing) |
| FCM payload metadata | Google FCM | transport (data-only wake, no content copy in the payload besides nudge id + type) | per Google FCM policy |
| operational logs (short hashes only — never uid/pairId/nudgeId/envelope in clear) | Cloud Logging | debugging, monitoring | 30 days |

**Not collected/visible:** message content (ciphertext only; CEK never leaves devices), contact lists, phone numbers, location, notification-open state (no "read" signal exists), the sender's or recipient's response choice (acks are not disclosed to the sender beyond "delivered").

## 4. Lawful basis and proportionality

- **Basis (draft view):** the recipient's and sender's **consent and voluntary pairing** — the feature is opt-in per nudge, partner pairing is explicit and revocable (unpair), the recipient can block at any time, and the sender can choose never to use it. The nudge is an expressive act by the sender, addressed to a person they chose.
- **Implication of recovery context:** the existence of a nudge implies an accountability relationship around behavior change. The metadata does not name the addiction domain, but the context is inferable to someone with server access (ADR #133 honest limit (b)). Minimization keeps the exposure to metadata; content confidentiality is cryptographic.
- **Proportionality:** every server-visible field is load-bearing for delivery, abuse resistance, or recovery — enumerated in ADR #133; nothing analytical, nothing for profiling, nothing sold or shared beyond the FCM transport.

## 5. Risks and mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Server operator reads nudge content | — | **None** (ciphertext) | E2E: CEK memory-only on the two devices; server holds ciphertext + AAD params |
| Server operator infers relationship/context from metadata | Low | Medium | Metadata-only by design; retention 7 days; ADR #133 records and ratifies the exposure |
| Cross-border processing (FCM/Google, non-Kenya servers) | Certain | Low–Medium | Disclosed in this DPIA + notice; data-only payloads minimized; no content |
| Notification content visible on a locked screen | Low | Medium | Channel `VISIBILITY_PRIVATE`; in-app card only (no content in tray beyond generic copy — fixed generic text by design) |
| Nudge used to harass | Medium | Medium | Per-partner cooldown + daily cap server-side; recipient block switch suppresses not-yet-sent nudges; X / acknowledge paths; report-free by design (unpair is the exit) |
| Device compromise (recipient's own device) | Low | Medium | App-level encryption at rest (SQLCipher), OS keystore binding — pre-existing app posture |
| Log leakage | Low | Medium | Hash-only log discipline (§9.4), grep-verified; 30-day retention |

## 6. Data subject rights (as implemented)

- **Access / portability:** the in-app data (habit, sessions, partner activity) is user-visible; SOS metadata is minimal and behaviorally visible (sent/received nudges, block state).
- **Erasure:** unpair shreds the pair keys (rendering stored ciphertext undecryptable) and the SOS documents age out via 7-day TTL; the block switch is user-controlled at all times.
- **Objection/withdrawal:** block incoming nudges (one switch); stop sending (never tap); exit entirely (unpair / app uninstall).
- **No automated decision-making** about users; rate limits are mechanical abuse caps.

## 7. Retention schedule (live)

| Data | Retention | Mechanism |
|---|---|---|
| nudge + queue documents | 7 days | Firestore TTL on `expiresAt` (ACTIVE, verified 2026-10-03) |
| rate state | overwritten per send | per-pair counters |
| operational logs | 30 days | Cloud Logging default retention for the project |
| block setting | until changed or pair ends | user-controlled |

## 8. Privacy notice — draft insert (for the in-app About/Partner surfaces when ratified)

> **Reach out (Partner SOS).** When you tap Reach out, a small encrypted message is sent to your partner's device through Firebase (a Google service). The servers see that a nudge happened and when — **never what it says**; the message is encrypted so only the two paired phones can read it. Nudges and their delivery records are deleted automatically after 7 days. Your partner never learns whether you read their nudge. You can block incoming nudges at any time in Settings; each of you can leave the pairing at any time, and leaving destroys the shared keys.

## 9. Sign-off (parked)

| Role | Name | Decision | Date |
|---|---|---|---|
| Project owner (data controller, solo project) | — | ☐ Ratify ADR #133 · ☐ Approve this DPIA · ☐ Approve the notice insert | — |

**On ratification:** lift the §8 copy into the About/Partner surfaces, reference ADR #133's status change, and re-run the plan §16 checklist rows (lines 2054, 2081).
