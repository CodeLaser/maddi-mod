# KotlinConf: maddi's declarations and an LLM's bodies (fernflower, 2026-11-10)

This is a record of an experiment, not a tool you can run as-is. The scripts were run from a scratch directory
(`ws/maddi-mcp/llm-bodies-exp`) and contain absolute paths to it, to the fernflower checkout and to maddi's
build output. The LLM was Claude Code subagents, not the API. The generated Kotlin is not kept here.

Four arms on fernflower's 199 Java files, all judged the same way: kotlinc 2.4.0, then fernflower's own tests with
the Kotlin classes in front of the Java ones.

| | maddi alone | maddi's declarations, LLM bodies | LLM alone | LLM alone + test feedback |
|---|---|---|---|---|
| tests passing | 264 / 265 | 264 / 265 | 157 / 265 | 264 / 265 |
| nullable parameters | 755 | 755 | 341 | 345 |
| subagent tokens | n/a | ~3.5M | ~2.87M | ~2.93M |

The failing test in every column is the Groovy test, which also fails on the Java.

- `pilot/`: three classes with the bodies written by hand from the Java (`strip_bodies.py` makes the skeletons,
  `check.sh` compiles, `runtests.sh` runs the tests, `kotlinc` is the compiler wrapper).
- `step2/`: maddi's declarations (`Bodies.STUB` and `maddi-bodies.json`, from `TestKotlinConfStubs`) with every
  body written by the LLM. `bodies.py` prepares the prompts, assembles, and falls back to maddi's body.
  `RESULTS.md` is the write-up.
- `step3/`: the LLM alone, translating whole files from the Java (`alone.py`, prompt in `PROMPT.md`), then fix
  rounds fed by the test log (`testfeedback.py`). `RESULTS.md` covers both arms.
