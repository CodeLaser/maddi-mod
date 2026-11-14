# Ownership: `@Fresh`, `@Owned`, `@Borrowed` — implementation brief

**Plan, 2026-11-13.** Stages 1 to 5 are built behind the `OWNERSHIP` gate (`maddi-modification-analyzer`, package
`analyzer.ownership`), and the Rust printer reads the verdicts (stage 4). This brief is self-contained: it is meant to be handed to a session that has
not seen the discussion it came from. Paths are relative to the workspace root (the directory holding `maddi/` and
`maddi-mod/`).

Read first: `maddi/road-to-immutability/llm-summary.md` (vocabulary), `maddi-mod/maddi-modification-analyzer/README.md`
(phases, properties by writer), `maddi/maddi-cst-print-rust/rust-printing.md` (how verdicts become Rust ownership
modes), and the `CLAUDE.md` of each repository and of `~/git`.

## 1. Goal

Three new verdicts, computed after the analysis has converged, that say who holds an object:

| Annotation | On | Property | Meaning |
|---|---|---|---|
| `@Fresh` | method | `FRESH_METHOD` | every object the method returns was created during the call and is held nowhere else when it returns |
| `@Borrowed` | parameter | `BORROWED_PARAMETER` | the object passed in is not referenced by anything that outlives the call |
| `@Owned` | field | `OWNED_FIELD` | the object the field holds can only be reached through the object holding the field |

The names are decided (Bart, 2026-11-13). Do not use `@Owner` (in ownership types that is the parent) or `@Owning`
(Checker Framework: an obligation to close).

Why: an owned object dies with its owner, so its lifetime is known statically. The first consumer is the Java→Rust
translation (an owned field is a plain field or `Box`, not a shared handle); the second is the engine itself, which
gets an externally checkable form of independence.

**How ownership differs from independence.** `INDEPENDENT_*` asks whether *modifications* can travel, so it lets
immutable content be shared. Ownership asks whether the *object* is shared, so immutable sharing counts. And
ownership is a claim about the **absence** of links: a degraded or missing summary must always read as "shared",
never as "owned" (see `maddi-mod/docs/roadmap/modification-link-applications.md` on presence against absence).

## 2. Shape of the implementation

A one-shot pass after convergence, modelled on `NullabilityPass`:

- New package `maddi-mod/maddi-modification-analyzer/src/main/java/.../analyzer/ownership/`, driver `OwnershipPass`.
- Called from `IteratingAnalyzerImpl` where `configuration.nullability()` triggers the nullability pass
  (around line 531), under a new `Configuration.ownership()` knob (default false), set by `AnalysisEngineImpl`
  from an environment gate `OWNERSHIP=1`, in the way the other gates are read.
- It **only adds** properties. It never writes or overwrites an existing one, and nothing in the fixpoint reads the
  new ones. Acceptance for every stage: `FPDUMP` of the existing verdicts is byte-identical with the gate on and off.
- Properties are declared in `maddi/maddi-cst-analysis/.../PropertyImpl.java` and registered in
  `PropertyProviderImpl` (follow `NULLABILITY_METHOD`). Polarity as elsewhere: the default is the pessimistic
  value, and absence means undecided.
- The pass has its own small fixpoint over the call graph for `FRESH_METHOD` (a method returning the result of a
  fresh method is fresh). Start **pessimistic** (not fresh) and iterate upward; recursion then stays not fresh.
  Measure how much that loses before considering an optimistic start.

## 3. Stages

Do them in order; each is one or more commits with its tests. Stop after stage 4 and report the census.

### Stage 1 — `FRESH_METHOD`

Half of this exists: `LinkFacts` (`maddi/maddi-cst-analysis/.../LinkFacts.java`), computed by
`maddi-mod/maddi-modification-link/.../impl/LinkFactsImpl.java` from `METHOD_LINKS`, gives the Rust printer
`Result.NEW` or `EXISTING` per method. It is not a property: not persisted, no library contracts, no fold over
implementations.

Rule. A non-void, non-constructor method with a body is fresh when **every** return path yields one of:

- an object created in the method (the creation marker `return ← oc:N`, an `ObjectCreationVariable`; see
  `LinkComputerImpl` near line 695 and `Graph.isFreshObjectReturn`);
- the result of a call to a method that is `FRESH_METHOD`;
- `null`;

and the return variable itself has no link to the receiver, a parameter, a field or a static (the test
`LinkFactsImpl` already makes: a link *from* the `ReturnVariable` means an existing object; links only from its
fields mean a new object holding something).

Requirements beyond `LinkFactsImpl`:

- **Positive evidence only.** `LinkFactsImpl` answers `NEW` when there are links and none from the return variable.
  Check what it answers for `return libraryCallWithoutHints();` and for a degraded callee: if that is `NEW` by
  absence, the property must not inherit it. Verify this first, with a test, and report what you find.
- A method marked `DEGRADED_ANALYSIS_METHOD`, or calling an undecided or degraded method on a return path, is not fresh.
- Bodiless methods: fold over `IMPLEMENTATIONS` (fresh only when all are), in the style of
  `AbstractMethodAnalyzerImpl`; a non-final method with overrides is fresh only when its overrides are.
- Contracts: `@Fresh` (new, in `maddi/maddi-annotation/`) on a bodiless or library method settles the property,
  through `ContractResolution`. Add it to the JDK hints for an obvious first set (collection and `StringBuilder`
  constructors need nothing; `List.copyOf` is **not** fresh, it may return its argument; `ArrayList::clone`,
  `Stream.toList`, `String.valueOf`, `Map.entry` are). Keep the first set small.
- Persist it (the codec that writes verdicts; follow `NULLABILITY_METHOD`), and include it in `FPDUMP`.
- `LinkFactsImpl.result` may then read the property for methods without links (library methods), so that the
  printer gets facts for library calls. That is the only consumer change in this stage.

Tests: a spec-by-example class `TestFreshMethod` (in `.../analyzer/ownership/` under `src/test`), in the style of
`.../analyzer/link/TestLinkFacts.java`. Cases at least: plain factory; builder `build()`; delegation to a fresh
method; local modified before return; conditional return of a parameter (not fresh); object also stored in a
field or a collection of `this` before return (not fresh); return of a static constant (not fresh); interface
with two implementations, one not fresh; recursion; library call with and without a contract.

### Stage 2 — `BORROWED_PARAMETER`

Rule. A parameter is borrowed when, after the call, nothing reachable holds the object: no link from a field of
`this`, a static, the return value, another parameter's content, or a lambda or inner object that escapes, to the
parameter **itself** (not to its hidden content: `list.addAll(p)` copies elements and borrows `p`).

This is `INDEPENDENT_PARAMETER` without the exemption for immutable content. The exemption is in
`FieldAnalyzerImpl.computeIndependent` (`typeIndependentFromImmutableOrNull`); `TypeModIndyAnalyzerImpl` writes the
parameter property. Compute the new property from the parameter's links in `METHOD_LINKS` (`ofParameters()`) and
`LinkFacts.keptInResult`, not by changing those analyzers.

- Degraded or undecided method: not borrowed.
- Bodiless: fold over implementations; `@Borrowed` contract for library methods.
- A captured parameter counts as kept (engine fact since CodeLaser/maddi-mod#39).

Tests `TestBorrowedParameter`: read-only use; stored in a field; added to a field's collection; returned; stored
and immutable (`String`: **not** borrowed, although independent); captured by a returned lambda; passed on to a
callee that keeps it; passed on to a callee that borrows it.

### Stage 3 — `OWNED_FIELD`

Values: `NOT_OWNED` (default), `SHARED_IMMUTABLE`, `OWNED`. A new `Value` type with its codec.

A field is `OWNED` when all hold:

1. **Arrives fresh.** Every assignment to it, in constructors, initializers and methods, is an object creation, a
   call to a `FRESH_METHOD`, or `null`. A parameter stored in the field fails (until stage 5).
2. **Never leaves.** Its links (`LinksImpl.LINKS` on the field, written by `FieldAnalyzerImpl`) reach no return
   value, no parameter, no static, no field of another object, no escaping lambda. Passing it to a
   `BORROWED_PARAMETER` is allowed.
3. **Not accessible.** The field is private, or otherwise not readable from outside the primary type.
4. **No degraded referrer.** No method referring to the field is degraded (`FieldAnalyzerImpl` already computes
   `degradedReferrer`).

A field that fails only rule 1 or 2, and only through content whose type is immutable, is `SHARED_IMMUTABLE` (the
`Rc` case: several holders, nobody modifies, no cycles). Everything else is `NOT_OWNED`.

Scope of this stage: the field's object itself. Whether a collection's **elements** are owned is out of scope (§5).

Tests `TestOwnedField`: private final list created in the initializer, never exposed; the same with a getter
(not owned); with an unmodifiable-view getter (not owned: the view shares the object); constructor parameter stored
(not owned); `String` constructor parameter stored (`SHARED_IMMUTABLE`); field passed to a borrowing callee (owned);
field passed to a keeping callee (not owned); lazily created field (`if (f == null) f = new …`, owned); field
reassigned from a fresh method (owned); non-private field (not owned); back-pointer to the parent (not owned).

### Stage 4 — census and first consumer

- A report at the end of the pass (log, and a TSV when `OWNERSHIP=<file>`): counts of fresh methods, borrowed
  parameters, and fields by value; for `NOT_OWNED`, which rule failed first. That last column is the worklist.
- Run it on fernflower, gson and langchain4j (corpus handling: `maddi/corpus/README.md`; memory notes
  `fernflower-rust-loop`, `langchain4j-collections-ab`). **Report the numbers to Bart and stop.**
- Only after that, and only on his go-ahead: the Rust printer reads `OWNED_FIELD` so that an owned field of a type
  that is otherwise a shared handle stays a plain field. Measure against the current fernflower translation
  (930 stubs, 12 translated tests passing, as of 2026-11-12).

  **Built 2026-11-13** (maddi `1da2436cd`, `fc53bc9bd`, `d788b34d9`, `47cbd4f14`; gate `NORUSTOWNED` ignores the
  verdicts; `TestJavaToRustOwnedField`). An `OWNED_FIELD` is no holder for `RustShared` (a class held only in owned
  fields is not a `Shared` handle) and never a `RustBorrows` view: a plain field, its object moved in. A
  `FRESH_METHOD` returns its object by value (also one built in a local, which `returnsNew` does not follow), and a
  fluent chain making an owned result runs, then yields the object. fernflower with sources and tests, `OWNERSHIP=1
  OWNERSHIP_WORLD=closed`, against the same run with `NORUSTOWNED=1`: rustc errors 1937 → 1878, stubbed functions
  (counted by `stub.py`'s rustc.json, not comparable with the 930 above) 999 → 994, translated tests 12 → 12. Owned
  fields fixed 3 errors (`DecompilerContext.properties` no longer a borrow among them); fresh results most of the rest
  (fernflower's `toJava` family over `TextBuffer`).

### Stage 5 — transfer

A parameter stored in a field counts as arriving fresh when **every** call site passes an object that is fresh and
not used after the call. This is what CodeLaser/maddi#202 pattern 3 lacked (moving in an independent stored
parameter was tried and reverted: Java's callers went on using the object).

Built (`Transfers`, test `TestTransfer`) with a syntactic check of the call sites rather than the per-call argument
links and prepwork's last-use data the plan named: narrower, but each step is visible in the source.

- **All call sites known.** The callee is private or package-private, or the world is closed (`OWNERSHIP_WORLD=closed`,
  `Configuration.ownershipClosedWorld`: an application rather than a library). It overrides nothing, nothing
  overrides it, no method reference names it, the parameter is not variadic, and no field initializer calls it.
- **Every argument handed over**, through casts, parentheses and `@Identity` methods (`Objects.requireNonNull`):
  created on the spot (freshness's evidence); a parameter of the caller that is itself transferred and mentioned
  once (`this(l, 0)`; greatest fixpoint); or a local declared in the block of the call's statement, assigned only
  created values, before that statement only the receiver of calls that neither may leak `this` nor dispatch
  elsewhere, mentioned once in the statement outside any loop or lambda, and not after it.
- **Kept only in this field**: the callee's links from the parameter go to `this.f` only, none reach it, and stage 2's
  hidden rules hold (a lambda capturing it, a callee that may keep it).

Stage 3's rules 3 and 4 then accept the parameter, and a field that still fails names the transfer's reason.

**Moves out of a finalizer** (built, `TestTransfer` B1-B4, D). A `@Finalizer` method (contract, `MethodInfo.isFinalizer()`) may hand
over its own field: `@Finalizer T build() { return new T(list); }` is Rust's `fn build(self) -> T`, a move instead
of a copy. Accepted as an argument when the finalizer belongs to the field's type and mentions the field once,
outside any loop or lambda, and the field is owned. On the builder's side, a finalizer that moves the field out (hands
it over, or returns `this.f`) does not count against the field's ownership: after the call the owner is dead. The
two depend on each other: greatest fixpoint over stage 3's verdicts. This trusts the contract's "no use after"; stage
6 is what checks it.

### Stage 6 — computing and checking `@Finalizer`

**Built 2026-11-13** (`Finalizers`, test `TestFinalizer`), as planned below, with these differences:

- Rule 3 has Rust's exception: a contracted finalizer may consume a parameter when no caller uses its argument after
  the call (every call site known; each argument a temporary, or a local in stage 5's ordering). Without it, every
  method consuming a `Stream` parameter would be flagged. Only the use after counts here, not stage 5's freshness.
- The check reads only the use after the call: a temporary passes, a local rebound to the call's result
  (`s = s.filter(p)`) passes, `this` passes inside a finalizer. Violations are warnings of category
  `finalizer-violation`, added to the guard's messages (`IteratingAnalyzer.messages()`), and `finalizerViolation`
  rows in the census.
- Computing rejects `this` as a receiver, and borrowed-parameter uses before the call are not yet accepted (stage 5's
  receiver-only rule).
- A fluent call on a created object is created too (`new B().add(x)`, unless the fluent method may leak `this` or
  dispatch elsewhere); this also widens stage 1.
- A computed `FINALIZER_METHOD` is written like a contract, and reads as one afterwards: a later run over the same
  results (incremental, preloaded) does not recompute it, only checks it. Its callers are then checked as a
  contract's, so a new caller that uses the object afterwards is reported, not silently accepted.

Census 2026-11-13 (`finalizer` and `finalizerViolation` rows): computed finalizers gson 0, langchain4j 0 (libraries:
their candidates are public), fernflower closed world 1 (`SwitchPatternHelper.JavacReferenceFinder.findCandidate`);
contract violations 0 in all three (their stream pipelines are chains, never a reused stream). Stage 1 rose slightly
through fluent chains (langchain4j fresh 501 → 509).

The plan as written before the build:

`@Finalizer` (maddi-annotation, `rare`) says that after the call nothing may use the receiver. Today it is a contract
only, read from source and from the annotated APIs, never computed. Its modification semantics are load-bearing (a
finalizer call never modifies its receiver: there is no *after* to observe it; this is what keeps stream pipelines
analysable); its three life-cycle rules are documented but not enforced (`road-to-immutability`, chapter "Other
annotations"). Stages 2, 3 and 5 supply what both computing and checking need: `@Finalizer` is **transfer of the
receiver**, and its rules are ownership rules.

| `@Finalizer` rule | In ownership terms |
|---|---|
| no method may be called after the finalizer | the receiver is not used after the call: stage 5's check, with the receiver as argument |
| 1. a type with finalizers has effectively final fields | the owner cannot be swapped out (`FINAL_FIELD`, computed) |
| 2. a finalizer is called on a field only inside a finalizer | an owner consumes what it owns only while it is itself consumed (Rust: moving out of a field needs `self` by value) |
| 3. never on a parameter or anything linked to it | a borrowed object cannot be consumed |

**Receiver transfer.** For a method `m`, every call site's receiver must be handed over in stage 5's sense, read
for the receiver instead of an argument:

- a temporary: a construction, a call to a `FRESH_METHOD`, or a chain of `@Fluent` calls (`FLUENT_METHOD`,
  computed) on one: `new B().add(x).add(y).build()`;
- a local with stage 5's ordering: declared in the block of the call's statement, not mentioned after it, the call
  outside any loop or lambda in it. Before the statement, the receiver of calls, and (wider than stage 5's locals)
  an argument to parameters that are `BORROWED_PARAMETER`: a borrow ends with its call;
- `this.f` inside a finalizer of `f`'s owner, with `f` owned (rule 2: a dying owner consumes what it owns);
- never a parameter of the caller, nor anything that is not a plain local, a temporary or such a field (rule 3).

The object must also never have been shared before: its constructor does not leak `this` (`ThisEscape`), and no
`@Fluent` link in the chain hides a second holder (a fluent method returns `this`, so the chain holds one object).

**Computing `FINALIZER_METHOD`.** Call-site evidence alone is vacuous: any method called once, on a temporary,
passes. So a method is computed a finalizer only when it also *needs* to be one: its body moves an owned field out
(returns `this.f`, or hands it over as above). Need and permission together:

- need (body): the method moves at least one `OWNED_FIELD` of its type out;
- permission (sites): receiver transfer at every call site, with stage 5's rule 1 (private or package-private, or
  `OWNERSHIP_WORLD=closed`; no method reference names it; nothing overrides it, or every override is judged too).

This is a greatest fixpoint with stage 3: the moved field is owned only if the method that moves it is a finalizer.
The computed property is the existing `FINALIZER_METHOD`, so the Rust printer's `self` by value (CodeLaser/maddi#167)
and the moves above apply unchanged. The modification exemption follows for free and stays sound: nobody observes
the receiver after the call.

**Checking contracted `@Finalizer`s.** For a contract (source annotation or annotated API), the same receiver-transfer
test, run on every call site in the analysed sources, finds violations: `Stream s = l.stream(); s.map(f); s.count()`
uses `s` after `map`, a finalizer in the annotated APIs; a finalizer called on a parameter breaks rule 3; on a field
outside a finalizer, rule 2. Report them as findings, as other contract violations. A violation of a contract the
modification exemption relied on makes that exemption unsound at that call site; say so in the finding.

**Census.** Count computed finalizers and their need (which fields they move); count violations of contracted ones,
split by rule. Expect few computed finalizers (builders' `build()`, `into...` accessors) and some stream reuse.

**Out of scope here.** Inferring a finalizer from intent (a `close()` that releases a resource: that is
`@Owning`/must-call territory, Checker Framework's Resource Leak Checker); finalizers on library methods without a
contract; ordering beyond one block (prepwork's variable data would be the route).

### Element ownership — `OWNED_ELEMENTS_FIELD`

**Built 2026-11-13** (`OwnedElements`, tests `TestOwnedElements`, printer `TestJavaToRustOwnedField`). The
elements of an owned collection, map or other generic object (its hidden content: `this.f.§$s`, a map's
`§$$s[-1]`/`§$$s[-2]`) are owned too when every element link goes to a creation, `null`, an immutable value, or a
parameter every caller hands over and its method keeps only there (E2), and no element leaves where no link shows
(E3: an element only the receiver of a call that keeps nothing, an argument of a call that does not keep it or of a
call on the collection itself, an operand; no lambda, view or function argument touching it). The printer then
counts no element type as held through the field: `Vec<T>` of plain values.

Census: fernflower (closed) 3 of 199 collection fields (`LabelHelper.LabelSets.breaks`/`continues`,
`VarProcessor.hasNotInsideLVT`), gson 0 of 62, langchain4j 0 of 170; the first blocker is nearly always E1 (the
collection itself is not owned: 149, 50, 164). fernflower translation 1878 → 1877 rustc errors.

Not yet: one verdict per type argument (a `Map<Node, Data>` with shared keys and owned values), arrays, nested
hidden content (`∈∈`), the same question for results and parameters, and the printer looking inside source generic
classes (`RustShared.heldIn` descends only into library collections and source classes extending one).

### Lending, pass-through calls, effectively private fields

**Built 2026-11-13** (`Lending`, `OwnershipPass.passThrough`, tests `TestLending`, `TestEffectivelyPrivate`).

- **Lending.** A method's result is lent when every call site is known (stage 5's rule 1) and every caller only uses
  the object: the receiver of a call that keeps nothing, an iterable, an operand, an argument the callee does not
  keep (stage 2's hidden verdict; in a library an independent parameter, or one independent with hidden content
  whose type is not a type parameter: `addAll(Collection)` yes, `add(E)` no). Not returned, stored or captured. A
  link from an owned field to a lent result, or an element returned by a lent method, no longer breaks ownership:
  Rust's `&self.f`.
- **Pass-through calls.** A call that returns its argument and keeps it nowhere is that argument in the creation
  evidence (`ensureNotNull(x, "x")`); stage 5 no longer trusts `@Identity` alone (`register(x)` may return `x` and
  keep it).
- **Effectively private.** In a closed world, a non-private field that no body outside its primary type mentions
  passes rule 1.
- **Rule 4** accepts a link to a deeply immutable value (`this.f ~ 0:s` after `x.add(s)` through an alias). Not by
  link nature: `~` also stands for "reachable through a result's fields" (`list ~ build`, `TestTransfer` B3).

Census: fernflower closed world owned fields 83 → 115 (elements 3 → 5 of 199), gson 17 → 24, langchain4j 32
(library: public getters and constructors). The fernflower translation did not move (1877 rustc errors, 994 stubs,
12 tests): the newly owned fields are mostly collections, already plain `Vec`/`HashSet` in Rust, whose elements stay
`Shared` through other holders; only `VarNamesCollector` stopped being `Shared`. For the translation, element
ownership is what counts, and its first blocker is now the collection's own E2 and E3 more than E1.

### Checked at run time (2026-11-13)

A run-time oracle (§5) now exists for `@Owned`: an instrumented test run marks the object a field claimed owned
holds, records every other heap location that stores it, and reports the holder reading the field while another
location still holds it. On fernflower's suite (closed world) it contradicted two claims; both were holes in the
pass, now fixed and in `TestLending.test2`:

- **Lending and element counting read the hidden verdict.** `BorrowedParameters.isHidden` leaves out the
  parameter's own link keeps; it is for callers whose links already apply the callee's. Counting uses reads no
  links, so it needs the full verdict: `new MethodWrapper(.., getCounterContainer())` had counted as a borrow.
- **A switch expression's arm is not a discarded result.** `case X -> s.getHeadexprentList();` is an expression
  statement whose value is yielded (`OwnershipPass.yieldedArms`), in lending, element counting and transfer.
- **Rule 4 through another instance.** The field's links are those of `this.f`. A read through another instance
  (`current.counter`, `getCurrentContext().counterContainer`) shows in the links of the field or method it is
  reached from, which rule 4 now reads too; not the field of a created or freshly returned object (`return build.l`:
  rule 3 judges arrival), not the field's own content (`child.parent.children`), not a fluent method's result.
- **`FieldReference.scopeVariable()` is null for any non-variable scope**, a call's result included; `isThisField`
  read null as `this`. The scope expression decides.

- **A transferred local's initializer was not read.** `Transfers.usesBefore` skipped statements without a
  `VariableExpression` of the local, and its declaration has none: `T c = get(..); new Adapter(c)` handed over the
  result of a method that is not fresh. gson's `Adapter.constructor` fields held non-capturing lambdas, shared
  between adapters.

**A lambda is not a created object** (decided 2026-11-14): JLS 15.27.4 and 15.13.3 let every evaluation of a lambda
or method reference return the same instance, so neither is fresh, and the run-time check counts a shared stateless
object as a contradiction.

After the fixes: fernflower 109 owned (115 before; also lost `BytecodeMappingTracer.lineNumberTable`, an attribute
its class keeps, and `RequiresEntry.moduleVersion`, a String, now shared immutable), gson 21; no contradiction on
either suite.

### Annotations

`@Fresh` (method), `@Borrowed` (parameter) and `@Owned` (field; `elements = true` for `OWNED_ELEMENTS_FIELD`) are in
`maddi-annotation`; `AnnotationToProperty` reads all three, `DecoratorImpl` prints them whenever the pass wrote them
(`TestOwnershipDecorated`). `@Fresh` and `@Borrowed` are contracts on bodiless and library methods; nothing reads an
`@Owned` contract yet (the guard on hand-written annotations is out of scope, §5). `SHARED_IMMUTABLE_FIELD` has no
annotation: `@Immutable` on the type says it.

## 4. Working rules

- Runs longer than about 5 minutes or 8 GB go through `lab run` from the workspace root, after committing
  (`~/git/CLAUDE.md`). A single test class stays local with `box run --mb 4g --wait -- …`.
- Commit on `ws/<name>` in each repository; Bart merges and pushes. maddi and maddi-mod are public: no customer
  names, and open work items are GitHub issues, not checkbox files.
- Before quoting a corpus run as evidence, read `maddi/AGENTS.md` §Commands (a green run can be cached or vacuous).
- When a rule above turns out to be wrong or unimplementable as written, stop and say so; do not weaken it silently.
  Soundness (never `OWNED`, `FRESH` or `BORROWED` when it is not) comes before coverage.

## 5. Out of scope

- Ownership of a collection's elements (needs the `∈`/`∋` links).
- "Release": an owned object that leaves because its owner gives up the reference in the same method (`pop`,
  `remove`). Latte allows it; add it after the first census if the worklist shows it matters.
- Back-pointers as borrows; uniqueness as a basis for eventual immutability.
- Feeding freshness into the modification fixpoint.
- A guard that checks hand-written `@Owned` / `@Borrowed` in source (contracts are read for bodiless and library
  methods only).
- A run-time oracle that refutes a verdict by observing sharing during a test run. Optional after stage 3; it does
  not belong to this repository.

## 6. Decided (Bart, 2026-11-13)

- The engine part is public: properties in maddi, the pass in maddi-mod, the annotations in `maddi-annotation`.
- `@Fresh`, `@Owned` and `@Borrowed` are new annotations, not attributes on existing ones.

## 7. Prior work

- AliasJava: Aldrich, Kostadinov, Chambers, "Alias Annotations for Program Understanding", OOPSLA 2002
  (`unique`, `owned`, `lent`, `shared`; includes an inference algorithm).
- Latte: Zimmerman, Gamboa, Fonseca, Aldrich, arXiv 2309.05637 (2023). A checker design, not implemented. Its
  `owned` means borrowed. Its consumption rule is stage 5; its isolation rule is the "release" case of §5.
- Jule: `maddi-mod/docs/related-work/jule-memory-model.md`.
