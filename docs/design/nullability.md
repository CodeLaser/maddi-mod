# Nullability inference — design (2026-10-06)

Status: **proposal**, nothing implemented beyond the feasibility probe
(`maddi-modification-link/src/test/java/.../link/nullflow/TestNullFlowProbe.java`).

## 1. Goal and applications

Infer, for every field, parameter, method return and type argument, whether it can hold `null`, and, at every
dereference, whether the value can be null at that point. Two applications consume the same verdicts:

1. **Java annotations** — insert `@Nullable` / `@NonNull` (or `@NotNull`) into Java sources: as IDE hints, as
   source edits, and as analysis-hint output. This is at least as important as the translation, and comes
   first in the order of work (§7). It needs only the declaration verdicts (§4, M3), not the use-site
   decisions.
2. **Java → Kotlin translation** — the declared type (`T` vs `T?`, `List<String?>`) and, per dereference,
   nothing, `?.`, `!!` or `?:`. It needs both the declaration verdicts and the use-site decisions (§4, M4).

What to do with a value the analysis cannot decide is a **policy parameter**, not a design decision (§5).

## 2. What exists today

| piece | where | state |
|---|---|---|
| `NullableState {UNSPECIFIED, NONNULL, NULLABLE}` on `ParameterizedType` | maddi `maddi-cst-api` | set by the Kotlin front end (`KotlinTypeMapper`); **never set by the Java front end** |
| properties `NOT_NULL_METHOD` / `NOT_NULL_PARAMETER` / `NOT_NULL_FIELD`, value `NotNullImpl` (`NO_VALUE`, `NULLABLE`, `NOT_NULL`, `CONTENT_NOT_NULL`) | maddi `maddi-cst-analysis` | filled for library code by `ShallowMethodAnalyzer`/`ShallowTypeAnalyzer` from the analysis hints (626 `@NotNull` in the JDK archive); not inferred for source |
| inference design ("context not-null", "field not-null", "external not-null") | road-to-immutability ch. 120 §"Nullable, not null" | marked *Not implemented* |
| `DecoratorImpl` | maddi-mod prepwork `io/` | prints maddi's own `@NotNull` (and `content=true`) from `NOT_NULL_*`; never `@Nullable`. Used by the analysis-hints writer, `AnalysisEngineImpl`, the IDE daemon (Eclipse code minings) |
| Kotlin printer | maddi `maddi-cst-print-kotlin` | prints `?` iff `NullableState.NULLABLE`: every Java-parsed type prints non-null today |
| null literals in the link graph | link module | constant markers `$_ceN` whose expression is the `NullConstant`; see §3 |
| source edits (insert an annotation into a file) | — | none anywhere; the IDE integrations only display |

## 3. Feasibility probe — what the link engine already delivers

`TestNullFlowProbe` pins current behaviour. A null literal survives as a constant marker along:

- returns that also have a real source: `ternary←$_ce0(null), ternary←1:s`;
- field assignment (method-level variable data): `this.f←$_ce0(null)`;
- setters seen from the caller, also on another object: `this.f←null`, summary `0:other*.f←null`;
- collection content: `list.add(null)` → `0:list.§$s∋null`; maps keep keys (`§$$s[-1]`) apart from values
  (`§$$s[-2]`); array initializers are index-precise;
- source generic holders: `box.set(null)` → `0:box.t←null`;
- if/else joins: `x = null; if (b) x = s; f = x` → `this.f←null, this.f←x`.

Non-null literals are constant markers too (`earlyExit←$_ce1` is `""`): positive evidence comes for free.

Two gaps:

- **Marker-only returns are erased from the summary.** `LinkComputerImpl.emptyIfOnlySomeValue` empties a return
  link set whose targets are all markers: `return null`, `return new Box<>(null)`, `return listWithANull` give
  `[] --> -`, although the variable data has the fact. A caller of such a method sees only `$_v`.
- **No condition tracking**: after `if (x == null) x = "default"`, `x` still links to its possibly-null source.
  Expected — linking is not path-sensitive — and the reason for the separate use-site pass (M4).

## 4. Architecture

### 4.1 The approach in one line

Declaration nullability is **reachability over the converged link facts**, computed once after the iterating
analyzer has reached its fixpoint, modelled on `ShadowModificationPass` (MODREACH). It is *not* a new
write-once phase inside the iteration.

Reason: `maddi-modification-analyzer/PLAN-modification-reachability.md`. Modification computed inside the
iteration froze optimistic values (`UNMODIFIED = TRUE`) before the evidence arrived, and chains deeper than two
levels came out wrong. Nullability has the same shape with "non-null" as the optimistic value. A post-convergence
reachability pass over the link facts writes each value once, from complete information. The shadow pass already
has the edges needed (argument → parameter via `LINKED_VARIABLES_ARGUMENTS`, field ↔ parameter via
`METHOD_LINKS`, override ↔ overridden), only with modification flowing the other way.

### 4.2 Components

**maddi (base)**

- **B1 — Java front end reads null annotations** (`maddi-java-openjdk`): JSpecify, JetBrains, Checker
  Framework, JSR-305 / javax, Lombok `@NonNull`, and the scope of `@NullMarked` / `@NullUnmarked`, into
  `NullableState` on each type use. `TypeUseAnnotationClosure` (maddi-run-config) already knows these
  annotation families. Feeds both the user's contracts (seeds) and the test oracle (§6).
- **B2 — value model** (`maddi-cst-analysis`, codec in `maddi-cst-io`): the inferred type is a
  **`ParameterizedType` carrying `NullableState` per type argument** (decision 2026-10-06). The outer state
  answers "can this be null", the arguments answer "can its elements / keys / values be null"
  (`Map<String, String?>`). Nullability is already a per-use dimension of the type
  (`TestTypeUseAnnotationDistinguishesUses`), and the Kotlin printer already prints it. New properties on
  `FieldInfo`, `ParameterInfo` and `MethodInfo` (return), e.g. `NULLABILITY_TYPE_FIELD/PARAMETER/METHOD`. The
  existing `NOT_NULL_*` stay, derived from the outer state, so `DecoratorImpl`, `MethodInfo.isNotNull…` and the
  hint files keep working. `UNSPECIFIED` means *undecided* (input to the policy, §5).
- **B3 — printers**: the Kotlin printer reads the property for declarations, falling back to
  `NullableState`, and reads a per-expression use-site decision (M4) for `?.` / `!!` / `?:`. The Java side
  is `DecoratorImpl`, which is in maddi-mod (§D1). No base → mod dependency arises: the properties live in base.

**maddi-mod**

- **M1 — link module hands over the facts** (`maddi-modification-link`): the link engine stays free of nullability
  semantics. Work: close the marker-only-return gap (either keep null markers in the summary, or carry the
  return's constant sources side-band for M3); check that `LINKED_VARIABLES_ARGUMENTS` is populated in the
  configurations M3 runs under (`Options.PRODUCTION` has `trackObjectCreations = true`). Every change behind a
  gate, with an FPDUMP A/B and `TestParSeqLinkBench` (golden rule: no silent verdict changes).
- **M2 — seeds** (`maddi-modification-common`): library seeds exist (`ShallowMethodAnalyzer`); add B1's
  annotations as contracts (a contract wins over inference, as `SourceContractMaterializer` does for other
  properties).
- **M3 — declaration pass**, package **`io.codelaser.maddi.modification.analyzer.nullability`** (decision
  2026-10-06; extract to a module later if it grows), next to `shadow/`:
  - *nodes*: parameters, fields, method returns, and their content slots (`§` paths: `List` elements, `Map`
    key and value slices, array elements, the fields of source holders);
  - *seeds*: null constant markers; nullable library returns and fields (hints, annotations); degraded methods
    (`DEGRADED_ANALYSIS_METHOD`: no links, so their outputs are UNSPECIFIED, never non-null); unannotated jars;
  - *edges*, in the direction null travels (source → recipient): argument → callee parameter
    (`LINKED_VARIABLES_ARGUMENTS`); `←`, `∈`, `∋` from `METHOD_LINKS`; field links from each method's
    last-statement variable data (what `FieldAnalyzerImpl` reads); callee return → caller variable;
  - *overrides*, Kotlin's rules (they are also sound for Java annotations): a parameter has one nullability
    across its whole override chain (join over the chain); a return may be non-null in an override of a
    nullable return, but an overridden return is nullable when any override's is;
  - *writes*: the B2 properties, once, after convergence. The opposite evidence ("dereferenced without a check",
    ch. 120's *context not-null*) comes from M4 and is consumed by M5, not by the reachability.
- **M4 — use-site pass** (flow-sensitive, per method): walks the CST in statement order and decides, per
  dereference, whether the value can be null there. Recognizes `x == null` / `x != null` / `instanceof`
  conditions via the CST evaluator (`EvalEquals`, `sortAndSimplify`), guard clauses via prepwork's
  `ComputeAlwaysEscapes`, `Objects.requireNonNull` and friends via their hints. Writes a per-expression
  property, as the link computer does with `VARIABLES_LINKED_TO_OBJECT` on a `MethodCall`. Constraint:
  `SingleIterationAnalyzerImpl.flattenMethod` drops the per-statement variable data after linking, so M4
  relies on the CST and method-level data only. Needed for Kotlin; for Java annotations only as M5's input.
- **M5 — findings** (`GuardAnalyzer` pattern: messages, never property values): a parameter that receives null
  from a caller and is dereferenced unguarded; a violated `@NonNull` contract. Valuable for the Java application
  on its own.

**Applications**

- **D1 — Java annotations.** (a) `DecoratorImpl` learns `@Nullable` and an **annotation flavour**
  parameter (maddi `@NotNull`, JSpecify, JetBrains, JSR-305, Checker) with the flavour's placement
  (type-use, so `List<@Nullable String>`, vs declaration). (b) Under a `@NullMarked` scope only `@Nullable` is
  written; a project-level option decides whether to introduce `@NullMarked` itself. (c) The IDE path comes
  for free through the decorator (Eclipse code minings, daemon). (d) **Source edits do not exist yet**:
  inserting annotations into files, with imports, at the positions `DetailedSources` records, is new work;
  it belongs with the distributions (maddi-dist: a CLI command, a Gradle/Maven goal, an IDE quick fix).
- **D2 — Kotlin translation.** B3 plus a translation entry point; today the printer runs only from tests.
  Kotlin infers local types, so locals need a declared type only when initialized to null and assigned later
  (`var x: String? = null`).

## 5. Policy parameters

One configuration record, read by both applications (decision 2026-10-06: parameterize, decide later):

| situation | options |
|---|---|
| Kotlin, UNSPECIFIED declaration | `?` (safe, noisy) / non-null (the use sites then get `!!` where M4 cannot prove non-null) |
| Kotlin, dereference of a possibly-null value | `!!` / `?.` (changes semantics: the expression may then be null too) |
| Java, UNSPECIFIED declaration | no annotation / `@Nullable` |
| Java, flavour and scope | see D1 |

## 6. Testing

| layer | location | pins |
|---|---|---|
| T1 link facts | link module, `nullflow/` (`TestNullFlowProbe`) | null facts reach summaries and variable data; exact-string pins; the M1 gap closure flips its "GAP" assertions |
| T2 front end | `maddi-java-openjdk` | annotation → `NullableState` per flavour; `@NullMarked` / `@NullUnmarked` scoping; type-use positions |
| T3 declaration pass | analyzer `nullability/`: `TestNullabilityFields`, `…Parameters`, `…Returns`, `…Content`, `…Overrides`, `…Library`, `…Degraded` | the main spec-by-example: small multi-type inputs, one asserted verdict per declaration |
| T4 use sites | same package, `TestNullabilityUseSites` | the decision per dereference |
| T5a Java end-to-end | prepwork `io/` (decorator tests) | Java in, annotated Java out, per flavour |
| T5b Kotlin end-to-end | `maddi-inspection-kotlin-analysis` (next to `TestKotlinPrinterRoundTrip`, which needs prepwork) | Java in, Kotlin text out; optionally compile the output with `kotlinc` as a second oracle |
| T6 corpus oracle (`slowTest`) | analyzer `nullability/` or `maddi-run-analysis` | see below |

**The oracle.** guava is `@NullMarked` with 1,341 files importing `org.jspecify.annotations.Nullable`;
OpenSearch adds 563 annotated files. Infer with the annotations *hidden* (B1 reads them, the pass does not
seed from them), then compare per declaration, reporting two numbers separately:

- **unsafe**: inferred non-null, annotated nullable — must stay near zero;
- **noise**: inferred nullable, annotated non-null — costs extra `@Nullable`, `?` and `!!`.

This measures the Java-annotation application directly, in its own notation. The Kotlin corpora (exposed,
retrofit, through the K2 front end) are a weaker oracle: declared `?` is often over-declared. The first
milestone runs the harness against two trivial baselines (everything nullable, everything non-null), so the
inference has a number to beat before it exists.

Standing rules: FPDUMP A/B for any link-module change; `TestParSeqLinkBench` after engine-level changes; a green
`slowTest` is evidence only after the checks of maddi's `AGENTS.md` §Commands.

## 7. Order of work

1. B1 + the T6 harness with trivial baselines.
2. M1: the marker-only-return gap.
3. M3 (+ M2 seeds), iterated against the oracle.
4. B2 properties written; D1(a)–(c): annotated Java through the decorator.
5. M4 + M5.
6. B3 + D2: Kotlin.
7. D1(d): source edits.

Discrete items go to GitHub issues, linking back to the section here.

## 8. Open questions

- The opposite polarity: should "dereferenced without a check" (M4) ever make a parameter *non-null* for the
  annotation output (a precondition), or only produce M5 findings? Kotlin needs the former to avoid `?` on every
  parameter of a public API that callers outside the analysed code may reach.
- Public API entry points: a parameter of a public method with no analysed caller — UNSPECIFIED, or
  non-null-by-precondition (previous question)?
- Generic type parameters (`T` vs `T & Any` / `T?` in Kotlin, `@Nullable T` in JSpecify): where the type
  parameter itself carries the nullability.
