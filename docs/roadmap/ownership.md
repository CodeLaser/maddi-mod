# Ownership: `@Fresh`, `@Owned`, `@Borrowed` — implementation brief

**Plan, 2026-11-13.** Stages 1 to 5 are built behind the `OWNERSHIP` gate (`maddi-modification-analyzer`, package
`analyzer.ownership`); the printer's use of stage 4 is not. This brief is self-contained: it is meant to be handed to a session that has
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
