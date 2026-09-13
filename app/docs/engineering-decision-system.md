# Engineering Decision System
*The machinery that decides which knowledge to invoke. `engineering-patterns-reference.md` is one input to this system, not a replacement for it. Use this at Phase 1 (research) and Phase 3 (architecture) — it's what generates the questions the pattern checklist can't generate on its own.*

## 0. The core shift
Stop asking "does pattern X apply?" Start asking: **"What must remain true about this system, what can go wrong, and which knowledge helps preserve those truths?"** No finite checklist covers this — assume incompleteness by default, and build in a way that keeps discovering what's missing rather than pretending a list is done.

---

## 1. Epistemic states — tag every non-trivial claim
Before it enters a plan, every significant technical or business statement should be classifiable as one of:
- **Verified fact** — backed by docs, spec, source, or test
- **Requirement** — something the system must do
- **Constraint** — imposed externally (legal, contractual, org policy)
- **Assumption** — believed but unverified
- **Decision** — chosen deliberately from alternatives
- **Hypothesis** — a guess to be tested
- **Unknown — requires investigation** — a legitimate state, not a failure

This alone prevents the most common AI failure chain: a guess ("the API probably supports idempotency") silently becomes a stated fact two documents later, and an architecture gets built on it. Confidently wrong is worse than explicitly uncertain — reward the "unknown" state, don't penalize it.

## 2. Requirement challenge pass (before Phase 3 architecture)
For every important requirement in the plan, ask: what problem does this actually solve? Is it necessary, measurable, internally consistent? What assumptions does it bake in? What happens if it's interpreted the wrong way? Does it conflict with another requirement elsewhere? This catches the case where excellent engineering gets built on a wrong or incomplete premise.

## 3. Assumption register
For every load-bearing assumption ("traffic stays under X," "the provider is backwards compatible," "this query is fast enough"): **assumption → evidence → confidence → consequence if wrong → how/when to validate.** The dangerous assumptions are always the ones nobody wrote down.

## 4. Discovery lenses — generate per feature, don't apply a fixed list
Interrogate the feature from whichever of these lenses are actually relevant (a healthcare feature needs a clinical-safety lens no generic list would include; a game needs anti-cheat). Common starting lenses: Functional · Data · Temporal (expiry, scheduling, ordering) · Failure · Adversarial/abuse · Scale (10x/100x/1000x) · Concurrency · Evolution (API/schema/dependency change) · Operations (deploy/observe/recover) · Economics (cost as usage grows) · Human factors (misuse, confusing UX, operator fatigue) · External ecosystem (regulation, vendor guarantees, known incidents).
Then always add the recursive one: **Lens 13 — Unknown. What lens haven't we considered for this specific system?**

## 5. Invariants — the load-bearing layer above patterns
Patterns are tools for preserving invariants, not the goal themselves. State the invariants explicitly before choosing patterns:
- **Business:** e.g. "a customer cannot be charged twice for the same purchase"
- **Data:** e.g. "an order always references an existing customer"
- **Security:** e.g. "a user can access only resources they're authorized for, in this exact context"
- **Operational:** e.g. "every production request is traceable across its critical dependencies"
- **Recovery:** e.g. "a confirmed transaction survives a process crash"

Every implementation decision gets judged against these, not just "did we follow best practice."

## 6. Decision states — replace binary applies/doesn't-apply
For every candidate pattern or principle:

| State | Meaning |
|---|---|
| **Required** | Skipping it creates unacceptable risk |
| **Recommended** | Real benefit, reasonable cost |
| **Optional** | Worth having, low priority |
| **Deferred** | Valid concern, premature at current scale/maturity |
| **Rejected** | Considered, intentionally not used |
| **Unknown** | Insufficient information |
| **Not applicable** | The triggering system characteristic doesn't exist here |

**Rejected and Deferred require a stated reason.** That reason is what stops the next AI session from "cleaning up" a decision it doesn't understand.

## 7. Trade-off analysis (for every significant decision)
Real architecture is full of two-good-things-in-tension: consistency vs. availability, latency vs. durability, security vs. usability, simplicity vs. extensibility, developer velocity vs. rigor. Template: **goal → options → benefits → risks → costs → affected qualities → decision → rationale → reversibility.** Don't let "don't over-engineer" be the only guidance when two legitimate principles actually conflict — that's exactly when judgment is needed, not a rule of thumb.

## 8. Verification vs. Validation — don't confuse these
- **Verification:** did we build what we intended? (tests, linting, review, static analysis)
- **Validation:** was what we intended actually the right thing to build? (requirement challenge, business fit)

Passing every gate proves verification. It proves nothing about validation. Don't let a green pipeline become false confidence that the right thing got built.

## 9. Second-order effects, interaction effects, emergence
Three checks AI systematically underweights:
- **Second-order:** caching → faster reads → stale data → wrong decisions made on stale data. What does this decision cause *indirectly*?
- **Interaction effects:** retry + timeout + queue + autoscaling + circuit breaker together can produce behavior none of them causes alone. Patterns get reviewed together, not just individually.
- **Emergence:** component correctness ≠ system correctness — independent retry loops can synchronize into a thundering herd even though each retry loop, alone, looks fine. Do a system-level pass, not just a component-level one.

## 10. Failure-history harvesting
For a significant feature, search not just "best practices for X" but "X outage postmortem," "X duplicate processing incident," "X data corruption incident." Real incident reports teach lessons textbooks don't — "we had backups" vs. "the backups were never restored successfully" is the kind of gap only failure history surfaces.

## 11. Fitness functions — where a principle becomes machine-checkable
When a principle can be phrased as an automatable check, do that instead of relying on review to catch drift forever: "domain module A must never import infrastructure module B," "every external HTTP call has a timeout," "no code accesses secrets directly," "public API responses never expose internal DB IDs." This is what Gate 2.5's tooling (Semgrep, dependency-cruiser) is *for* — architecture that stays true as code evolves, not just true on day one.

## 12. The loop — this replaces "the checklist"
```
WE KNOW WE ARE INCOMPLETE
        ↓
  DISCOVER CONCERNS (lenses, requirement challenge, assumption register)
        ↓
  SEARCH FOR KNOWLEDGE (research, failure history, docs)
        ↓
  CHALLENGE ASSUMPTIONS
        ↓
  MAKE EXPLICIT DECISIONS (decision states + rationale)
        ↓
  BUILD
        ↓
  VERIFY + ATTACK (Gate 2.5 + Gate 3 + validation check)
        ↓
  OPERATE
        ↓
  OBSERVE REALITY
        ↓
  LEARN
        ↓
  UPDATE KNOWLEDGE (append to patterns reference / this doc)
        ↓
  NEXT FEATURE
```
The goal is never "we've thought of everything." It's "we have a working way of discovering what we missed, every time."

## 13. Stopping rule — when to actually stop auditing
"No more findings" is not a valid stopping condition — it may never trigger, and an audit pass reporting nothing new is not proof nothing remains; it's one reviewer's limited sample of a space that has no guaranteed boundary. Do not treat any single "we've covered everything" statement as true, from any model, ever — this is a known overconfidence failure mode, not a completeness signal.

**Use severity trend instead of finding count:** stop iterating when two consecutive independent audit passes both come back with nothing above Medium severity. Log whatever Medium/Low items remain as **explicitly accepted residual risk** — write down what it is and why it's accepted, don't let it just go silent. That's a real decision (§1 epistemic states), not an assumption.

**For high-stakes/regulated surfaces (payments, auth, anything with real financial or legal liability):** don't rely on audit-pass count to reach safety at all. Reduce custom-built surface area first — route through an already-audited provider/SDK rather than hand-building the sensitive part, and audit your integration layer, not a reimplementation. Get a human security/compliance review before production regardless of how many AI passes were run — this pipeline lowers the risk you bring to that review, it doesn't replace it.

---

## How this plugs into your pipeline
- **Phase 1 (NotebookLM research):** add the discovery-lenses instruction (§4) and require epistemic-state tagging (§1) on major claims.
- **Phase 3 (Claude architecture):** run the requirement challenge (§2), build the assumption register (§3) and invariant list (§5), and use decision states (§6) instead of applies/doesn't-apply. Trade-off template (§7) for every major call.
- **Phase 4 (spec):** invariants and rejected/deferred rationale carry into the spec so DeepSeek and Gate 3 both see *why*, not just *what*.
- **Gate 3 (review):** check verification AND validation separately (§8), and explicitly look for interaction/emergent effects (§9), not just per-file correctness.
- **After shipping:** feed real incidents back into §10 and into `engineering-patterns-reference.md` — this is the "update knowledge" step in the loop, and it's the one most pipelines skip.
