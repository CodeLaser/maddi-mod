# Nullability inference — design (2026-10-06)

Status: **in progress** — steps 1–3 of §7 have a first implementation; see §9 for what landed and what it
measures. Feasibility probe: `maddi-modification-link/src/test/java/.../link/nullflow/TestNullFlowProbe.java`.

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

## 2. What existed at the start (2026-10-06)

(`NOT_NULL_*` and `NotNullImpl` have since been removed; see B2 and the §9 log.)

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

- **B1 — what the source declares** (`maddi-cst-impl`, `DeclaredNullability`): JSpecify, JetBrains, Checker
  Framework, JSR-305 / javax, Lombok `@NonNull`, and the scope of `@NullMarked` / `@NullUnmarked` /
  `@ParametersAreNonnullByDefault`, read into the B2 value shape. *Changed while implementing:* the front end does
  not write `NullableState`. Since `6d189c44e` it carries type-use annotations as annotations on the
  `ParameterizedType` (their identity and import are what printing back needs; `TestTypeUseAnnotationDistinguishesUses`
  records that decision), so B1 is an interpreter over those annotations and the declaration annotations, not a
  second channel. Feeds the user's contracts (seeds) and the test oracle (§6).
- **B2 — value model** (`maddi-cst-analysis`, codec in `maddi-cst-io`): the inferred type is a
  **`ParameterizedType` carrying `NullableState` per type argument** (decision 2026-10-06). The outer state
  answers "can this be null", the arguments answer "can its elements / keys / values be null"
  (`Map<String, String?>`). Nullability is already a per-use dimension of the type
  (`TestTypeUseAnnotationDistinguishesUses`), and the Kotlin printer already prints it. New properties on
  `FieldInfo`, `ParameterInfo` and `MethodInfo` (return): `NULLABILITY_FIELD/PARAMETER/METHOD`, the ONLY
  nullness properties (decision 2026-10-06: `NOT_NULL_*` removed, no backward compatibility). `UNSPECIFIED`, the
  default, means *nobody said* (input to the policy, §5); the old properties' default was NULLABLE, which could
  not tell "may be null" from "unknown".
- **B3 — printers**: the Kotlin printer reads the property for declarations, falling back to
  `NullableState`, and reads a per-expression use-site decision (M4) for `?.` / `!!` / `?:`. The Java side
  is `DecoratorImpl`, which is in maddi-mod (§D1). No base → mod dependency arises: the properties live in base.

**maddi-mod**

- **M1 — link module hands over the facts** (`maddi-modification-link`): the link engine stays free of nullability
  semantics. Work: close the marker-only-return gap; check that `LINKED_VARIABLES_ARGUMENTS` is populated in the
  configurations M3 runs under. *Implemented as* `LinkComputer.Options.nullConstantReturns` (keep the links to
  NULL-constant markers only; side-band was rejected: a caller of `source()` only sees `$_v`, so the fact has to
  travel in the summary). Off everywhere by default, switched on with the analyzer's `Configuration.nullability()`
  (which also turns on `trackObjectCreations`); turning it on in PRODUCTION waits for an FPDUMP A/B and
  `TestParSeqLinkBench` (golden rule: no silent verdict changes).
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

## 9. Implementation log

### 2026-10-06 — steps 1–3, first cut

- **B1** `DeclaredNullability` (maddi `maddi-cst-impl`), tests `TestDeclaredNullability` (maddi-java-openjdk).
  Front-end facts found on the way, all pinned there:
  - a bounded wildcard dropped its bound's type-use annotations (`? extends @Nullable CharSequence`): fixed in
    `ClassSymbolScanner`, the `JCTypeApply` defect of `6d189c44e` once more;
  - javac normalizes `? extends @Nullable Object` to `?`: the annotation is gone, and an unbounded wildcard is
    parametric anyway;
  - an array is one `ParameterizedType`, so element nullability has no slot. On a FIELD, `@Nullable String[] a`
    stays a declaration annotation and is skipped (it qualifies the elements); on a PARAMETER or RETURN it reaches
    the type and reads as a nullable array, which it is not. Open: an element slot for arrays (B2).
- **T6** `TestNullabilityOracleGuava` (maddi-run-analysis, slow) with `NullabilityComparison` (analyzer
  `nullability/`). Reference: 607 primary types, 15 `@NullMarked` packages, 15,787 scored positions (2,515
  declared nullable, 13,272 non-null). Baselines: all-nullable 0 unsafe / 13,272 noise; all-non-null 2,515 unsafe /
  0 noise.
- **M1** `LinkComputer.Options.nullConstantReturns`, `TestNullFlowProbe` test 7.
- **M3** `NullabilityPass` v1 (analyzer `nullability/`), tests `TestNullabilityPass`: top-level reachability over
  fields, parameters and returns, locals as carriers between statements, overrides per §4.2; type arguments not
  inferred yet (UNSPECIFIED). Policies `NULL_MARKED` (with null tests), `NULL_MARKED_FLOW_ONLY`, `CAUTIOUS`.
  Wired through `IteratingAnalyzer.Configuration.nullability()`; not yet called from the iterating analyzer nor
  written into properties (step 4).

**First measurement on guava** (top-level positions only; type arguments are UNDECIDED by construction):

| verdict | agree | unsafe | noise | undecided (top) |
|---|---:|---:|---:|---:|
| all-non-null baseline | 13,272 | 2,515 | 0 | 0 |
| all-nullable baseline | 2,515 | 0 | 13,272 | 0 |
| `NULL_MARKED_FLOW_ONLY` | 12,609 | 467 | 696 | 277 |
| `NULL_MARKED` (+ null tests) | 12,702 | 391 | 711 | 245 |
| + JDK contracts (`LibraryNullness`) | 12,798 | 321 | 825 | 145 |
| + override edges directional at type variables | **12,766** | **321** | **817** | 145 |

Against "everything non-null" the pass removes 84% of the unsafe positions, at 5% of the noise of "everything
nullable"; null tests take unsafe 467 → 391 for 15 extra noise. A 40-name sample of the unsafe ones shows three
causes: (1) array and varargs parameters, where the REFERENCE is misread (the B1 array gap: an element annotation
on a parameter lands on the array type), (2) public API parameters no analysed caller passes null to
(`Joiner.join(@Nullable Object first, ...)`), (3) values returned from library calls (`comparator()`,
`pollFirstEntry()`), which the pass reads as non-null. The oracle now writes every disagreement with its cause chain
to `maddi-run-analysis/build/nullability-oracle-guava-<policy>.tsv` for classification.

After the first measurement:

- **JDK contracts** (`LibraryNullness`, a stopgap for M2): overriding a JDK method that may return null
  (`NavigableMap.ceilingEntry`, `SortedMap.comparator`, ...) or accepts null (`equals(Object)`,
  `Collection.contains(Object)`, ...), and using such a call's result directly (returned, assigned, passed on).
  Unsafe returns 128 → 64. Its noise is mostly the flow-insensitivity of the pass: `V v = map.get(k); if (v ==
  null) {...}` and then `v` stored or passed — the null is checked away, which M4 will see.
- **Override edges**: downward (overridden → implementation) always, upward not through a type-variable position.
  Joining both ways made every implementation of a generic interface one hub. A type-variable position null
  reaches is still NULLABLE: guava writes `@Nullable V get(Object)`, and marking such positions parametric cost
  ~500 agreements.

The remaining unsafe (321): 72 array/varargs positions (mostly the reference misread, B1 array gap); ~200 public
API parameters no analysed caller passes null to (`Preconditions.checkNotNull(..., p1, p2)` format arguments,
guava's own `Multimap.containsEntry(Object, Object)`), which need either use-site evidence (M4) or a policy for
public entry points (§8); 36 returns; 12 fields.

Cost: the modification analysis of guava with `nullability` on takes about 100 s of test time (the first run's
12 minutes were DEBUG logging); the pass itself is
negligible. ⛔ A corpus test must set the log level to INFO itself: the first run logged at DEBUG into a 27.7 GB
test report.

### 2026-10-06 — local variables, for the Java→Kotlin printer

Locals get a verdict (`Report.locals()`, `Report.local(MethodInfo, Element declaration, LocalVariable)`), so the
printer can write `val x: String?`. A `LocalVariable` is equal BY NAME, so the first cut's per-method name key
merged same-named locals of sibling blocks. A local is now keyed by its declaring element, by identity: the
`LocalVariableCreation` (including a for-each variable, a `for` initializer, a try resource) or the `CatchClause`.
The pass resolves names through block scopes; Java forbids a local to shadow a local, so a name in scope denotes
one declaration. Pattern variables have no declaration key yet; they fall back to a per-method name key and get no
verdict. A lambda resolves captured locals in its enclosing scope. The guava declaration scores are unchanged
(12,766 / 321 / 817).

### 2026-10-06 — B2 properties and D1 annotated Java (first cut)

- **B2** (maddi base): `Value.Nullability` / `ValueImpl.NullabilityImpl`, the state of the value and,
  recursively, of each type argument. *Changed while implementing:* the value stores the states only, not a
  `ParameterizedType`. The declared type is already the field's, parameter's or return type; `applyTo(declared)`
  rebuilds the typed form, and the codec, whose `encodeType` does not carry `NullableState`, stays untouched.
  Encoded as a shape string (`U(N,Q)` is `Map<String, String?>!`). Properties `NULLABILITY_FIELD`,
  `NULLABILITY_PARAMETER`, `NULLABILITY_METHOD`, default UNSPECIFIED, registered with the provider. *Deviation:*
  `NOT_NULL_*` are NOT derived from it yet. Writing them would make `DecoratorImpl` print maddi's `@NotNull`
  everywhere under the `@NullMarked` policy, and change the hints round-trip; that waits for a decision.
- **Writing**: `IteratingAnalyzerImpl.analyze` runs `NullabilityPass` (policy `NULL_MARKED`) after convergence
  when `Configuration.nullability()` is on, and `NullabilityPass.write` stores the verdicts. Overwrite is allowed:
  a re-analysis recomputes from complete facts.
- **D1** (`prepwork/io/NullabilityDecorator`, a separate decorator that wraps another, e.g. `DecoratorImpl`):
  flavours JSpecify, Checker, JetBrains (TYPE_USE), JSR-305 and maddi (declaration); option `writeNonNull`
  (false is the `@NullMarked` style: only `@Nullable`). An annotation type not on the classpath is stubbed for
  printing and import. Nothing is written for primitives, UNSPECIFIED verdicts, or where the source already has a
  null annotation (a contract). Skipped in TYPE_USE flavours: arrays (declaration position would speak about the
  elements) and nested types (`@Nullable Map.Entry` does not compile). Both, and type arguments, need the
  annotation inside the printed type: next. `TestNullabilityAnnotations` pins JSpecify `@NullMarked` and JSR-305
  with non-null output end to end.

### 2026-10-06 — NOT_NULL_* removed; NULLABILITY_* is the only nullness property

Decision (Bart): `NOT_NULL_*` was a relic of an older analyzer; remove it, no backward compatibility.

- **Gone**: `NOT_NULL_FIELD/_METHOD/_PARAMETER`, `ValueImpl.NotNullImpl`, `Value.NotNullProperty`,
  `MethodInfo.isPropertyNullable` (unused). `isPropertyNotNull` (Lombok uses it) now reads `NULLABILITY_*`.
- **Contracts** (`AnnotationToProperty`): maddi's `@NotNull` → N, `@NotNull(content = true)` → N with every type
  argument N, `@Nullable` → Q (it was not read at all before), `absent = true` → U explicitly (it stops a default).
  B1 now reads `content = true` the same way: the value is non-null too. The 38 such hints are all
  `List.of`/`Map.of`/`stream()`, which are non-null themselves, so the earlier "content only" reading was wrong.
- **Library defaults** (`ShallowMethodAnalyzer`/`ShallowTypeAnalyzer`): primitive, fluent and enum-constant → N.
  A return inherits N from an overridden method, else Q; a parameter inherits Q from an overridden method, else N
  (Kotlin's one-nullability-per-chain rule). Otherwise U, where the old default was NULLABLE. Constructors and void
  methods get no value (they used to get a meaningless NOT_NULL).
- **Hint archives** regenerated (`compileAnalysisHints`, on JDK 26, the recorded release, via
  `-Dorg.gradle.java.home`): 4,641 `nullabilityMethod` N, 27 N(N), 11 N(N,N), 4,630 parameter N, 304 field N. The
  libs/support results were regenerated with `GenerateSupportAnalysisResults`; the file name had been stale since
  the e2immu → maddi rename.
- **Printing**: `DecoratorImpl` prints maddi's `@NotNull`/`@Nullable` from `NULLABILITY_*` in explicit mode only
  (the hints round-trip). User-facing null annotations are `NullabilityDecorator`'s, in the chosen flavour.
- **The pass** does not overwrite a declaration that carries a null annotation (contract wins). Using contracts as
  seeds is still M2 work. The simple-name lists moved to `cst-analysis` `NullAnnotations`, shared by B1, the pass
  and the decorator.
- **IDE daemon** (maddi-dist `AnnotationTagger`): `@Nullable` tag from state Q.
- Not touched: `maddi-run-analysis/src/test/resources/json/JavaIo.json`, `JavaLang.json`, fixtures of a
  `@Disabled` test that already use other obsolete keys.

Test suites: maddi 2,388 tests, maddi-mod 1,568, all passing, `TestAnalysisHintsCompiler` included. Guava oracle
unchanged.

### 2026-10-06 — M2: contracts; the JDK null contracts move into the hints

- **Decision (Bart)**: an array gets an element slot in B2. Proposed shape: for an array type, `arguments()`
  holds the element state, so `String?[]` is `N(Q)`; no new format. Implemented with content inference.
- **Library contracts from the hints**: `LibraryNullness` is gone. Its entries are maddi `@Nullable` in the JDK
  shadows (`Map.get/put/remove/...`, `NavigableMap`/`NavigableSet` lookups and polls, `Queue.poll/peek`, `Deque`,
  `BlockingQueue`/`BlockingDeque` timed polls, `comparator()`, `Reference.get`, `ThreadLocal.get`,
  `System.getProperty/getenv`, `Class`/`Throwable` getters, `readLine`, `InvocationHandler.invoke`; parameters of
  `equals`, `contains`, `indexOf`, `Map.get/containsKey/...`). The pass reads a library method's
  `NULLABILITY_*` (state Q) for itself and the methods it overrides. Only methods outside the analysis count:
  the analysed ones' properties may be an earlier run's output. Override inheritance in the shallow analyzer makes
  every `equals(Object)` parameter Q, among others: 156 returns and 169 parameters in the JDK archive. Apart from
  that, the regenerated archive changed no verdict except the "annotated" marker on the 5 added `poll` methods,
  plus the newly shadowed types (`DataInput`, `ThreadLocal`, `InvocationHandler`, `Blocking{Queue,Deque}`,
  `PriorityBlockingQueue`, `Reference`) with their defaults.
- **Source contracts** (`Policy.contracts`, on in every preset; the oracle turns it off with
  `withoutContracts()`, since it measures the inference against those very annotations): a declaration annotated
  nullable (any family, `NullAnnotations.explicitState`) seeds, and so does a call to it used directly; one
  annotated non-null stops null: a seed or an edge into it is dropped (the caller's error, an M5 finding), and its
  verdict is the annotation's.
- **The literal `null`** assigned to a local or field is seeded from the code, not from the links. Fernflower's
  `FlattenStatementsHelper.flattenStatement` is DEGRADED (too big for the link engine, so no links), and its
  `X x = null` locals came out non-null; the Kotlin printer then failed on them. An unreached local of a
  degraded method is now UNSPECIFIED, like its outputs.
- **Rejected**: a general edge from an analysed callee's return to wherever its result is used directly.
  On guava it gave -27 unsafe for +947 noise: the flow-insensitivity of `v = get(k); if (v == null) ...` carried
  into callers. Revisit with M4.

Guava (NULL_MARKED, contracts off): 12,772 agree / 315 unsafe / 817 noise (was 12,766 / 321 / 817); field
unsafe 14 → 8 from the literal-null seeds.

### 2026-10-06 — M4, first cut: use sites cut the flow-insensitive edges

`NonNullFacts` walks each method body (lambda bodies included) in statement order. For every statement it records
the locals and parameters known non-null when the statement starts:
- after `if (v == null) return/throw/break/continue;`;
- inside `if (v != null)`, and in the `else` of `v == null`, with `&&`, `||`, `!` and `instanceof` handled;
- after a dereference `v.m()`, `v.f`, `v[i]`;
- after passing `v` to a parameter that demands non-null (a library `@NotNull`, or a source contract);
- after `assert v != null`;
- after `v = <non-null expression>` (`new`, a literal, a non-null variable, a call whose contract is non-null).

It is conservative where it has to be. An assignment kills a fact. Loops, `try` and `switch` keep only the facts
of variables they do not assign. Two branches that both continue are joined by intersection. Fields are not
tracked.

The declaration pass uses the facts in two places.
- **Link edges** are decided after all statements have been seen. An edge is dropped when, at every statement
  that assigns the recipient, the source can only become the assigned value where it is known non-null. This is
  analysed structurally through `?:`, nested assignments and casts, so the lazy getter
  `return r == null ? field = new X() : r` counts as guarded.
- **An argument** that is a variable known non-null at the call carries nothing to the parameter.

`Report.useSites()` exposes the facts per statement, for the Kotlin printer's `!!` / `?.` decisions.

Measured on guava and rejected: treating `if (p == null) throw` as a non-null precondition rather than a null
test. Guava's own checking methods (`checkEntryNotNull`, `checkElementNotNull`, …) annotate such parameters
`@Nullable`, because their job is to accept null and throw; the rule cost 28 agreements and +3 unsafe.

Guava (NULL_MARKED, contracts off): **12,949 agree / 340 unsafe / 610 noise**. Before M4 it was 12,772 / 315 / 817.
The +25 unsafe are almost all one pattern: `this.mutex = (mutex == null) ? this : mutex`. The field is now
rightly non-null. Before, it was wrongly nullable, and that made the `mutex` parameters of every `Synchronized`
factory nullable "by accident". Guava declares those parameters `@Nullable` by API design: it is the open
public-API-parameter question (§8), not an M4 error.

What is left of the noise:
- 97 parameters compared with null, where the test is not a guard;
- about 85 nulls fanning out through JDK interface parameters to every implementation (`Comparator.compare`,
  `Collection.addAll`, `Map.put`);
- lazy fields read without a local (fields are not tracked);
- no relation between two variables (`if (map.containsKey(k)) map.get(k)`).

Next for M4 are field facts for `this.f` within a method, killed at calls, and the per-dereference decision for
the printer.

### 2026-10-07 — M4: facts inside a statement, and fields of `this`

- **Inside a statement**: the right operand of `&&` sees the left true, the right operand of `||` sees it false,
  and the branches of `?:` see the condition. Every call and every field or array access records the facts at
  that point: `NonNullFacts.at(Expression)` and `nonNullAt(Expression, Variable)`. The declaration pass checks
  call arguments there, so `v != null && accept(v)` and `w == null ? 0 : measure(w)` carry no null. The printer
  can ask per dereference.
- **Fields of `this`**: a non-final field is forgotten at any call, not carried into a lambda body, and forgotten
  after a loop, `try` or `switch` that contains a call. So `if (lazy == null) lazy = "y"; return lazy;` returns
  non-null, but not when a call sits between the check and the return. The Kotlin smart-cast view tracks only
  final fields: Kotlin never smart-casts a `var` property.

Guava: 13,006 agree / 346 unsafe / 546 noise (was 12,949 / 340 / 610). The noise drops by 64: 45 parameters,
13 returns, 6 fields. The 6 new unsafe are parameters every analysed caller now visibly guards, such as
`Lists.indexOfImpl`, called only as `object == null ? -1 : indexOfImpl(this, object)`. Guava annotates them
`@Nullable` for its public API; that is the §8 question again.

### 2026-10-07 — rounds: the pass trusts its own non-null returns

`NullabilityPass.go` now runs in rounds. Round 1 trusts no analysed method's return. Each later round treats the
analysed methods whose return the previous round found unreached by null as known non-null values. This is
sound: a round's reachability over-approximates. Those methods are not degraded and do not return a type
variable. Dropping edges only shrinks what null reaches, so the trusted set grows until it is stable (at most 5
rounds). It catches guava's lazy getter with a factory method,
`return r == null ? keySet = createKeySet() : r`, in which `createKeySet()` is not annotated.

Guava: 13,085 agree / 346 unsafe / 467 noise (was 13,006 / 346 / 546): 79 fewer noise, no new unsafe.

- A value known non-null as a whole guards every source that flows into it: `return requireNonNull(links)` and
  `return checkNotNull(x)` (the call's contract or trusted return is non-null). Type-variable returns are trusted
  too when unreached. Their verdict stays parametric, but no null of this program reaches them, so calls to them
  here are non-null values: guava's own `checkNotNull(T)`.

  Guava: **13,172 agree / 351 unsafe / 374 noise** (was 13,085 / 346 / 467). The +5 unsafe: 3 are the B1 array
  gap (`requireKeys()` is declared `@Nullable Object[]`, meaning the elements, but read as a nullable array; the
  array really is non-null), and 2 are public-API parameters (`StandardTable.containsMapping`/`removeMapping`).

### 2026-10-07 — generic interfaces: no fan-out through a type variable, typed dispatch instead

Null entering a generic interface method reached every implementation through the downward override edge:
`Comparator<T>.compare` led to every `LexicographicalComparator.compare(boolean[], …)`, and `Funnel<T>.funnel`
to `ByteArrayFunnel.funnel(byte[] …)`. The downward edge no longer goes from a type-variable parameter into an
implementation that instantiates it with a concrete type other than `Object`; that mirrors the upward rule.
Soundness comes back where the code tells: at a call whose receiver is the interface itself with a concrete type
argument (`Fn<String, String> f; f.apply(x)`), the argument also flows straight into the implementations whose
parameter is that type ("typed dispatch"). `Object` implementations (`IdentityFunction.apply(Object)`) stay on
the chain. Still a heuristic: a receiver typed through a subtype or a wildcard (`Comparator<? super K>`) does
not dispatch.

Guava: **13,302 agree / 351 unsafe / 244 noise** (was 13,172 / 351 / 374): 130 fewer noise, no new unsafe.

### 2026-10-07 — closed and open world; preconditions

A parameter's verdict comes from the analysed invocations. That is right when they are all the invocations,
and wrong for a library, whose users are callers the analysis does not see. `Policy.world` now makes that a
choice:

- `CLOSED` (the default, unchanged): no analysed call passes null, so the parameter is non-null. This is for an
  application analysed together with its callers, and for whole-program Kotlin translation.
- `OPEN_VISIBILITY`: a parameter that can be called from outside and that no null reaches is UNSPECIFIED. Every
  declaration it flows into is UNSPECIFIED too: a second closure over the same edges, which stops at null
  contracts. "Called from outside" means public, or protected in an extensible type, of a type reachable from
  outside. It also covers overriding such a method, or overriding a library method whose hint does not declare
  the parameter non-null. A library calls its overrides back, as the JDK calls `Comparator.compare`.
- `OPEN`: the same, except for a parameter that the body makes non-null on every normal exit without assigning
  it (`NonNullFacts.nonNullAtExit`). It may be dereferenced, passed to a non-null parameter, or rejected with
  `if (p == null) throw`. A null argument then throws whoever passes it, so the parameter is a precondition and
  non-null (§8).

Preconditions also cross calls: in every world, a round hands the next one the analysed parameters it found to
be preconditions, as `parameterContract` NONNULL. The argument is then non-null after the call. A precondition
only counts at a call when no override can replace the body: the method is static, private, final, a
constructor, or in a final type, or (closed world only) has no analysed override.

Guava (top-level positions only; "undecided" is UNSPECIFIED):

| world | agree | unsafe | noise | undecided |
|---|---:|---:|---:|---:|
| `CLOSED` | 13,306 | 354 | 233 | 1,894 |
| `OPEN_VISIBILITY` | 9,640 | 158 | 236 | 5,753 |
| `OPEN` | 11,133 | 166 | 236 | 4,252 |

Preconditions crossing calls changed `CLOSED` by +3 unsafe and −11 noise. The 8 unsafe that `OPEN` adds over
`OPEN_VISIBILITY` are all array and varargs parameters (`Joiner.join(…, Object... rest)`, `Invokable.invoke`).
That is the B1 misread: guava's `@Nullable Object...` speaks about the elements. What remains in the open world
is mostly arrays as well.

Wiring:
- `Configuration.nullabilityOpenWorld()` (`setNullabilityOpenWorld`) makes the analyzer run `OPEN`.
- In the `@NullMarked` style, a missing annotation reads as non-null, so `NullabilityDecorator` cannot leave an
  undecided position out. A method or constructor with an UNSPECIFIED parameter or return (other than a type
  variable, which is parametric already) gets `@NullUnmarked`, and its non-null positions get an explicit
  `@NonNull`. A field cannot be unmarked on its own, so an undecided field gets `@Nullable`, the reading that is
  safe for whoever reads it. Only JSpecify has an unmarked annotation; the other flavours still leave
  UNSPECIFIED out.

### 2026-10-07 — array element slot (content inference, first part)

Implements the decision recorded at M2 (an element slot for arrays).

**Model** (maddi `4f6bcb3fa`):
- `ParameterizedType.componentType()` is the element type as this use states it: its own `nullable()` and
  TYPE-USE annotations, recursively for nested arrays. `withComponentType` sets it. Like `nullable()`, it is part
  of `equals` and not of `hashCode`.
- `NullabilityImpl` gives an array its element as its one argument: `String?[]` is `N(Q)`, and `List<String>[]`
  is `N(N(N))`.
- The front end puts a type-use annotation that is about the elements on the element type (JLS 9.7.4). That
  covers `@Nullable String[]` in declaration position on a parameter or return, and `List<@Nullable String[]>`.
  `String @Nullable []` stays on the array. The Java printer prints each one in its place.
- `DeclaredNullability` reads the array and its elements apart, which ends the B1 array misread. On a field, a
  type-use annotation that stays with the declaration is read as the elements'.

**Pass**:
- Nodes: `Content(node)` stands for the elements of an array node; `a[i]` is `Content(a)`.
- Seeds:
  - element writes (`a[i] = null`, an initializer's `{…, null}`);
  - `new T[n]` without initializer (Kotlin's `arrayOfNulls`), but not `new T[0]`;
  - `null` in a varargs position;
  - element contracts (Policy.contracts).
- Edges:
  - element reads (`x = a[i]`, `for (T x : a)`);
  - varargs arguments into the parameter's elements.
- Arrays are invariant in what they hold, so every flow between two array nodes ties their Contents both ways.
  That includes a flow that the top-level verdict drops because the array is non-null there. The tie does not
  pass through a library method, whose parameter would join the arrays of all its callers.
- In the open world, the elements of an outside-callable array parameter are UNSPECIFIED.
- `NullabilityComparison` scores elements as `Depth.ELEMENT`. `NullabilityDecorator` writes the element state in
  declaration position for a TYPE_USE flavour, which is where JLS puts the elements. It writes nothing when the
  array itself is nullable.

Guava, `CLOSED` (top level; was 13,306 / 354 / 233):

| depth | agree | unsafe | noise |
|---|---:|---:|---:|
| top | 13,349 | 294 | 255 |
| element | 181 | 33 | 82 |

`OPEN`, top level: 11,138 / 112 / 258 (was 11,133 / 166 / 236).

The B1 fix accounts for most of the top-level change (−60 unsafe). Element reads (`x = queue[i]` in a
`MinMaxPriorityQueue` with holes) carry some null into parameters guava declares non-null (+22 noise).

Element disagreements:
- **Unsafe:**
  - 18 are `toArray()` overrides. `Collection.toArray()` has nullable elements in JSpecify's JDK, but the hints
    have no element states, and maddi's `@Nullable` has no way to say "the elements" (open).
  - The rest are public varargs, `Object... args`. That's the closed world again; in `OPEN` they are UNSPECIFIED.
- **Noise:** mostly `new T[n]` arrays filled before they escape. Kotlin would need a non-null element type there
  too: `Array(n) { … }`, not `arrayOfNulls`.

**Fernflower (Kotlin printer)**: the ratchet goes from 125 to 121 compiling files (446 → 463 errors).
- Element states do not reach the output: `KotlinTypeName` prints an array's elements without a state, so the
  144 `Array<IntArray>` errors from `int[][][] stack_impact = {{null, null}, null, …}` remain. The pass now has
  `stack_impact: int[]?[]?[]`.
- The loss comes from top-level verdicts that element reads now make nullable (`int[] row = table[i]`). The
  printer then needs a `!!` it does not write (nullable receivers 17 → 24; "smart cast impossible" on `var` array
  fields).
- A local experiment that only let `KotlinTypeName` print the component state made it worse (120 files, 525
  errors). The `arrayOf<…>` type arguments, element reads (`a[i]!!`) and calls (invariant `Array<T?>`) must follow
  the element state as well. That is printer work.

### 2026-10-07 — type arguments (content inference, second part)

A node with declared type `C<A0, …>` has a slot `Arg(node, i)` per type argument, recursively. An array's type
arguments are its element's. The link engine's hidden content gives the facts:
- a one-parameter type's virtual field (`list.§$s`, `opt.§$`) is `Arg(list, 0)`;
- a multi-parameter container's slice (`map.§$$s[-1]` keys, `[-2]` values) is `Arg(map, k-1)`;
- a generic holder's field typed by its class's type variable, seen on another object (`b.t` for `Box<String> b`),
  is `Arg(b, index of T)`.

**Writes.** An argument passed to a parameter typed by a type variable of the callee's class (`add(E)`,
`put(K, V)`, `set(T)`) flows into the receiver's slot. The receiver's arguments are matched to the class's by
position, so only when the arities agree (`receiverSlots`). A library parameter typed that way no longer takes
the argument itself: it carried every `Map.put(k, null)` down into all analysed `Map` implementations.

**Reads.** Membership links (`∈`/`∋`) count only as reads: into a return value, or into a local or field in the
statement that assigns it. The engine writes membership both ways for reads and writes alike, and derives more by
transitivity. `Maps.safeGet`'s `return null` came out as "null is an element of the map", 238 noise. Content copied
between slots (`⊆`/`⊇`: `new ArrayList<>(in)`) flows one way. A slot is never written through `≡`. A for-each
over an `Iterable<T>` reads `Arg(…, 0)`.

**Invariance.** Every flow between two values of generic types with the same number of type arguments ties their
slots by position, both ways (one way into a `? extends` argument). Slots typed by a generic method's own type
variable are not tied: each call instantiates it anew, and `ImmutableMap.copyOf` otherwise joined the maps of all
its callers.

**Other rules:**
- **Holder fields:** a null in a holder field (`T t` in `Box<T>`: its default value, `set(null)`) reaches the slot
  of every `Box<…>`. This over-approximates (`Box<Integer>` elsewhere becomes `Box<Integer?>`).
- **Contracts:** `List<@Nullable String>` seeds a slot and `@NonNull` stops null there; the written annotations
  override the inferred states.
- **Open world:** the type-argument slots of an outside-callable parameter are UNSPECIFIED.
- **`Void`:** a `Void` position is always nullable (`Future<Void>`).

Guava, `CLOSED`:

| depth | agree | unsafe | noise |
|---|---:|---:|---:|
| top | 13,342 | 293 | 264 |
| type argument | 1,526 | 29 | 57 |
| element | 173 | 33 | 90 |

The top level is unchanged (13,349 / 294 / 255 before type arguments). Of the 29 type-argument unsafe, most are
guava's API choices that no analysed caller shows: `Ordering<@Nullable Object>` from `allEqual()`/`arbitrary()`,
`MultimapBuilder`'s nullable key and value bounds, `Functions.constant`. `OPEN`: type arguments
1,026 / 28 / 57, with 627 undecided.

### 2026-10-07 — Kotlin: content writes are asserted (`Policy.KOTLIN`)

Decision (Bart): when a value enters a container, the `?`-vs-`!!` choice is made at the write. Under
`Policy.assertContentWrites` (`Policy.KOTLIN`), a value that is nullable only because of a library contract
(`Map.get`, …) does not make a type argument or an array's elements nullable where it is written. The slot stays
non-null, and the printer asserts there (`list.add(map.get(k)!!)`). A null literal and a source annotation still
make the slot nullable. A second, strict closure from the seeds that are not library contracts decides which
writes count. Java annotation output keeps the faithful policy.

Fernflower with type arguments, printer not yet adapted:

| verdicts | compiling files | errors |
|---|---:|---:|
| faithful (`NULL_MARKED`) | 87 | 1,123 |
| `KOTLIN` | 89 | 1,074 |

Of the 232 nullable type-argument slots under `KOTLIN`:
- 96 come from a null literal, 63 from a `null` argument, 13 from an `@Nullable` element type in fernflower's own
  source (`DoStatement.initExprent`), 17 from comparison with null, and only 11 from `Map.get`.
- Fernflower does store nulls in its lists (`IfStatement.<init>` puts null into `headexprent`), so these slots are
  genuinely `List<X?>`.

The errors are two printer gaps, both on the printer side:
- a read from a `List<X?>` needs `!!`;
- a constructor call's type arguments must follow the target (`ArrayList<Exprent?>()`).

### 2026-10-07 — after the merge: fernflower feedback

After the merge, the printer session's list of remaining fernflower errors pointed at gaps in the verdicts:
- **Content copies:** `addAll(Collection<? extends E>)`, `putAll`, `new ArrayList<>(c)`: the argument's slots now
  flow into the receiver's, or into the new object's target.
- **Writes need a modifying method.** Only a method that modifies its receiver writes into its slots
  (`NON_MODIFYING_METHOD`). `Comparator.compare(T, T)` had made comparators' type arguments nullable, and from
  there 266 guava positions.
- **Call results:** an analysed method's result ties content slots, except where its type arguments are type
  variables. A read of a generic receiver (`map.get(a).get(b)`) is that receiver's slot, at any depth.
  `Policy.callResults` (on in `KOTLIN`) also makes a variable assigned a nullable result nullable, including
  inside an expression (`(res = f()) != null`).
- **Indirect evidence under `KOTLIN`:** a field's default value and a comparison with null now count like a
  library contract, so they are asserted at a content write instead of spreading.
- **Argument guard:** it uses the facts at the moment the callee runs. In `stats.addWithKey(x, x.id)` the call
  happens only with a non-null `x`.
- **`NonNullFacts`:**
  - A while/for loop's condition no longer sees facts about variables the loop assigns.
  - Each statement of an old-style switch starts from the facts before the switch, because every case label
    is a jump target.

Guava, `CLOSED`, type arguments: 1,551 / 29 / 32 (was 1,526 / 29 / 57). Top level and elements are unchanged.

### 2026-10-07 — lambdas, switch expressions, guards, returned nulls

Further fernflower errors traced to the verdicts (154 → 160 compiling files, 133 → 109 errors):
- **Lambdas:** a lambda's synthetic method overrides nothing in the model, so a null passed to
  `ExprentIterator.processExprent` never reached the lambdas implementing it. Kotlin types those lambdas'
  parameters by the functional method (`Exprent?`), and the unguarded dereferences failed. The pass now treats a
  lambda as overriding its functional interface's single abstract method, which gives it the same override edges
  as an anonymous class.
- **Switch expressions (`NonNullFacts`):** each arm starts from the facts after the selector, and only what every
  completing arm establishes is kept. Before, a dereference in one arm leaked into the next, and the printer
  left out a `!!` that Kotlin needs (`cn.value` in `StructAnnotationAttribute`).
- **Guards:**
  - A branch of `?:` that is a call not given the variable cannot return it, so
    `a == null ? Collections.emptyList() : a` no longer carries `a`'s null into the field.
  - A local that reaches another object's field through a constructor argument is judged by the facts when that
    call runs, as `argument()` judges the parameter.
- **Returned nulls** (reported by the diagnose session): with `nullConstantReturns`, a callee's `return null`
  crosses the call as the callee's own marker, and the pass seeded it as "null in" the caller. The pass now
  finds the analysed method whose body holds that literal (by identity) and adds an edge from that method's
  return. The cause chain reads `return relay <- return find <- null in find`. A marker whose literal isn't
  found, such as one from a decoded summary, is still seeded where it arrives.

Guava, `CLOSED`: unchanged, apart from +2 agreeing parameters. `OPEN`: top-level unsafe 109.

Fernflower items left for the printer:
- Diamond and `computeIfAbsent` constructor calls are spelled with nullability-free type arguments (about 25
  errors).
- `Objects.requireNonNull(x).m()` needs `x!!`.
- `Map.of(k, new Integer[]{..., null})` gets `Array<Int>` values. This one is a verdict gap too: a library
  factory's method type variables don't tie the arguments' content into the result's slots.
