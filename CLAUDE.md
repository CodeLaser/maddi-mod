# maddi-mod — notes for AI assistants

This is one of three repositories split from maddi; **read `README.md` for the tier rule first**. The general
notes live in the base repository and apply here unchanged: `../maddi/CLAUDE.md`, `../maddi/AGENTS.md`
(commands, engine facts, working style) and `../maddi/ARCHITECTURE.md`.

The two rules that follow from the project being public hold here too: the customer behind the private proving
corpus is never named (write `closed-core` / `com.example.*`; the commit hook in `.githooks` refuses the rest),
and open work items become GitHub issues rather than new checkbox `.md` files.

Before reasoning about immutability, modification, independence, linking, or the analyzer's convergence
machinery, read `../maddi/road-to-immutability/llm-summary.md`.

Deeper references in this repository, in reading order per topic:

- Link engine: `maddi-modification-link/linking-manual.md` (start at §5 LinkMethodCall + §6 worked examples;
  `TestLinkMethodCall` is the spec-by-example), `maddi-modification-link/README.md`,
  `maddi-modification-link/src/main/java/.../vf/virtual-fields.md`.
- The engine layer (collapse and reconstruction): `maddi-modification-link/sv-reconstruction-techniques.md`;
  the other `sv-*.md` files are closed records of the July 2026 integration.
- The analyzer: `maddi-modification-analyzer/README.md` (what runs, in what order, which component writes which
  property), `maddi-modification-analyzer/definitions.md` (the concepts mapped onto properties);
  `docs/README.md` §Module references lists the status of every module-level document.
- Nullability: `docs/design/nullability.md`.

Before quoting a `slowTest` run as evidence for an engine change, read `../maddi/AGENTS.md` §Commands: a green
corpus run can be cached, skipped, vacuous, or heap-starved, and each looks like success.

Test corpora: resolve the clone-bench corpus through `CloneBenchCorpus` (maddi-modification-common test
fixtures; `TESTARCHIVE_ROOT` / `-Dtestarchive.root`), never a hardcoded path. The corpus lives on the
`analyzed` branch of `testarchive`.
