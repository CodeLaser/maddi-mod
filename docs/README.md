# docs/ — maddi-mod

The working documents of this repository. The index of all three repositories, with each document's status
and what it covers, is maddi's [`docs/README.md`](https://github.com/CodeLaser/maddi/blob/main/docs/README.md);
documents cited here by relative path from the other two repositories live there.

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
- [design/receiver-level.md](design/receiver-level.md) — The receiver decides too: level and cone rules at a call site (2026-09-29)
- [design/spec-eventually-unmodified-parameter.md](design/spec-eventually-unmodified-parameter.md) — Spec — `EVENTUALLY_UNMODIFIED_PARAMETER` (`@NotModified(after=…)` on parameters)
- [roadmap/handoff-saturated-closure-collapse.md](roadmap/handoff-saturated-closure-collapse.md) — Handoff — collapsing saturated closures in the link engine
- [roadmap/modification-link-analyzer-hardening.md](roadmap/modification-link-analyzer-hardening.md) — Modification link + analyzer hardening roadmap (real-world robustness)
- [roadmap/modification-link-applications.md](roadmap/modification-link-applications.md) — Candidate applications for `maddi-modification-link`
- [roadmap/prep-analyzer-hardening.md](roadmap/prep-analyzer-hardening.md) — Prep-analyzer hardening roadmap
- [roadmap/semantic-preconditions-for-relocation.md](roadmap/semantic-preconditions-for-relocation.md) — Semantic preconditions for state-relocating refactorings
