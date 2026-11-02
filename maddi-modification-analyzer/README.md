# The iterating analyzer

What `maddi-modification-analyzer` runs, in what order, and which component decides which property. The
concepts (modification, container, independence, immutability) are in `definitions.md`; the vocabulary and the
engine facts an assistant should not re-derive are in maddi's `road-to-immutability/llm-summary.md`
(§Convergence is the short form of this file). The link engine the analyzer drives is
`../maddi-modification-link/linking-manual.md`.

Entry point: `IteratingAnalyzer.analyze(order, callGraph)`, implemented by `impl/IteratingAnalyzerImpl`. The run
driver (`maddi-run-analysis`'s `AnalysisEngineImpl`) hands it the analysis order and the call graph that maddi's
`maddi-callgraph` computed, after `PrepAnalyzer` (`maddi-modification-prepwork`) has written the per-method
variable data, `IMPLEMENTATIONS`, `FINAL_FIELD` and the part-of-construction facts. The analyzer writes its verdicts
as properties on `element.analysis()`, through `TolerantWrite` (`maddi-modification-common`): write-once, with
controlled upward overwrites only. Absence means undecided.

## One pass

`impl/SingleIterationAnalyzerImpl.go` walks the analysis order once. Per element:

| element | what runs |
|---|---|
| method | `SourceContractMaterializer` writes the source contracts the shallow path would otherwise miss (`@Immutable` on an accessor, `@Finalizer`, `@IgnoreModifications`). On the first pass, an abstract method's type is shallow-analyzed once. Then the link engine computes `METHOD_LINKS`, the method's summary (`LinkComputer.doMethod`), unless the link cache shows that nothing the previous computation read has changed (CodeLaser/maddi-mod#76, `NOLINKCACHE` opts out, `LINKCACHECHECK` verifies). The link engine also writes the method's own modification verdicts and its parameters' (`NON_MODIFYING_METHOD`, `UNMODIFIED_PARAMETER`, the `STRUCTURALLY_*` twins, `DOWNCAST_PARAMETER`), except a parameter whose verdict waits for a field's. |
| field | contract materialization; `DynamicImmutabilityInference` (what the field holds, carried from the call site through a constructor parameter); `FieldAnalyzerImpl`: `UNMODIFIED_FIELD` from the methods that refer to the field, `INDEPENDENT_FIELD` from the field's links (`LinksImpl.LINKS`). |
| type | the type analyzers, in this order: `TypeModIndyAnalyzerImpl` (`IDENTITY_METHOD`, `FLUENT_METHOD`, `INDEPENDENT_METHOD`, `INDEPENDENT_PARAMETER`, `UNMODIFIED_PARAMETER` for the parameters that waited on a field, `UNMODIFIABLE_VIEW_METHOD` for a method with a body), `TypeIndependentAnalyzerImpl` (`INDEPENDENT_TYPE`), `TypeImmutableAnalyzerImpl` (`UNMODIFIABLE_TYPE`, then `IMMUTABLE_TYPE`: rules 0–3 and the hidden-content level), `TypeContainerAnalyzerImpl` (`CONTAINER_TYPE`: every parameter of a non-private method or constructor unmodified), `TypeEventualAnalyzerImpl` (the eventual family, by propagation from the support classes' contracts), `StaticSideEffectAnalyzerImpl` (`STATIC_STATE_MODIFIED_METHOD`, `STATIC_SIDE_EFFECTS_METHOD`). |

After the walk, `AbstractMethodAnalyzerImpl` runs over every method whose verdict is the union over its
implementations (abstract methods, and throw-only placeholders for modification): modification, structural
modification, independence, downcasts, unmodifiable view and the eventual properties, each folded over
`IMPLEMENTATIONS`. A method without implementations is decided once, on the first pass. `ContractResolution` is
the one place that decides whether an authored contract on a bodiless method, or on one it overrides, settles a
property; a decided contract is never second-guessed by the fold. Then the type analyzers run a second time over
all types, because the fold may have decided what they were waiting for.

Parallelism: `PARALLEL=n` sets the thread count (default `min(8, cores − 2)`; `1` is sequential), from 256
elements. The first pass runs in dependency waves computed from the call graph (`ComputeAnalysisOrder.waves`:
units run concurrently within a wave, waves are barriers; an on-demand lock on an absent summary degrades to a
shallow computation instead of waiting); later passes run all their elements in parallel. Verdicts around
cycles can depend on thread order (CodeLaser/maddi-mod#82, #23); `NOWORKLIST` and `PARALLEL=1` are the A/B baseline.

A crash on one element is fatal by default; with `Configuration.faultTolerant()` (the production runners) it
becomes an ERROR finding (`analyzer-crash` / `link-crash`), the element is marked `DEGRADED_ANALYSIS_METHOD` and
is not retried.

## The iteration

`IteratingAnalyzerImpl.iterate`:

1. **First pass** over everything.
2. **Worklist** (on by default when a call graph is given; `NOWORKLIST` opts out): the next pass re-analyzes only
   the elements whose summary changed, plus their dependents. The reverse adjacency is symmetric over the call
   graph, plus the override relation, plus the summary-consumption edges the link engine records (consumer read
   consumed's `METHOD_LINKS`), which catch value-mediated flows the call graph has no edge for. A lambda's or
   anonymous class's method maps to its enclosing analysis-order element.
3. **Verification**: when the worklist runs dry, one full pass. Zero changes certifies the fixpoint; changes
   resume the worklist from them.
4. **Cycle breaking**: at the certification point, when types remain immutability-undecided (they wait for
   information that will never arrive: external supertypes without values, call cycles), one more full pass runs
   with `cycleBreakingActive`, and the analyzers resolve their undecided inputs pessimistically or by the
   configured `CycleBreakingStrategy` (`NO_INFORMATION_IS_NON_MODIFYING` reads an undecided callee as
   non-modifying). The result is re-certified. `NOCYCLEBREAKING` opts out. A conclusion resting on a broken cycle
   is capped at the hidden-content level.
5. **Refused downgrades** are counted (`TolerantWrite.refusedDowngrades`): a certified fixpoint with refusals is
   logged as such, and `STRICTCERT` refuses to certify it. `certifiedWithoutFrozenValues()` reports the clean case.
6. **MODREACH**: when `Configuration.modificationViaReachability()` is set (`AnalysisEngineImpl` sets it unless
   `MODREACH=0`), `shadow/ShadowModificationPass` re-derives `NON_MODIFYING_METHOD`, `UNMODIFIED_PARAMETER` and
   `UNMODIFIED_FIELD` as one-shot reachability over the converged links and the primitive evidence in the bodies,
   writes them (the cutover, the one sanctioned downgrade past `TolerantWrite`; a contract on a bodiless method
   stays), computes the structural twins from a second closure, and freezes the three properties. When a round
   changed anything, the derived family (immutability, independence, container, eventual) is cleared and
   re-derived with modification frozen; a round that only strengthened re-derives through the worklist instead
   (#76, `NOINCREDERIVE` restores the clear). Rounds repeat to a joint fixpoint, at most `MODREACH_ROUNDS` (3).
   Diagnostics: `MODREACH_EXPLAIN=<substring>`, `MODREACH_TAINT=<substring>`, `MODREACH_DEBUG`. The design and
   its history: `PLAN-modification-reachability.md`.
7. **Eventual deferral** (`EventualCluster.ENABLED`, `EVENTUALCLUSTER=0` turns the cluster off, `EVENTUALDEFER=0`
   the deferral): at the terminal certification point the eventual family is cleared once and re-derived over
   the settled state, so that write-once walks do not freeze on the timing of an earlier pass
   (`docs/design/eventual-info-hierarchy.md`).
8. **Terminal**: `EventualClusterContraction` retracts eventual verdicts whose assumptions did not hold; the
   verdict fingerprint is logged (`FPDUMP=<file>` dumps every verdict, the A/B instrument); then the guard.
9. **Guard** (`Configuration.guardContracts()`, default on): `GuardAnalyzerImpl` verifies the source contracts
   against the computed values and reports findings, never values: `contract-violation`, `contract-conflict`,
   `contract-unverifiable`, `override-weakens-computed`, `ignore-modifications-not-confined`; with
   `warnNearMisses()` the `near-miss-*` warnings. With `Culprits.ENABLED` (a run that asks for explanations) the
   culprits are written beside the verdicts: `MODIFIED_CULPRIT` (by the reachability pass), `IMMUTABLE_CULPRIT`,
   `DEPENDENT_CULPRIT`, `CONTAINER_CULPRIT` (`TypeCulprit`, `DependentCulprit`), `LinkToField` naming the link a
   verdict rests on. `docs/design/guard-mode-analysis.md`.
10. **Nullability** (`Configuration.nullability()`): after `iterate` returns, `nullability/NullabilityPass` runs
    once over the converged link facts and writes `NULLABILITY_METHOD/PARAMETER/FIELD` (`docs/design/nullability.md`).

Other exits: `maxIterations` (uncertified, logged), and the opt-in plateau (`stopWhenCycleDetectedAndNoImprovements`,
without a worklist). `maxDuration` is a wall-clock budget checked per element (`AnalysisBudget`,
`AnalysisBudgetExceededException`).

**Incremental mode**: `analyze(order, graph, initialDirty, clearHook)` seeds the worklist with the changed
elements and stops when it runs dry, without verification or cycle breaking, so that carried values are not
re-touched (`docs/design/analysis-rewiring.md`; `AnalysisEngineImpl.incrementalSeed`, `INCREMENTAL=<dir>`).
`CHECKPOINT=<dir>` writes pass-boundary deltas through the value feed (`CheckpointWriter`), `CHECKPOINT_RESTORE`
preloads them; the verification pass is the soundness net of a resumed run.

## Configuration

`IteratingAnalyzer.Configuration`, built with `IteratingAnalyzerImpl.ConfigurationBuilder`:

| knob | default | meaning |
|---|---|---|
| `maxIterations` | 1 | passes before giving up; extended by each MODREACH or deferral round |
| `cycleBreakingStrategy` | `NONE` | how an undecided input is read while cycle breaking is active |
| `stopWhenCycleDetectedAndNoImprovements` | false | the plateau exit |
| `trackObjectCreations` | false | per-call argument links (`LINKED_VARIABLES_ARGUMENTS`); implied by the reachability pass and by nullability |
| `modificationViaReachability` | false | MODREACH (step 6); `AnalysisEngineImpl` turns it on |
| `guardContracts` | true | step 9 |
| `warnNearMisses`, `nearMissPolicy` | false, `STRICT` | advisory near-miss warnings |
| `faultTolerant` | false | isolate a crash to its element |
| `flattenVariableData` | false | evict the intermediate statements' variable data after linking (`DESIGN-vardata-flatten.md`); not compatible with the reachability pass; set by no production runner |
| `nullability`, `nullabilityOpenWorld` | false | step 10; `OPEN`: outside-callable parameters stay unspecified unless a precondition |
| `maxDuration` | unlimited | the wall-clock budget |

`linkComputerOptions()` derives the link engine's options from these: recursion on, `trackObjectCreations`,
`nullConstantReturns` with nullability.

## Properties by writer

| writer | properties |
|---|---|
| link engine (`maddi-modification-link`) | `METHOD_LINKS`, `NON_MODIFYING_METHOD`, `UNMODIFIED_PARAMETER`, `STRUCTURALLY_NON_MODIFYING_METHOD`, `STRUCTURALLY_UNMODIFIED_PARAMETER`, `DOWNCAST_PARAMETER`; per statement, in `VariableInfo`: the links, `UNMODIFIED_VARIABLE`, `STRUCTURALLY_UNMODIFIED_VARIABLE`, `DOWNCAST_VARIABLE` |
| `FieldAnalyzerImpl` | `UNMODIFIED_FIELD`, `INDEPENDENT_FIELD`, `LinksImpl.LINKS` |
| `TypeModIndyAnalyzerImpl` | `IDENTITY_METHOD`, `FLUENT_METHOD`, `INDEPENDENT_METHOD`, `INDEPENDENT_PARAMETER`, `UNMODIFIED_PARAMETER` (field-linked), `UNMODIFIABLE_VIEW_METHOD` |
| `AbstractMethodAnalyzerImpl` | the same families for bodiless methods, as folds over `IMPLEMENTATIONS`; `IMMUTABLE_METHOD` |
| `TypeIndependentAnalyzerImpl` | `INDEPENDENT_TYPE` |
| `TypeImmutableAnalyzerImpl` | `IMMUTABLE_TYPE`, `UNMODIFIABLE_TYPE` |
| `TypeContainerAnalyzerImpl` | `CONTAINER_TYPE` |
| `TypeEventualAnalyzerImpl` | the eventual family: `EVENTUAL_METHOD`, `EVENTUALLY_NON_MODIFYING_METHOD`, `EVENTUALLY_UNMODIFIED_PARAMETER`, `EVENTUALLY_IMMUTABLE_TYPE` |
| `StaticSideEffectAnalyzerImpl` | `STATIC_STATE_MODIFIED_METHOD`, `STATIC_SIDE_EFFECTS_METHOD` |
| `ShadowModificationPass` | the three modification properties and their structural twins, as the authority after the cutover; `MODIFIED_CULPRIT` |
| `SourceContractMaterializer`, `DynamicImmutabilityInference` | the dynamic type (`IMMUTABLE_FIELD`, `IMMUTABLE_METHOD`), and the source contracts `EVENTUAL_METHOD`, `FINALIZER_METHOD`, `IGNORE_MODIFICATION(S)_*`, `STATIC_SIDE_EFFECTS_METHOD`, `UNMODIFIABLE_VIEW_METHOD` |
| `GuardAnalyzerImpl`, `TypeCulprit`, `DependentCulprit` | findings; the `*_CULPRIT` properties |
| `NullabilityPass` | `NULLABILITY_METHOD`, `NULLABILITY_PARAMETER`, `NULLABILITY_FIELD` |

The polarity: the annotations are `@Modified`/`@NotModified`, the properties `NON_MODIFYING_*`/`UNMODIFIED_*`,
true meaning not modified. `UNMODIFIED_FIELD` is content-only; assignment is `FINAL_FIELD` (prepwork).

## Packages

- `impl/`: the analyzers above, `IteratingAnalyzerImpl`, `SingleIterationAnalyzerImpl`, `ContractResolution`,
  `LinkToField`, `EventualCluster` and `EventualClusterContraction`, `AnalysisBudget`.
- `shadow/`: `ShadowModificationPass`, the reachability pass.
- `nullability/`: the nullability pass, one class per part (`NullabilityPass` is the driver; `Seeds`,
  `Contracts`, `NullGraph`, `MethodWalker`, `NonNullFacts`, `Verdicts`, …); `docs/design/nullability.md`.
- The root package: the interfaces, `CycleBreakingStrategy`, the value feed (`AnalysisProgressFeed`,
  `CompositeValueFeed`, `CheckpointWriter`).

Tests: `src/test/java/.../analyzer/` by topic (`modification/`, `field/`, `method/`, `eventual/`, `guard/`,
`shadow/`, `nullability/`, `kotlin/`, `rewire/`, `faulttolerance/`, …), 180 classes. The corpus ratchets are in `maddi-run-analysis` (repository README).

Other documents in this module: `definitions.md` (the concept chain, mapped to properties),
`PLAN-modification-reachability.md` (the reachability pass: design and the thread that delivered it),
`DESIGN-incremental-v2.md` and `DESIGN-vardata-flatten.md` (incremental reuse and heap bounding at monorepo scale;
status lines at their top), `immutability-transform-divergence.md` (a resolved adjudication, kept for the mechanism).
