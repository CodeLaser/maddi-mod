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
- `Map.of(k, new Integer[]{..., null})` is printed as `arrayOf<Int>(..., null)`. Since the next entry, the field
  itself is `MutableMap<Int, Array<Int?>>`.

### 2026-10-07 — library factories

The pass now handles a library method whose return type argument `j` is a method type variable `T`, such as
`Map.of(K, V, ...)`, `List.of(E...)`, `Arrays.asList(T...)` or `Map.entry`. Each argument passed for a `T`, or as
an element of a `T...`, is a value of slot `j` of the result, which is the target's slot. This covers:
- a null literal;
- an array created with null elements, at any depth;
- a variable or analysed result: an edge into the slot, and its content slots coupled.

It applies to assignments, returns and field initializers. A parameter the library declares nullable is skipped,
because accepting null is not storing it as a `T`. `Optional.ofNullable(t)` already got its slot from the link
engine's hidden content, so it is unchanged.

Guava: unchanged in every row. Fernflower: unchanged at 160 / 109. `SecondaryFunctionsHelper.mapNumComparisons` is
now `MutableMap<Int, Array<Int?>>`, and its four remaining errors are the printer's explicit `arrayOf<Int>`.

### 2026-10-07 — a class's type variable: the receiver's slot

`ListStack<T>.push(T item)` is passed null by its callers (`stack.push(null)`). The pass made `item` nullable, and
the printer wrote `push(item: T?)`, which then failed at `add(item)` (`T?` is not the `ArrayList<T>`'s `E`). Both
outputs want something else:
- JSpecify: `ListStack<T extends @Nullable Object>` with `push(T)`, and a `ListStack<@Nullable X>` at the use;
- Kotlin: `push(item: T)` and a `ListStack<X?>`.

The null belongs to the instantiation. So a call with a receiver variable now writes such an argument into the
receiver's slot (`receiverSlots`), as it already did for library classes, and not into the parameter. This applies
only to a concrete method that nothing overrides. An abstract method such as guava's `Function.apply(F)` is a
consumer, and its null must still reach the implementations; without this restriction guava had +5 unsafe. A call
on `this`, or one to a non-modifying method, still makes the parameter nullable. A null of the class's own
(`T peek() { return ... ? null : ... }`) stays `T?`.

This also removes the holder over-approximation from the type-arguments test: `boxNull(Box<String> b)` makes only
its own box `Box<String?>`. Before, the null reached `Box.t`, and from there the slot of every `Box` (`intBox`,
`unbox`).

Guava, `NULL_MARKED`: parameters 6147 / 248 / 147, undecided 44 (was 6150 / 247 / 147, 42). The new unsafe one is
`LocalCache.hash(@Nullable Object)`: its null used to come through `put(K key, ...)`, whose `K` guava annotates
non-null. Fernflower: 161 files, 102 errors (was 160, 109).

A related fernflower error that the verdicts can't fix: `FastSparseSetIterator.next()` really returns null, which
breaks `Iterator.next(): E`. Kotlin can't override with `E?`, so the printer has to keep `E` and write
`null as E`.

### 2026-10-07 — `super(...)` and `this(...)` arguments

An explicit constructor invocation carries no argument links (`LINKED_VARIABLES_ARGUMENTS`), so `argument()`
returned before adding any edge. A variable passed to `super(...)` or `this(...)` therefore never reached the
called constructor's parameter, at the top level or in its slots. In fernflower, `StructField(Map<String?, ...>)`
called `super(accessFlags, attributes)` while `StructMember(Map<String, ...>)` stayed clean. When there are no
links, the argument's own node (`argumentNode`) now flows into the parameter. That also couples their slots.

Guava, `NULL_MARKED`: unsafe 348 → 339 (parameters 248 → 241, fields 8 → 6, returns 39 → 38); noise 403 → 425.
The new noise is real flow from imprecision further upstream (`TypeToken.of(Class)` fed by
`Class.getComponentType()`; `ImmutableSortedSet`'s comparator chain). Fernflower not measured: the printer session
was updating the ratchet at the time.

### 2026-10-07 — arrays filled by the next loop

`T[] a = new T[n]; for (int i = 0; i < n; i++) { ... a[i] = v; ... }` (also with `i < a.length`): every element is
written before anything reads the array, so the creation leaves no null, and each `v` flows into the elements
through the links. The rule is narrow on purpose:
- a one-dimensional creation;
- followed directly by the loop: from 0, one step at a time, with no other assignment to `i`;
- `a[i] = ...` is a statement of the loop body itself (not under an `if`);
- the body has no `break`, `continue` or `return`.

Guava: −3 noise (`AbstractCompositeHashFunction`'s hashers). Most of guava's "array created with null elements"
noise comes from elsewhere: `ImmutableMap.Builder.entries` (guava annotates it `@Nullable Entry<K, V>[]` and casts
the nulls away once it is full), coupled both ways with `ImmutableList.array`, `Joiner.join(Object[])` and
`TypeToken`'s `Type[]`. A cast barrier on content (`(Entry<K, V>[]) entries`) was tried and dropped. It made no
difference: the link engine's transitive links bypass the cast, and cutting those too lost a real flow
(`HashBiMap.hashTableVToK`).

### 2026-10-07 — array content: backward only where written through

`coupleContent` tied two arrays' elements both ways on every flow, because Kotlin's `Array<T>` is invariant. Java
arrays are covariant, and so is JSpecify, so for Java annotations an element only needs to flow forward. The
exception is aliasing: in `void f(Object[] a) { a[0] = null; }` the null lands in the caller's array. Outside
`Policy.assertContentWrites` (`KOTLIN`), the backward edge is now added only when the downstream array is written
through, directly (`a[i] = v`, a null stored into its elements) or via an array it flows on into.

Guava `NULL_MARKED`: noise 422 → 414, unsafe unchanged (339). Dropping the backward edge altogether gave
noise 386 but unsafe 346: those 7 come from writes that this rule doesn't recognise (library writes such as
`System.arraycopy` are one possibility, not confirmed). Fernflower uses `KOTLIN` and is not affected.

### 2026-10-07 — diagnose feedback (nacos): identity returns, null-check predicates

From the diagnose session's first corpus run (nacos) of its rules nullIntoNonNull, nullDereference and
redundantNullCheck:
- **Identity returns:** the links' `≡` is transitive. In `checked = check(key)`, with `check` returning its
  argument or null, `key ≡ return ≡ null` attached the callee's null to the argument, and from there to the
  callee's parameter (`SystemEnvPropertySource.getProperty`). A top-level variable now takes a null marker only in
  a statement that assigns it, or as a return. Slots keep their call-side writes. Guava: unchanged.
- **Null-check predicates (`NullPredicates`):** `if (StringUtils.isNotBlank(s)) s.trim()` and the like.
  - Library predicates come from a table: commons-lang and Spring `StringUtils`, `Objects.nonNull`/`isNull`,
    guava's `Strings.isNullOrEmpty`, the `CollectionUtils`/`MapUtils`/`ArrayUtils` families.
  - Analysed boolean methods are inferred by evaluating the body with the parameter null. Every path must return
    the same constant; a `throw` counts for neither. This runs to a fixed point, so
    `isNotBlank(s) { return !isBlank(s); }` follows `isBlank`.
  - `NonNullFacts.whenTrue`/`whenFalse` treat them like `s != null`, but not for Kotlin's smart casts: Kotlin
    doesn't see a Java method as a null check.
  - Guava: noise 414 → 410, unsafe +1 (`StandardTable.removeColumn`). That parameter's callers now guard it with a
    predicate, but guava annotates it `@Nullable` anyway.

Not done yet, from the same report: trusted returns stay optimistic for a method that wraps an unhinted library
call (`findConfigInfo4GrayState` returning `databaseOperate.queryOne(...)`). Also out of reach: a null that depends
on the object's subclass.

Trusted returns for library wrappers were tried: a method returning an unhinted library call's result (directly,
through a local, or through another such method) was not trusted non-null at its call sites. Guava: +13 noise,
unsafe unchanged. Not the default. diagnose's redundantNullCheck works around it with its own facts; an opt-in
policy flag is possible if a consumer needs it.

### 2026-10-08 — the printer's "nullable into non-null" list

The printer session listed 512 places where `!!` asserts a nullable value into a declaration the verdicts keep
non-null (ASSERT_INTO_NON_NULL). Many follow from rules the pass has on purpose: KOTLIN asserts indirect
evidence at content writes, and an element read passed as an argument is asserted there. Some were verdict gaps:
- **Generic results (`Policy.callResults`).**
  - A class type variable's own null (`E getWithKey(K k) { ... return null; }`, `E?`) holds for every
    instantiation, so the call result now reaches the variable it is assigned to (`BasicBlock block =
    blocks.getWithKey(j)`).
  - The same holds for a method type variable, unless a parameter of that very type could carry the caller's null
    back out (`<X> X id(X x)`): `<T> T getAttribute(Key<T>)` returns a map lookup.
  - A read of the receiver's slot (`st = stat.getStats().get(0)`) is an edge into the target.
- **Slots through supertypes (`slotIndex`).** On a `VBStyleCollection<Statement, Integer>`, which extends
  `ArrayList<E>`, `get` returns ArrayList's `E`, which is the receiver's argument 0. Reads (`argumentNode`) and
  writes (`receiverSlots`) used to require the receiver to have exactly as many type arguments as the callee's
  class.
- **Closure-only edges.** The links' `≡` is transitive and repeats through later statements and method summaries.
  In `ImportCollector.getNestedName`, `node.simpleName` (compared with null) was linked to
  `node.parent.classStruct.qualifiedName` in every statement, with no assignment between them, and from
  `StructClass.qualifiedName` the null reached most of fernflower.
  - An edge into a top-level node now needs an observation: a statement that assigns the recipient (guarded,
    unguarded, or with a value not seen through), or that passes the source as an argument.
  - A call value counts as not seen through even when it does not mention the source, because an alias can carry
    it (`table = this.table; return table.get(i)`).
  - Slots keep their unobserved edges, since calls write them.
  - The `qualifiedName` hub is not gone yet: it now arrives through `getNestedName`'s local `outerShortName`, the
    same artefact by another route. It is to be traced further.
- **Lookup arguments.** `contains(child)` does not make `child` known non-null afterwards (a regression test). That
  coupling was the printer's: its `kotlin.run { }` scopes blocked kotlinc's smart casts.

Guava `NULL_MARKED`: noise 405 → 397, unsafe 340 → 341 (`AbstractFuture.appendUserObject`). Fernflower on this
branch, without the printer's latest commits: 184 files, 28 errors, unchanged. The effect is on the printer's
ASSERT_INTO_NON_NULL count, which the printer session will measure after the merge.

### 2026-10-08 — the `qualifiedName` hub; try-with-resources

- **Argument observations.** `linkEdge` counted a statement that passes the source as a call argument as an
  observation of the edge, for any recipient. In `getNestedName`, `mapSimpleNames.put(outerShortName, ...)` thereby
  confirmed the closure link `outerShortName → node.parent.classStruct.qualifiedName`. It now counts only when the
  call can reach the recipient: a field whose base object the statement assigns (`converter = new X(..., v)`) or
  hands to the call as receiver or argument. A local, a parameter or a return is never written by a call.
- **Try-with-resources.** A resource (`try (In in = open(name))`) is a statement of its own, with its own variable
  data. The pass read neither its links nor its declaration seeds. Since 42ccad18 (a null marker sets a variable
  only where it is assigned) the null from `getClassStream` therefore never reached `in`, which fernflower's
  `ContextUnit.reload` passes on. Each resource is now handled as a declaration statement.

Guava unchanged. Fernflower, merged with the printer's latest: 190 files, 20 type errors; the printer's
ASSERT_INTO_NON_NULL 510 → 348 (with cba2d20a).

### 2026-10-08 — checked array elements; what is left of the printer's list

- **Checked array elements.** The Java facts now track an element with a constant index of a local or parameter
  array: `Object[] res = f(); if (res[0] != null) g((X) res[0]);` (fernflower's `AssertProcessor`). The fact is
  forgotten at any call (one may write the array) and when the array variable is assigned. Kotlin does not
  smart-cast an element, so the printer asserts there instead. That adds 2 ASSERT_INTO_NON_NULL (`varmaparr[1]`),
  which are correct. Guava and fernflower are otherwise unchanged.
- **What is left of ASSERT_INTO_NON_NULL (350) is mostly policy, not inconsistency:**
  - `for (Statement st : stat.getStats()) recurse(st)` (34): `Statement.stats` is nullable only by indirect
    evidence (`Statement.first`'s default value), which `KOTLIN` asserts where it meets a non-null declaration.
  - `FunctionExprent.lstOperands` reads (32): `IfExprent(int, ListStack, BitSet)` delegates `this(null, ...)` and
    then assigns `condition`. In Java the null is never seen from outside, but in Kotlin the private constructor
    stores it, so the field stays nullable unless the printer restructures the constructors.

### 2026-10-08 — switch-expression block arms

- **Block arms.** `handleBlock` stops its expression walk at every `Block`, because nested blocks reach it as sub-blocks
  of the statement. A switch expression's block arm (`case K -> { ...; yield v; }`) is inside an expression, so it
  never did: its statements contributed no links or seeds, and its locals had no verdict. `indexFills` had the same
  gap. Fernflower's `StructTypeAnnotationAttribute.parse` declares and fills `Offsets[] offsets` in such an arm. With
  no local verdict, the printer declared it `Array<Offsets?>` and passed it to `LocalvarTarget(Array<Offsets>)`. Both
  walks now descend into block arms. Expected: that type error goes away; other arms' locals get verdicts.
- **Call results among alternatives.** A library call's nullable result seeds its target only when it is the value
  itself. Fernflower's `MatchEngine` assigns `value = switch (property) { case ... -> stat_type.get(strValue); ... }`,
  and the `Map.get` results inside the arms were not seen. `callResult` now looks through a conditional's branches
  and a switch expression's arms (`-> v`, and each `yield v` of a block arm). Expected: MatchEngine.kt:42 goes away.
- **Slots written through a getter.** A null passed to a class-type-variable parameter goes to the receiver's slot
  only when the receiver is a variable. Fernflower's `wrapper.getDynamicFieldInitializers().addWithKey(value, key)`
  (InitializerProcessor) therefore made `VBStyleCollection.addWithKey(E?)`, and its `super.add(element)` failed
  in Kotlin. An analysed method's result, with concrete type arguments, is now a slot receiver too. Its slots are
  those of what it returns. Expected: both VBStyleCollection.kt errors go away, and
  `ClassWrapper.dynamicFieldInitializers` becomes `VBStyleCollection<Exprent?, String>`.

Guava (all four policies) unchanged.

### 2026-10-08 — asserted at the declaration (Kotlin)

- **`Policy.assertAtDeclaration`** (on for `KOTLIN` only). Fernflower's `ControlFlowGraph.setExceptionEdges` runs
  `BasicBlock block = blocks.getWithKey(j); protectedRange.add(block); block.addSuccessorException(handle);`. The
  `!!` at the dereference comes after the null has reached `protectedRange`'s slot, and from there
  `ExceptionRangeCFG.protectedRange`. The result was ExceptionDeobfuscator.kt:211 (`MutableList<BasicBlock?>` into a
  `Collection<BasicBlock>`).
  - **The printer's half** (maddi 9942ae3d0): it asserts at the declaration instead
    (`val block = blocks.getWithKey(j)!!`, reported as ASSERT_AT_DECLARATION) when
    `NullabilityVerdicts.assertedAtDeclaration` says so.
  - **The pass's half: which locals.** A local qualifies when it has an initializer other than `null`, is never
    assigned again in the rest of its block, and some later statement of that block dereferences it unconditionally.
    "Unconditionally" means a call on it, a field, an element or `.length`, outside `?:` branches, the right side of
    `&&`/`||`, lambdas and switch arms. No statement in between may contain a return, throw, yield, break or
    continue.
  - **Among those, the asserted locals** are the ones null reaches in a first closure. Each becomes a barrier like a
    non-null contract, and the closure runs again. The local's own verdict is then non-null, and nothing downstream
    gets null from it. Its slots are unaffected.
  - In Java, a null there would throw at the dereference anyway. The assertion moves the NPE forward, past whatever
    the statements in between do.
- **Switch-arm fill, printer side.** On fernflower the pass now gives `StructTypeAnnotationAttribute.parse`'s
  `offsets` an `Offsets[]` with non-null elements, yet StructTypeAnnotationAttribute.kt:45 remains. The remaining
  `Array<Offsets?>` comes from how the printer prints the filled array inside a `when` arm.
- **A field of this, dereferenced.** `NonNullFacts.effects` only counted a field access as a dereference of its
  scope when the scope chain did not end at `this`. So `this.first.id` never established `this.first`. In
  fernflower's `RootStatement`, `DoStatement` and `CatchStatement` constructors,
  `first = head; stats.addWithKey(first, first.id);` therefore wrote a nullable `first` into `Statement.stats`'s
  slot, although the second argument dereferences it before the call. Only `this.f` itself, whose scope is `this`,
  is now exempt. The explanation of a node reached by a written null now shows that chain rather than the first one
  found, which had named `Statement.first`'s default value. Guava: +1 agree, -1 noise under every policy.
  Expected on fernflower: `Statement.stats` loses its nullable elements. That should clear DomHelper:174,
  SwitchPatternHelper:537/538, and probably SwitchPatternHelper:1563 and FinallyProcessor:249.

### 2026-10-08 — tried and reverted: slot ties around fernflower's statement graph

Measured by the printer session on fernflower (maddi af0fd470c). Devel with 3564fef8 was at 196 files / 4 errors;
**5c817266 (`this.first.id` dereferences `this.first`) gives 197 / 3**, and the branch is truncated there.
Reverted, each a net loss:
- **32f0a688, a slot read as a receiver** (`ranges.computeIfAbsent(h, …).addAll(c)` ties the map's value slot):
  196 / 5. It fixes ExceptionDeobfuscator:211 but makes the FastFixedSet type variables disagree.
- **a0a80ec0, no tie between a class type variable and a concrete argument:** 197 / 7.
- **929948ce, a call result's slots tied both ways to the receiver's:** 193 / 11. It spreads `Int?` through
  every FastFixedSet/FastSparseSet user.
- **f7c595bd, preconditions asserted at the call plus a lookup's miss as indirect evidence:** 187 / 25.
  Implementations went non-null while their interface (`ExprentIterator.processExprent`,
  `NewClassNameBuilder.buildNewClassname`) stayed nullable. An override family has to move together, interface
  included. Moving the NPE to the call was also not approved.

The underlying problem: fernflower's statement graph really can hold null.
`SwitchStatement.collectExitEdgesIndices` does `nodes.add(null)` into `caseStatements`, and those reach the edge
maps through `new StatEdge(BREAK, statement, last)`. Kotlin's invariance then needs every slot connected to them to
agree, while the pass ties slots only along the flows it sees, so partial fixes move the disagreement around.
Printing never-modified collection parameters as read-only (covariant) is the printer-side lever.

### 2026-10-08 — unobserved before the dereference (Kotlin reporting)

The user decided that an assertion at the declaration is reported as INFO (ASSERT_AT_DECLARATION_UNOBSERVED)
rather than BEHAVIOUR_CHANGE, but only when the linking engine proves that the statements between the declaration
and the dereference do not refer to the local. `Report.unobservedBeforeDereference` answers that for an asserted
local:
- no statement strictly in between refers to the local directly;
- no variable such a statement uses is linked to it, or to a variable built on it (`block.f`), by a link of the
  identity family (`≡`, `←`, `→`). The other natures of the link algebra (`∈`, `∋`, `~`, `⊆`, `≺`, `≈`, …) leave
  the two variables possibly unrelated;
- every statement in between has its links, so a degraded method gets no proof.

So in fernflower's ControlFlowGraph:337, `from = instrBlocks.get(a); to =
instrBlocks.get(b); … from.id`, `from ∈ instrBlocks` and `to ∈ instrBlocks` relate neither to the other. ControlFlowGraph:351
(`protectedRange.add(block)` before `block.addSuccessorException`) refers to it, so it stays a behaviour change.
No verdict changes.

### 2026-10-08 — nulls the Kotlin translation must carry (runtime check)

The printer session now runs fernflower's own test suite against the compiled Kotlin translation. Three verdicts
asserted (`!!`) where Java carries a null on, so the translation threw an NPE:
- **A null alternative in an array initializer.** `new String[]{…, monitor ? "1" : null}`
  (FlattenStatementsHelper.saveEdge) and `new BasicBlock[]{…, last ? successor : null}`
  (FinallyProcessor.compareSubGraphsEx). `seedInitializer` only saw a bare `null` element; it now walks each
  element's alternatives.
- **A library write through a lookup.** `ranges.computeIfAbsent(id, k -> new ArrayList<>()).add(array)` writes
  the map's value. A lookup is now a write receiver, but only for a library callee. Through an analysed generic
  class, the same receiver made fernflower's FastFixedSet type variables disagree (32f0a688, reverted).
- `DecHelper.isChoiceStatement`'s `List<? super Statement>` was already nullable in the pass: `post = null`
  reaches `lst.add(0, post)`.
- `JarFile.getManifest()` (nullable) has no JDK hint yet, so `ContextUnit.setManifest` stays non-null.

Guava unchanged under all four policies.
- **A library copy as an argument.** `getUniqueNext(graph, new HashSet<>(mapNext.values()))` now copies the
  map's values into the parameter's slots. `slotOf` reads a library view of a generic receiver
  (`values()`: `Collection<V>`, slot 0 is the map's V), and a library copy constructor passed as the argument
  ties its argument's slots to the parameter's. Without that, mapNext's nullable components (above) stopped at the
  call and FinallyProcessor.kt:358 failed. Guava: NULL_MARKED unchanged; OPEN* -1 agree / +1 undecided.
- **Still open:** with 06fc1e65 the write `mapStates.get(type).set(index, value)` in `Statement.changeEdgeNode` is
  seen. It carries `IfStatement.ifstat`'s null (through `replaceStatement(first, firstif.getIfstat())`) into the
  statement graph's lists. From there it reaches DomHelper's FastFixedSets and `FastFixedSetFactory.spawnEmptySet()`
  as `E?` (FastFixedSetFactory.kt:19/33). In Java a null `newstat` throws in `replaceStatement` right after that
  write, so cutting it at the call (preconditions, f7c595bd) is a decision for the user.

### 2026-10-09 — issue #21 measured; substitution for class type variables; stream pipelines

**What the pass's graph says** (a reflection probe over `successors`/`reached` on fernflower, policy `KOTLIN`, at
4dc2fec6): 4,956 symmetric slot ties forming 424 components of two or more slots, **none mixed** (every component is
nullable as a whole or not at all), and **no slot-to-slot edge from a nullable slot into a non-null one**. The
per-group consistency the issue asks for already holds; a union-find over the ties the pass has would change nothing.
The three kotlinc errors at 4dc2fec6 (197 files; ExceptionDeobfuscator.kt:211 is gone since 06fc1e65) come from
relations the pass does not model, or models in the wrong place:

- **FastFixedSetFactory.kt:19/33.** Chain: `null in IfStatement.<init>` → `IfStatement.ifstat` → `replaceStatement`'s
  `newstat` → `changeEdgeNode`'s `value` → `mapStates.get(type).set(index, value)` → `Statement.mapPredStates` →
  `getNeighbours()` → DomHelper's `lstSuccs`, `pred`, `setFlagNodes: FastFixedSet<Statement?>` → **coupleContent's
  symmetric tie** between that concrete slot and the type-variable slot of `spawnEmptySet(): FastFixedSet<E>`, so `E`
  itself came out nullable and the class printed `FastFixedSet<E?>`. A consistent group, but one Kotlin cannot write:
  a class's `E` cannot be `E?` on account of one instantiation. (Not `caseStatements`: its null is transient,
  `replaceNullStatementsWithBasicBlocks` replaces every null before the field is assigned and before
  `remapWithPatterns` reads it; the `!!` in `addEdgeDirectInternal` is safe at run time. The field's `?` is forced only
  by invariance at `this.caseStatements = caseStatements`.)
- **SwitchPatternHelper.kt:1559.** `FullCase.exprents` (a record component, `@NotNull List<Exprent>`) against
  `caseValues: List<List<@Nullable Exprent>>`. The nullable elements travel `getCaseValues().get(i)` → `caseValue` →
  `new CaseValueWithEdge(caseValue.get(ind), …)` → `stream().map(t -> t.exprent).collect(toList())` →
  `sortedCaseValue` → `new FullCase(sortedCaseValue.get(ind), …)` → `cases.stream().map(t -> t.exprents).toList()` →
  `getCaseValues().addAll(…)`. The pass had no model for a stream pipeline (`map`'s and `collect`'s results are method
  type variables, `argumentNode` gives nothing): every predecessor of the field's element slot was unreached.

Neither position is read-only, so the printer's covariant `List`/`Collection` (direction 1 of the issue) does not
touch them; it stays a noise reducer. The four reverted attempts of 2026-10-08 were measured before 06fc1e65 made the
`set(index, value)` write visible, on a graph whose live chain was a different one.

**Substitution** (`instantiatedSlots`, `instantiatedArguments`, `constructorCopies`, `receiverNode`). A call on a
receiver of generic class C instantiates C's type variables with the receiver's type arguments, so a position typed by
one of them *inside another generic type* denotes the receiver's slot: `tmpSet = factory.spawnEmptySet()` ties
`tmpSet`'s argument 0 to `factory`'s; `tmpSet.union(set)` with `union(FastFixedSet<E> set)` ties the argument's to the
receiver's; `new Factory<>(lstStats)` with `Factory(Collection<E> set)` ties `lstStats`' to the new object's, i.e. the
target's. The pass already did this for a bare `E` (`receiverSlots`, `argumentNode`, `slotIndex`); the nested case fell
through to coupleContent, which identified the instantiation with the declaration's own type-variable slot.
- `coupleContent` no longer adds an edge from a concrete slot into a class type variable's slot (that slot is
  parametric: it is reached only by what the class itself puts there). The other direction stays: the class's own null
  reaches every instantiation. The old symmetric tie was what a0a80ec0 cut in both directions, without the substitution.
- Directions: an argument's slot flows into the receiver's (content copied in) under every policy, as `contentCopies`
  already did for `Collection<? extends E>`. The receiver's slot into a result's, the class's own null into an
  instantiation, and the receiver's into a `? super E` consumer are **Kotlin-only** (`Policy.callResults`): real
  flows, but a first measurement with them on for Java gave guava **+246 noise under every policy**, 241 through one
  chain: `AbstractMap.get(Object)`'s `@Nullable` key → `Multimaps.AsMap.get` → the multimap's K slot (flow-insensitive,
  `containsKey(key) ? multimap.get(key) : null`) → `multimap.keySet()` → the `ImmutableSet<E>` override hub →
  `RegularImmutableMultiset.<init>(…, ImmutableSet<E> elementSet)` → `RegularImmutableSet.EMPTY` → everything
  `ImmutableSet.of()` returns. The Java policies keep the ties the links give them, as before.
- The opposite directions are Kotlin's invariance (`Policy.assertContentWrites`, as for arrays): `tmpSet.add(x)` with a
  nullable `x` needs `FastFixedSetFactory<Statement?>`, and `new Factory<>(nodes)` needs `nodes: MutableList<Node?>`.
  Not into a `? extends` position. Not where the class's own null makes the formal position `E?`
  (`List<E> all() { …; l.add(null); return l; }`: a `List<Node?>` matches a `Factory<Node>` already); since a class
  type variable's slot is reached only by the class's own nulls, that is known from a first closure before the
  invariance edges go in (`invariantTies`, in `once`).
- Kotlin also takes a slot read passed as an argument (`new Pair(cv.get(i), i)`) as the parameter's value, as
  `callResult` does for an assignment; the Java policies leave it to the links.
- **Record accessors.** The first measurement left one new error, SwitchPatternHelper.kt:109: `initializer.instance`
  read as non-null while the component `Initializer.instance` and the constructor parameter were nullable. A
  synthesised accessor (`RecordSynthetics.createAccessor`) has a body, `return field;`, but no source, and its
  statement carries no variable data, so no link tied the component to the return. A parameterless getter without
  variable data now takes its field from the getter/setter association (`MethodInfo.getSetField`).

**Streams** (`streamElements`, `mapped`, `streamTerminal`, `slotSources`). The pass follows a pipeline to the nodes
whose values are its elements: `c.stream()` (the collection's slot, also a library view such as `map.values()`),
`Arrays.stream(a)`, `Stream.of(x, y)`, `Stream.concat`; through `filter`, `sorted`, `distinct`, `limit`, `skip`,
`peek`, `boxed`, …; through `map` / `mapToObj` to the lambda body's node (a field, an analysed call, a slot read; the
lambda parameter itself means the source's elements) or an analysed method reference. A terminal `toList()`,
`collect(Collectors.toList() / toSet() / toUnmodifiable… / toCollection(…))`, `findFirst/findAny/min/max`
(Optional) makes those nodes values of the result's slot 0; `toMap(k, v)` of slots 0 and 1. Applied where a result is
assigned or returned, where it is an argument, and in `contentCopies` (`addAll(stream…toList())`). No node for a
lambda that constructs (`mapToObj(i -> new Pair(…))`): the record's constructor call inside the lambda is a call site
of its own and carries the value into the record's field.

Guava: `NULL_MARKED` and `NULL_MARKED_FLOW_ONLY` unchanged, declaration for declaration; `OPEN_VISIBILITY` and `OPEN`
+7 agree / −7 undecided (6 return arguments, 1 parameter) and −1 agree / +1 undecided on a field argument.
Fernflower: **199 files, 0 type errors** (4dc2fec6: 197 / 3). Printer messages against the same code before the
change: ASSERT_INTO_NON_NULL 288 → 263, ASSERT_AT_DECLARATION 32 → 33, ASSERT_AT_DEREFERENCE 2,187 → 2,387 (the nullable
`Statement` slots DomHelper and the FastFixedSets now carry are read with `!!`).
Tests: `TestNullabilityPass.classTypeVariableInstantiation` (both policies), `streamPipelines` (Kotlin).

Measuring: a second Gradle build in the same worktree while a slow test's JVM runs recompiles classes that JVM loads
from `build/classes` and kills it (`ClassFormatError`; Gradle reports an `EOFException`), leaving the previous
`report.txt` in place. Unit tests first, then the slow tests, one at a time.

### 2026-10-09 — a library override's parameters (Kotlin)

The JDK calls an override back with what it likes. In fernflower's test fixture `DecompilerTestFixture.deleteRecursively`,
`SimpleFileVisitor.postVisitDirectory(Path dir, IOException exc)` receives a null `exc` whenever the directory's
iteration completes without an error. Translated as `exc: IOException`, Kotlin's entry check throws (170 failures in
the translated tests).

- **Hints (maddi).** `java.nio.file.FileVisitor` and `SimpleFileVisitor` say `postVisitDirectory`'s `exc` is
  `@Nullable` and the other parameters are `@NotNull`: `visitFile`/`preVisitDirectory` `attrs`, `visitFileFailed`
  `exc`. The existing `libraryNullableParameter` seed does the rest under every policy.
- **`Policy.libraryCallbacks` (KOTLIN only).** A parameter of a real override (not a lambda) of a library method is
  nullable unless some overridden library method declares it non-null. It is not nullable when the library
  parameter is a type variable, where the instantiation decides (`visitFile(Path file, …)` for `T = Path`), or a
  primitive. Seed cause: "overrides X, which does not declare it non-null". It is off for the Java policies: in
  guava's annotations an unannotated JDK override parameter is non-null, so the oracle would count it as noise.
  Guava is unchanged under all four policies.

### 2026-10-09 — issue #22 gap 4: an array field filled by the constructor's loop

`indexFills` recognised only `T[] a = new T[n];` followed by its loop. nacos's `NacosExecuteTaskExecuteEngine`
writes `this.executeWorkers = new TaskExecuteWorker[size];` in the constructor and fills it in the next statement's
loop, and every `executeWorkers[i].x` kept "array created with null elements". The creation may now also be an
assignment statement, to a field (the constructor's shape) or to a local declared earlier; the loop rule is the
same, narrow one. Gate `NOFIELDFILL` for A/B.

Guava: −1 noise under every policy (`Striped.CompactStriped.array`, exactly this shape), unsafe unchanged.
Fernflower: 199 files, 0 type errors, unchanged. `TestNullabilityPass.filledArrays` pins the field, the assigned
local and a conditional fill of a field that stays nullable.

### 2026-10-09 — issue #22 gap 1: a map key known present (`NonNullFacts.KeyPresence`)

`if (map.containsKey(k)) map.get(k)`, `map.put(k, v); map.get(k)` and `for (K k : map.keySet()) map.get(k)` read
the lookup as the library's `@Nullable V get(Object)`: the absent key's null, which these shapes exclude. The facts
walk now carries a synthetic variable `map[k]` ("k is present in map"), established by a true `containsKey(k)`, by
`put(k, v)` with `v` known non-null, and inside a `keySet()` loop; it goes when `map` or `k` is assigned and at a
call that may change the map (one on the map other than a read, or one handed the map). Both require `map` and `k`
to be trackable variables and the receiver a `java.util.Map`. Where a lookup's (`get`, `remove`) result would take
the library's nullable return, the pass skips that seed when the key is present at the call; what the map holds
for `k` still flows through its value slot, so a null stored in the map still arrives. Java only: Kotlin does not
smart-cast a lookup. `MapUtil.computeIfAbsent(map, k, …)` (a static helper) is not covered.

Guava: unchanged under all four policies (one cause chain spelled differently). Fernflower: 199 files, 0 type
errors, unchanged. `TestNullabilityPass.keyPresence` pins eleven shapes, including the ones that must forget the
fact (a call on the map, the map handed to a call, the key reassigned, a null put).

### 2026-10-09 — issue #22 gap 5, issue #9: a parameter the body assigns (`var p = p`)

`String value = …; value = decode(value.trim())`, `while (node != null) node = node.next`, `if (args == null) args
= new Object[0]`: a parameter is one node, so the caller's value and whatever the body assigns shared a verdict. A
null assigned in the body made the parameter nullable for every caller, and a nullable argument made the body's
later reads nullable where Kotlin would have smart-cast. The pass now gives a reassigned parameter two nodes: the
parameter, for the caller's value, and a shadow `Local(method, null, name)` the parameter flows into and the body's
assignments go to. Reads resolve to the shadow from the first assignment on (from the loop's start for an
assignment inside a loop; a statement's own expression before what its sub-blocks assign, so `if (args == null)`
tests the caller's value). Only plain assignments outside lambdas count; `+=` and a lambda's assignment leave the
parameter one node. `Report.reassigned(pi)` is the shadow's verdict; `Report.verdicts()` keeps the parameter's.

The Kotlin printer already printed `var p = p` for such a parameter; it now types the var nullable when the shadow's
verdict is and the parameter's is not (`var exp: Exprent? = exp`), since Kotlin would infer the parameter's non-null
type and reject the body's null. Every read in the body then takes the var's type (`KotlinNullability.parameterType`
consults the method's own shadow; a first version matched by name alone and typed callees' same-named parameters by
the caller's locals: 29 type errors in fernflower).

Gate `NOPARAMSHADOW` restores one node. Guava: unchanged under all four policies. Fernflower: 199 files, 0 type
errors, unchanged. `TestNullabilityPass.reassignedParameter` pins the incoming, loop, conditional and constant
shapes. The link-engine side of #9 (the reassigned parameter in `MethodLinkedVariables` summaries) stays open.
