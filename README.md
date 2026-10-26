# maddi-mod

The analyses of [maddi](https://github.com/CodeLaser/maddi): the **linking engine**, and the passes built on it,
which compute **modification and immutability** and **nullability**. maddi's README explains what they compute and
what they are for; this repository is where they are implemented and tested.

## What is here

| Module | What it does |
|---|---|
| `maddi-modification-prepwork` | The prep analysis: per-method variable data and escapes, ahead of linking. |
| `maddi-modification-link` | The linking engine. For every statement it computes which variables link to which, and how (`←`, `~`, `≤`, …), then summarises each method as links between its parameters, fields and return value. |
| `maddi-modification-analyzer` | The iterating analyzer. From the links it decides, to a certified fixpoint, which methods modify what and which types are `@Immutable`, `@Container`, `@Independent` or eventually immutable. After the fixpoint come the reachability pass, which propagates modification along call chains, and the nullability pass (`nullability/`). |
| `maddi-modification-common` | What the above share; the test fixtures. |
| `maddi-aapi-parser` | Compiles the annotated APIs (the hint sources in maddi's `maddi-aapi-archive`) into the analysis results that ship with maddi, for the JDK and for libraries. |
| `maddi-run-analysis` | `AnalysisEngineImpl`, the implementation of maddi's `AnalysisEngine` service, and the corpus tests. |
| `maddi-inspection-kotlin-analysis`, `maddi-run-kotlin-analysis` | Tests of the analyses on Kotlin input, among them the Kotlin/Java fixture pairs that check parity. |

Where to start reading:
- the linking engine: [`maddi-modification-link/README.md`](maddi-modification-link/README.md), then
  [`linking-manual.md`](maddi-modification-link/linking-manual.md) §5 and §6;
- the analyzer's phases: [`maddi-modification-analyzer/README.md`](maddi-modification-analyzer/README.md);
- nullability: [`docs/design/nullability.md`](docs/design/nullability.md);
- the vocabulary: maddi's
  [`road-to-immutability/llm-summary.md`](https://github.com/CodeLaser/maddi/blob/main/road-to-immutability/llm-summary.md).

[`docs/README.md`](docs/README.md) lists this repository's design notes and roadmaps.

## The corpus ratchets

The `slowTest` battery runs the analyses on real open-source projects, which maddi's `corpus/` tooling provisions.
Each ratchet pins its numbers in a file under `maddi-run-analysis/src/test/resources/` and fails when one gets worse:

- `j2k/`: Java → Kotlin with the analyses' facts: files compiling with `kotlinc`, files compiled to class files, and the
  corpus's own tests passing against the Kotlin classes and against its translated tests;
- `j2cs/`: Java → C# with the analyses' facts: files compiling with Roslyn, nullable warnings, unmapped JDK uses.

Without the analyses' facts, the same corpora are ratcheted in maddi's `maddi-run-openjdk`.

```bash
./gradlew test                                        # fast tests
./gradlew :maddi-run-analysis:slowTest --tests "*TestJavaToKotlinFernflowerNullability"
```

`J2K_COMPILE_ONLY=true` translates and compiles but runs no tests, which makes for a faster cycle. Before you quote a
`slowTest` run as evidence, read maddi's `AGENTS.md` §Commands: a green corpus run can be cached, skipped, vacuous or
starved of heap, and each one looks like success.

## rustc as a judge of the verdicts

maddi's Rust printer (`maddi-cst-print-rust`, CodeLaser/maddi#167) prints the verdicts computed here as Rust's
ownership modes, and prints the bodies without softening them, so rustc's borrow checker checks the verdicts against
the code:

| rustc says | reading |
|---|---|
| E0596, E0594: cannot borrow or assign behind `&` | a non-modifying or unmodified verdict the body contradicts: an unsound modification verdict |
| E0597, E0716, E0515: does not live long enough | a missed dependence |
| E0382: use of a moved value | usually the printer's: Java shares a kept object, Rust moves it |
| E0133: unsafe call | a missed `@StaticSideEffects` |

rustc catches unsound verdicts, not imprecise ones: a `&mut` where `&` would do compiles. Issues #58, #59, #60, #62,
#63 and #64 came from such rejections, each with a minimal repro, and #65 is a question this check raised.
To run it on a project: `maddi --translate rust --translate-output out`, then `cargo build` in `out`.
`scripts/rust-llvm/run.sh` adds a second opinion on precision, which rustc does not judge: LLVM's inferred parameter attributes (`captures`, `readonly`) and clippy's lints (`needless_pass_by_value`, `needless_pass_by_ref_mut`, `ptr_arg`) on the printed Rust, joined with the verdicts per parameter. With `--tests` it runs on the Java inputs of this repository's analyzer and link tests, one crate per input, and also counts rustc's rejections per error code.

## The annotated APIs

The hint sources are in maddi (`maddi-aapi-archive`); this repository compiles them. After a change there:

```bash
(cd ../maddi && ./gradlew :maddi-aapi-archive:compileJava)
./gradlew :maddi-aapi-parser:compileAnalysisHints     # rewrites the results in maddi; the JDK ones only on JDK 27
```

## Three repositories

| Repository | Holds | Depends on |
|---|---|---|
| [maddi](https://github.com/CodeLaser/maddi) | the base: CST, front ends, printers, the `AnalysisEngine` interface, the annotated APIs | nothing above it |
| **maddi-mod** | the analyses | the base only |
| [maddi-dist](https://github.com/CodeLaser/maddi-dist) | what users install | compiles against the base only; carries maddi-mod at run time |

Nothing here may depend on maddi-dist. Code outside this repository reaches the analyses through
`io.codelaser.maddi.analysis.api.AnalysisEngine` (in maddi), which `ServiceLoader` finds.

## Building

Check out the three repositories side by side, as described in maddi's
[README](https://github.com/CodeLaser/maddi#building-from-source). maddi-dist is needed here only by the corpus
tooling, for the CLI launchers and the build plugins.

Licence: LGPL-3.0-or-later (`COPYING`, `COPYING.LESSER`), as maddi.
