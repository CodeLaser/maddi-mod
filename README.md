# maddi-mod

The **modification analysis** of [maddi](https://github.com/CodeLaser/maddi): prep work, the link engine, the
iterating analyzer, the analysis-hints (AAPI) parser, and `maddi-run-analysis`, the implementation of maddi's
`AnalysisEngine` service. This repository also holds the analysis tests of the drivers, among them the
large-corpus `slowTest` battery. The open-source **corpus** tooling that provisions those corpora is maddi's
`corpus/` (here from the split until 2026-10-03); `task corpus:*` works from this root too.

## The tier rule

maddi was split into three repositories (see `maddi/docs/roadmap/split-maddi-into-three-repositories.md`):

| repository | tier | depends on |
|---|---|---|
| [maddi](https://github.com/CodeLaser/maddi) | base: CST, front ends, inspection, call graph, drivers, the `AnalysisEngine` interface | nothing above it |
| **maddi-mod** | mod: the modification analysis | base only |
| [maddi-dist](https://github.com/CodeLaser/maddi-dist) | dist: IDE and build-tool integrations, CLI distributions | compiles against base only; carries mod at run time |

Nothing here may depend on maddi-dist. Callers outside this repository reach the analysis through
`io.codelaser.maddi.analysis.api.AnalysisEngine` (in maddi), found with `ServiceLoader`.

## Building

maddi is built from source, as a sibling checkout:

    ~/git/…/maddi        (base)
    ~/git/…/maddi-mod    (this repository)
    ~/git/…/maddi-dist   (needed only by the corpus tooling, for the CLI launchers and the build plugins)

    ./gradlew test                    # fast tests
    ./gradlew slowTest                # the corpus battery; see corpus/ and maddi's AGENTS.md §Commands

Licence: LGPL-3.0-or-later (`COPYING`, `COPYING.LESSER`), as maddi.
