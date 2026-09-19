# UI/UX Improvement Session — Plan

**Status:** Draft for approval (user-requested 2026-09-18: "we also need a session for UI UX improvements cause bro its crap")
**Theme:** Desert Dawn (user-confirmed 2026-09-18) — no color-scheme change unless the user asks.
**Target device:** X6532 (physical, primary); emulator only for stock-Android checks.

---

## Method (standing rules apply)

1. **Research before building** (user standing rule): award-winning UI references + senior UI/UX guidance online before touching any screen; official Material 3 guidance where the question is a platform one.
2. **Screenshot audit first:** every surface × every state (loading / empty / error / success; light + dark) captured on-device and catalogued in the findings table below — no fixes from memory.
3. **Fix systematically:** one screen at a time, kept buildable; device-verify each with before/after screenshots.
4. **State + error rules already standing:** three-part error copy (what / why / action), UX timing thresholds (1 s / 10 s / skeleton), no backend internals on screen.

## Scope

All app surfaces: Home, Rules, Recovery, Streaks, Partner, Settings, all dialogs/sheets, onboarding wizard, block screen, widget, notification copy. (Partner UI assets not yet built — pairing screen, approval UI, unpair — are audited when they ship, not here.)

## Constraints

- **No IA changes without user sign-off** (e.g. moving Settings out of the bottom bar, merging tabs).
- **Bottom nav:** labels-on-selection-only is deliberate — 6 destinations, Material caps labeled bars at 5 (ADR #47). Do not re-add always-on labels; do not shrink them.
- **State semantics are ADR-pinned** — a visual pass must never soften or hide honesty copy (AD-05 staleness, chain-status distinctness) or permission banners.
- Existing tests must stay green; UI copy changes keep the string keys they replace.

## Findings (fill during the session — do not guess ahead)

| # | Screen | State | Issue (observed) | Severity | Fix | Status |
|---|---|---|---|---|---|---|
| — | — | — | (empty — populated from the screenshot audit) | — | — | — |

## Deliverable

Per-screen before/after screenshots + a summary entry in `Obsidian/MyExodus/MyExodus Progress.md`; any resulting ADR-worthy decision recorded in `app/docs/MyExodus Architecture Decisions.md`.
