# docs/ — maddi-mod

The working documents of this repository. The index of all three repositories, with each document's status
and what it covers, is maddi's [`docs/README.md`](https://github.com/CodeLaser/maddi/blob/main/docs/README.md);
documents cited here by relative path from the other two repositories live there.

## Module references

The documents kept next to the code; these describe the current state and are updated in place.

| Document | Status | What it covers |
|---|---|---|
| [`maddi-modification-link/README.md`](../maddi-modification-link/README.md) | reference | The link natures and the operator table, the engine in one paragraph, virtual fields, the shallow summaries, the special variables. |
| [`maddi-modification-link/linking-manual.md`](../maddi-modification-link/linking-manual.md) | reference | The working manual: pipeline, data model, notation, `LinkMethodCall`, worked examples, functional interfaces, the probe loop, the file map. |
| [`maddi-modification-link/src/main/java/.../vf/virtual-fields.md`](../maddi-modification-link/src/main/java/io/codelaser/maddi/modification/link/vf/virtual-fields.md) | reference | The `§` virtual fields: `§m`, hidden content, multiplicity, naming, the formal→concrete translation. |
| [`maddi-modification-link/sv-reconstruction-techniques.md`](../maddi-modification-link/sv-reconstruction-techniques.md) | reference | The engine layer: how links are reconstructed on real variables after the shared-variable collapse; every guard and its gate. |
| `maddi-modification-link/sv-*.md` (the other five), `semantic-audit-2026-07-18.md`, `org-review-2026-07-18.md`, `notes/` | closed records | The sv-engine integration of July 2026 and the audits of that month; headers say what is history. |
| [`maddi-modification-analyzer/README.md`](../maddi-modification-analyzer/README.md) | reference | What the analyzer runs, in what order: one pass, the iteration (worklist, certification, cycle breaking, the reachability pass, the eventual deferral, the guard, nullability), configuration, properties by writer. |
| [`maddi-modification-analyzer/definitions.md`](../maddi-modification-analyzer/definitions.md) | reference | The concept chain of the book mapped onto properties and values; what that means for the guard. |
| [`maddi-modification-analyzer/PLAN-modification-reachability.md`](../maddi-modification-analyzer/PLAN-modification-reachability.md) | implemented | The reachability pass (`ShadowModificationPass`, on by default): the design and the thread that delivered it. |
| [`maddi-modification-analyzer/DESIGN-incremental-v2.md`](../maddi-modification-analyzer/DESIGN-incremental-v2.md) | partly implemented | Incremental reuse: type granularity exists (`INCREMENTAL=<dir>`), element granularity does not. |
| [`maddi-modification-analyzer/DESIGN-vardata-flatten.md`](../maddi-modification-analyzer/DESIGN-vardata-flatten.md) | implemented, off | Bounding the heap on a single giant SCC (`Configuration.flattenVariableData`); not compatible with the reachability pass. |
| [`maddi-modification-analyzer/immutability-transform-divergence.md`](../maddi-modification-analyzer/immutability-transform-divergence.md) | resolved | Transported content decides dependence; the adjudication that led to the rule in `FieldAnalyzerImpl`. |

## Design notes and roadmaps

- [defects/sam-linking-reconciliation.md](defects/sam-linking-reconciliation.md) — The two SAM conventions: what actually diverges
- [design/analysis-rewiring.md](design/analysis-rewiring.md) — Analysis rewiring: the analysisFingerprint
- [design/builder-interface-split-impact.md](design/builder-interface-split-impact.md) — Splitting the mutable Builder off the read-only Inspection interfaces — impact survey (2026-07)
- [design/dynamic-immutability-feasibility.md](design/dynamic-immutability-feasibility.md) — Dynamic immutability: closing the consumption path (2026-07)
- [design/eventual-design-improvements.md](design/eventual-design-improvements.md) — Eventual immutability: design improvements for cst-api/cst-impl
- [design/eventual-immutability.md](design/eventual-immutability.md) — Eventual immutability — plan and status
- [design/eventual-info-hierarchy.md](design/eventual-info-hierarchy.md) — Making the `Info` hierarchy eventually immutable — diagnosis
- [design/guard-mode-analysis.md](design/guard-mode-analysis.md) — Guard mode: analysis and design proposal
- [design/handoff-builder-leans.md](design/handoff-builder-leans.md) — Handoff — the Builder leans (the last structural gap before the flagship family can survive)
- [design/handoff-eventual-interface-nonmodification.md](design/handoff-eventual-interface-nonmodification.md) — Handoff — surface the `*Info` interfaces' eventual verdict (greatest-fixpoint Part B)
- [design/handoff-verification-residue.md](design/handoff-verification-residue.md) — Handoff — the verification-pass residue (the gate on the eventual-immutability endgame)
- [design/independent-type-optimism.md](design/independent-type-optimism.md) — `INDEPENDENT_TYPE` can be permanently optimistic
- [design/nullability.md](design/nullability.md) — Nullability inference: Java annotations and Java→Kotlin, design (2026-10-06)
- [design/receiver-level.md](design/receiver-level.md) — The receiver decides too: level and cone rules at a call site (2026-09-29)
- [design/spec-eventually-unmodified-parameter.md](design/spec-eventually-unmodified-parameter.md) — Spec — `EVENTUALLY_UNMODIFIED_PARAMETER` (`@NotModified(after=…)` on parameters)
- [roadmap/handoff-saturated-closure-collapse.md](roadmap/handoff-saturated-closure-collapse.md) — Handoff — collapsing saturated closures in the link engine
- [roadmap/modification-link-analyzer-hardening.md](roadmap/modification-link-analyzer-hardening.md) — Modification link + analyzer hardening roadmap (real-world robustness)
- [roadmap/modification-link-applications.md](roadmap/modification-link-applications.md) — Candidate applications for `maddi-modification-link`
- [roadmap/ownership.md](roadmap/ownership.md) — Ownership: `@Fresh`, `@Owned`, `@Borrowed` — implementation brief (2026-11-13)
- [roadmap/prep-analyzer-hardening.md](roadmap/prep-analyzer-hardening.md) — Prep-analyzer hardening roadmap
- [roadmap/semantic-preconditions-for-relocation.md](roadmap/semantic-preconditions-for-relocation.md) — Semantic preconditions for state-relocating refactorings
- [related-work/jule-memory-model.md](related-work/jule-memory-model.md) — Jule's memory model and mutability rules, compared with maddi (2026-11-13)
