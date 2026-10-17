
# Master Workflow — Full Pipeline, File-by-File

> **Revision 2026-10-17:** this is the project's original methodology document, retained as design rationale. The pipeline actually in force is defined by `CLAUDE.md` (plan-fidelity protocol): plans live in `app/plans/`, docs in `app/docs/`, and the AI implements against them. `AGENTS.md`, `system-failure-domains.md`, and `operations-and-incident-readiness.md` are not present in this repo — references below describe the original setup design, not current files. Gate 2.5's actual toolset is SonarQube Community, OpenGrep, gitleaks, and Trivy (OWASP Dependency-Check was evaluated and removed 2026-08-24 — see `MyExodus Build & Dependencies.md`).

## Your stack
- **Research + persistent memory:** NotebookLM — holds Deep Research per feature AND a separate "Project Memory" notebook (see below)
- **Architect:** Claude (Sonnet 5, free tier) — fresh session per feature, no reliance on chat continuity
- **Primary audit:** ChatGPT (5.6, free tier)
- **Optional independent spot-check:** Gemini Pro — high-stakes features only
- **Build:** DeepSeek in Antigravity
- **Deterministic checks:** SonarQube Community Build, OpenGrep, gitleaks, Trivy (Gate 2.5)

## The "Project Memory" notebook — solves the fresh-session problem
Claude sessions don't persist across features, and pasting full docs into every session burns tokens fast. Fix: create ONE separate NotebookLM notebook (not per-feature) and upload as sources: `engineering-decision-system.md`, `engineering-patterns-reference.md`, `system-failure-domains.md`, `operations-and-incident-readiness.md`, PRD, architecture doc. Before each Phase 3 Claude session, query this notebook: *"summarize what's relevant to building [feature name] from these documents — relevant patterns, current failure-domain entries, applicable operational requirements."* Paste that synthesis into Claude's fresh session instead of the full documents. Update the source files in this notebook (re-upload) whenever `system-failure-domains.md` or the patterns reference gets a new entry.

---

## ONE-TIME SETUP (do this once, not per feature)

| File | Goes where | Purpose |
|---|---|---|
| `engineering-decision-system.md` | Shared docs folder (Drive or repo `/engineering-docs/`) | Discovery machinery — feeds Claude at Phase 3 |
| `engineering-patterns-reference.md` | Same folder | Known-pattern library — feeds Claude at Phase 3, and Gate 2.5 tool list |
| `AGENTS.md` | **Project root in Antigravity** (not a per-feature file) | Loads automatically every DeepSeek session — never pasted manually again |
| PRD + architecture doc | Project root or repo docs folder | Existing-system context — referenced at Phase 3, not re-explained each time |
| Gate 2.5 tools (SonarQube, OpenGrep, gitleaks, Trivy) | Installed once, run via CI or locally per build | No AI involved — deterministic |

---

## PER-FEATURE FLOW

**Step 0 — Pick the feature, write the short description**
From your PRD, pick the feature. Write (or have DeepSeek draft, then you tighten) a ~2,000-character description: what it does, which PRD epic/section it belongs to, relevant functional/non-functional requirements, what existing architecture it touches.
*Do NOT reuse a 20,000-word spec here — short and specific is what NotebookLM's Deep Research needs to trigger correctly.*

**Step 1 — NotebookLM**
1. New notebook (or existing project notebook) → paste the Step 0 description into Deep Research → let it populate sources.
2. New chat message (separate from the Deep Research query) → paste: the short context header (feature name, "focus on this feature specifically") + `step1-notebooklm-compact.md` (or the full version if you're under the character ceiling that session).
3. Output: research dossier. Copy it out (Google Doc or plain text) — you'll hand this to Claude next.

**Step 2 — Claude (architect)**
1. New Claude conversation.
2. Give it: the research dossier from Step 1 + relevant PRD sections + architecture doc + `engineering-decision-system.md` + `engineering-patterns-reference.md`.
3. Paste the Phase 3 architecture prompt (from `step2-audit-and-planning-prompts.md`). Explicitly require: requirement challenge pass, assumption register, invariants, and the seven-state decision table (Required/Recommended/Optional/Deferred/Rejected/Unknown/N-A) — Rejected/Deferred need stated reasons.
4. Output: draft plan.

**Step 3 — ChatGPT (audit)**
1. New ChatGPT conversation (fresh session — don't reuse one with prior context on this feature, that breaks independence).
2. Paste: Claude's draft plan + the Phase 1 audit prompt structure, applied to the plan (not raw research) for your single-audit-pass setup.
3. Output: findings.

**Step 4 — Claude (reconcile)**
1. Back in the Step 2 Claude conversation (context intact here is fine, this isn't the independence-sensitive step).
2. Paste ChatGPT's findings. Ask Claude to accept, reject, or challenge each — with reasons — and produce the final document.
3. Output: `feature-implementation-plan.md` — the 18-section spec, save it to `/plans/<feature-name>/` in your project.

**When to add Gemini (optional, not default):** if the feature touches payments, auth, data integrity, or anything architecturally load-bearing, run Gemini as a second, blind audit of Claude's draft plan (Step 3, in parallel with ChatGPT, in its own fresh session, never shown ChatGPT's findings). Reconcile all three in Step 4. For everything else, ChatGPT alone is proportionate — don't default to three-way audit on every small feature; that's the tiering principle from earlier, not a downgrade.

**Step 5 — Hand off to DeepSeek**
1. `feature-implementation-plan.md` is already in `/plans/<feature-name>/`.
2. Tell DeepSeek in Antigravity: "Build this feature. Plan is at `/plans/<feature-name>/feature-implementation-plan.md`." `AGENTS.md` is already loaded from setup — nothing to paste.
3. If DeepSeek flags something mid-build, check which of the five escalation categories it's using (per AGENTS.md). "Implementation detail" — let it decide and move on. Any of the other four — that's a real flag, goes into Gate 3, not dismissed and not stalling you in the moment.

**Step 6 — Gate 2.5 (deterministic, no AI)**
Run SonarQube/OpenGrep/gitleaks/Trivy against the diff. Zero-cost, catches file size, DRY, complexity, secrets, known-vulnerable dependencies mechanically.

**Step 7 — Gate 3 (conformance + validation review)**
1. New Claude or ChatGPT conversation.
2. Give it: the code diff + `feature-implementation-plan.md` + Gate 2.5's findings + any escalation flags DeepSeek raised in Step 5.
3. Ask it to check verification (does the code match the plan) and validation (does this still look like the right thing to have built) separately, and to resolve DeepSeek's flagged concerns explicitly — confirm or dismiss each, with reasons.

**Step 8 — Ship, then close the loop**
If something surfaces later in production that nothing caught, add one line to `engineering-patterns-reference.md`. Re-upload the updated file to the Project Memory notebook so future features inherit the lesson. Log the incident in the incident log per `operations-and-incident-readiness.md` §1, referencing the plan path that built the feature — that's what makes a future 2am problem traceable back to an actual document instead of a guess.

---

## Quick-reference: what goes where, one line each
- **NotebookLM** — turns a short feature description into evidence-backed research
- **Claude** — turns research into an architecture + plan, using the decision-system and patterns files
- **ChatGPT (+ Gemini for high-stakes)** — independently audits the plan before code exists
- **AGENTS.md (Antigravity)** — governs how DeepSeek builds; already loaded, never re-pasted
- **DeepSeek** — builds from the plan, flags real uncertainty (5 categories), doesn't relitigate settled architecture
- **Gate 2.5 tools** — catch mechanical issues for free, no model involved
- **Gate 3 (Claude/ChatGPT)** — checks the finished code against the plan, and resolves DeepSeek's flags
