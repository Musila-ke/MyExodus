'use strict';
// Partner SOS pure decision logic (plan §8.4, FR-4/FR-5) — validation, Nairobi calendar
// math, cooldown/daily-cap decisions, nudge-idempotency classification. No Firestore, no
// clock reads: the caller passes `nowMs`, so every boundary is directly testable
// (`test/partner-sos-logic.test.js`). The callable wiring lives in ./partner-sos.js.

const NUDGE_COOLDOWN_MS = 10 * 60 * 1000; // FR-4: sliding, exactly-10-minutes is eligible
const NUDGE_DAILY_CAP = 12; // FR-5: the 13th accepted nudge in a Nairobi day is rejected
const NUDGE_ENVELOPE_MAX = 1024; // plan §8.4 input contract
const NUDGE_RETENTION_MS = 7 * 24 * 60 * 60 * 1000; // §4.3: expiresAt = createdAt + 7 days

// AD-09: the client generates UUIDv4 (`Crypto.randomUUID`) and it becomes a Firestore
// document id — shape-checked before it touches a path, same discipline as requestId.
const NUDGE_ID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const BASE64_RE = /^[A-Za-z0-9+/]+={0,2}$/;

const INPUT_KEYS = ['pairId', 'nudgeId', 'envelope'];

/**
 * §8.4: reject unknown fields, validate every value. Returns `{ok:false, reason}` with a
 * stable token (logged, never shown to the user).
 */
function validateSendNudgeInput(data) {
  if (data === null || typeof data !== 'object' || Array.isArray(data)) {
    return { ok: false, reason: 'INPUT_NOT_AN_OBJECT' };
  }
  const keys = Object.keys(data);
  if (keys.length !== INPUT_KEYS.length || !INPUT_KEYS.every((k) => keys.includes(k))) {
    return { ok: false, reason: 'INPUT_UNKNOWN_OR_MISSING_FIELDS' };
  }
  if (typeof data.pairId !== 'string' || data.pairId.length === 0 || data.pairId.length > 128) {
    return { ok: false, reason: 'PAIR_ID_INVALID' };
  }
  if (typeof data.nudgeId !== 'string' || !NUDGE_ID_RE.test(data.nudgeId)) {
    return { ok: false, reason: 'NUDGE_ID_INVALID' };
  }
  if (
    typeof data.envelope !== 'string' ||
    data.envelope.length === 0 ||
    data.envelope.length > NUDGE_ENVELOPE_MAX ||
    data.envelope.length % 4 !== 0 ||
    !BASE64_RE.test(data.envelope)
  ) {
    return { ok: false, reason: 'ENVELOPE_INVALID' };
  }
  return { ok: true };
}

// §8.4: "Daily key generation must explicitly use Africa/Nairobi; never depend on
// process.env.TZ". One formatter per concern, built once — Intl construction is not free.
const NAIROBI_DATE = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Africa/Nairobi',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});
const NAIROBI_CLOCK = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Africa/Nairobi',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23', // h24 would yield "24" at midnight and make the seconds math negative
});

function partOf(formatter, date, type) {
  return formatter.formatToParts(date).find((p) => p.type === type).value;
}

/** `YYYY-MM-DD` in Africa/Nairobi for the given instant (FR-5 daily-cap key). */
function dayKeyOf(nowMs) {
  const date = new Date(nowMs);
  return [
    partOf(NAIROBI_DATE, date, 'year'),
    partOf(NAIROBI_DATE, date, 'month'),
    partOf(NAIROBI_DATE, date, 'day'),
  ].join('-');
}

/** Seconds until the next Africa/Nairobi midnight — the daily-cap retry hint (§9.1). */
function secondsUntilNairobiMidnight(nowMs) {
  const date = new Date(nowMs);
  const secOfDay =
    Number(partOf(NAIROBI_CLOCK, date, 'hour')) * 3600 +
    Number(partOf(NAIROBI_CLOCK, date, 'minute')) * 60 +
    Number(partOf(NAIROBI_CLOCK, date, 'second'));
  return 86400 - secOfDay;
}

/**
 * FR-4/FR-5 acceptance decision against the `(pairId, senderUid)` rate state. Cooldown is
 * checked before the cap (plan §6.2 steps 15/16). Suppressed nudges consume both exactly
 * like delivered ones — this function runs before the block state can matter (FR-5).
 */
function decideNudgeAcceptance({ state, nowMs }) {
  if (state && typeof state.lastAcceptedAtMs === 'number') {
    const elapsed = nowMs - state.lastAcceptedAtMs;
    if (elapsed < NUDGE_COOLDOWN_MS) {
      return {
        allowed: false,
        reason: 'cooldown',
        retryAfterSeconds: Math.ceil((NUDGE_COOLDOWN_MS - elapsed) / 1000),
      };
    }
  }
  const dayKey = dayKeyOf(nowMs);
  const dayCount =
    state && state.dayKey === dayKey && typeof state.dayCount === 'number' ? state.dayCount : 0;
  if (dayCount >= NUDGE_DAILY_CAP) {
    return { allowed: false, reason: 'daily_cap', retryAfterSeconds: secondsUntilNairobiMidnight(nowMs) };
  }
  return { allowed: true, dayKey, dayCount: dayCount + 1 };
}

/**
 * §6.2 step 12: what an already-existing nudge document means for this request.
 * `replay` — same sender, same envelope: return accepted, touch nothing (T-F4/T-F5).
 * `sender_mismatch` — another uid reusing this nudge id (T-F7), permission-denied.
 * `envelope_mismatch` — same id under a different ciphertext (T-F6), invalid-argument.
 */
function classifyExistingNudge({ existing, senderUid, envelope }) {
  if (existing.senderUid !== senderUid) return { outcome: 'sender_mismatch' };
  if (existing.envelope !== envelope) return { outcome: 'envelope_mismatch' };
  return { outcome: 'replay' };
}

module.exports = {
  NUDGE_COOLDOWN_MS,
  NUDGE_DAILY_CAP,
  NUDGE_ENVELOPE_MAX,
  NUDGE_RETENTION_MS,
  INPUT_KEYS,
  validateSendNudgeInput,
  dayKeyOf,
  secondsUntilNairobiMidnight,
  decideNudgeAcceptance,
  classifyExistingNudge,
};
