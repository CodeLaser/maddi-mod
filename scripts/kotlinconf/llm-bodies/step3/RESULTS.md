# KotlinConf, arm "an LLM alone": fernflower, 2026-11-10

## Setup

- **Input:** fernflower's 199 Java files, 1.7 MB. No maddi output: no declarations, no stubs, no manifest.
- **The LLM:** 16 Claude Code subagents (Claude Opus 5.5), each with about 105 KB of Java (`alone.py groups`).
  - The prompt (`w-alone/PROMPT.md`) gives binary-API conventions every translator follows, so that the files agree
    and the Java tests can link: companion objects with `@JvmStatic`/`@JvmField`, getters stay functions, no
    `internal`/`protected`, `open` where Java is not final, records as `@JvmRecord data class`.
  - Nullability and read-only versus mutable collections: each translator decides for its files.
  - The translators could read any fernflower Java file, and in fix rounds the other translators' Kotlin.
- **Fixing:** kotlinc compiled all 199 files together. Each group's errors went back to the agent that wrote them
  (`alone.py compile`). There was no fallback.
- **Judge:** the same as the other arms: kotlinc 2.4.0, then fernflower's own tests with the Kotlin classes.
- **Not done:** no fix round driven by the tests. Neither arm had one.

## Correctness, three arms

| | maddi alone | maddi's declarations, LLM bodies | LLM alone |
|---|---|---|---|
| files compiling on the first compile | 199 / 199 | 199 / 199 (bodies: 99.6%) | 155 / 199 (166 errors) |
| after one fix round | n/a | 199 / 199 | 199 / 199 |
| fernflower tests passing | 264 / 265 | 264 / 265 | **157 / 265** |

The test that fails in every column is `GroovyDeclarationDecompilerTest#testGroovyClass`, which fails on the Java too.

The first compile's errors were nearly all mismatches between files:
- 72 calls on a nullable receiver;
- 65 argument type mismatches, mostly `T?` against `T`;
- a few override signatures and mutable-versus-read-only collections.

The fix round settled them almost entirely with `!!` at the use site.

## Why 107 tests fail

The compile passes, but the code throws at run time. fernflower catches the exception per method and prints
"couldn't be decompiled", so the output comparison fails. The cause is 549 bare NullPointerExceptions in the log,
each from a `!!`:

- 395 come from `DomHelper.graphToStatement`: `stats.getWithKey(succ.id)!!`. Java's lookup returns null for the
  exit block's id, and the code replaces that with the dummy exit a few lines later.
- 143 come from the `InvocationExprent` constructor: `cn.className!!`. An `invokedynamic` constant has no class
  name, so Java stores null. That `!!` was added in the fix round, to satisfy a non-null `lateinit` declaration.
- The rest come from `NestedClassProcessor.setLambdaVars`, `VarProcessor.setVarFinal`,
  `ImportCollector.getNestedName`, and a Kotlin parameter check in `MemberConverterHelper.toBeRenamed`.

**What-if:** I made the first two sites nullable again in a copy (`w-whatif`, not part of the result). 250 of 265
tests passed. The 14 left fail on output differences that I have not traced.

So the LLM's declarations are where the arm loses. It makes things non-null that are null at run time, and the
compiler then asks for a `!!` that throws. maddi's whole-program nullability is what the other two arms take for
granted.

## Idioms (scripts/kotlin-idioms)

| | maddi alone | LLM bodies | LLM alone |
|---|---|---|---|
| lines | 36,392 | 39,525 | 37,725 |
| `!!` | 2,957 | 2,429 | 1,837 |
| `?.` | 34 | 21 | 41 |
| `?:` | 16 | 56 | 83 |
| nullable parameters | 755 | 755 | 341 |
| nullable properties | 216 | 216 | 164 |
| nullable returns | 291 | 291 | 218 |
| nullable type arguments | 314 | 314 | 91 |
| read-only collection types | 144 | 149 | 332 |
| mutable collection types | 668 | 663 | 461 |
| `val` locals | 3,640 | 3,888 | 3,932 |
| `var` locals | 989 | 798 | 784 |
| `lateinit` | 15 | 14 | 11 |

The LLM-alone arm looks the most idiomatic: the fewest `!!` and the most read-only collections. But it has fewer than
half the nullable parameters, and that is exactly what makes it fail at run time.

## Behaviour risks the agents reported

They are the same kinds as in the other arm:
- `!!` where Java stored or passed on a null;
- `lateinit` and non-null getters that throw before initialisation, where Java returned null;
- `==` where Java's `equals` would throw on null;
- private members of nested classes made public;
- `IFernflowerPreferences.DEFAULTS` is no longer a static field (`@JvmField` is not allowed beside `const val` in
  an interface companion);
- `IdentityRenamerFactory.rename` returns an empty map instead of null;
- the enum case-label `FieldExprent`s get placeholder names.

## Cost

About 2.87M subagent tokens:
- the translation took 2.82M, between 158k and 207k per group;
- the fix round took about 55k.

That is less than the LLM-bodies arm's 3.5M: there is no separate prompt per file, and the Java is read once.

# Arm "an LLM alone, with test feedback" (2026-11-10)

This arm starts from the LLM-alone result above. That result is kept in `alone-final-src` and `alone-final-tests`;
this arm's result is in `testfix-final-src`.

## Setup

- **The loop:** run fernflower's tests, then `testfeedback.py`, then send each group its file.
  - `testfeedback.py` takes every exception in the test log and groups them by the fernflower frame on top of the
    stack.
  - Each exception, with its stack and the "couldn't be decompiled" warning before it, goes to the translator that
    owns that file, in `errors-tests/<group>.txt`.
- **A test failure without an exception** went by hand to the owner of the method in the trace.
- **The translators:** the same agents, with the instructions in `PROMPT.md`, "Test-fix rounds".
  - They make the Kotlin behave like the Java, fixing at the cause: often a declaration of their own made nullable.
  - They still don't run the compiler or the tests themselves.
  - A change another translator's file needs was routed by me (once: `IMemberIdentifierRenamer`).

## Rounds

| round | groups | tests passing | what was fixed |
|---|---|---|---|
| start | | 157 / 265 | |
| 1 | a04, a09, a15, a16 | 251 / 265 | `DomHelper.graphToStatement`; `InvocationExprent` (invokedynamic class name); `ImportCollector.getNestedName` now returns `String?` |
| 2 | a03, a04, a09, a10, a13, a15 | 263 / 265 | `DomHelper.findGeneralStatement`; `ExprProcessor.collectCatchVars` (null map key); `SecondaryFunctionsHelper` (if without body); `AssertProcessor` (missing else edge); `SSAUConstructorSparseEx` |
| 3 | a07, then a09 | **264 / 265** | `MemberConverterHelper.toBeRenamed` and `IMemberIdentifierRenamer`: the class name is nullable again |

- The code compiled after every round, with no compile errors.
- The only failure left is the Groovy test, which fails on the Java too.
- The log now holds the same exceptions as the maddi-declaration arms: 49 "parsing failure", 4 NullPointerExceptions
  and 53 methods that couldn't be decompiled. They are fernflower's own behaviour on the obfuscated test data.
- **What changed:** 10 files, 47 diff lines.
- **Round 3's bug** came from round 1 of the compile fixes: making a parameter non-null to match an interface broke a
  test that passes null.
- **Agents that changed nothing:** twice an agent declined to change its file, correctly. Its exception came from a
  method that had failed upstream and was fixed by another group's round.

## Result, four arms

| | maddi alone | maddi's declarations, LLM bodies | LLM alone | LLM alone with test feedback |
|---|---|---|---|---|
| tests passing | 264 / 265 | 264 / 265 | 157 / 265 | 264 / 265 |
| test-driven rounds | 0 | 0 | 0 | 3 |
| `!!` | 2,957 | 2,429 | 1,837 | 1,831 |
| nullable parameters | 755 | 755 | 341 | 345 |
| subagent tokens | n/a | ~3.5M | ~2.87M | ~2.93M (+55k for the test rounds) |

## What this says

- **The tests recover quickly:** 3 rounds, 55k tokens. But only where a test reaches the code.
- **The declarations barely moved:** 345 nullable parameters against maddi's 755. Every `!!` that no test runs over is
  still in place.
- **The test suite is the oracle:** fernflower's tests decompile many classes, so they reach a lot of code. A corpus
  with weaker tests keeps more of those NPEs.
- **maddi's declarations got the same test result with no test feedback at all.**
