# KotlinConf, arm "maddi's declarations, an LLM's bodies": fernflower, 2026-11-10

## Setup

- **Input:** `TestKotlinConfStubs`, the reproduce options with `Bodies.STUB`.
  - maddi 7b5aae759 and maddi-mod 4c53ac64.
  - 199 Kotlin files and 1,827 stubs.
  - The manifest carries the callees' declarations and the fields of other classes each body uses.
- **The LLM:** Claude Code subagents (Claude Opus 5.5), one prompt per file, written by `bodies.py prepare`.
  - The prompt holds the Java file, the stubbed Kotlin file, and the declarations of the callees and of the other
    files' fields.
  - The subagents never saw maddi's bodies.
- **Splitting:** 21 subagents in all.
  - 4 for the slice of 20 files; those prompts had no field declarations yet.
  - 17 for the other 164 files, each with about 200 KB of prompts.
- **Fixing:** kotlinc compiled all 199 files together after each round. Each file's errors went back to the agent
  that wrote it (`bodies.py assemble`).
- **Fallback:** maddi's body wherever an LLM body still fails (`bodies.py finish`). In the end it wasn't needed.
- **Judge:** the same as the scoreboard's: kotlinc 2.4.0, then fernflower's own tests with the Kotlin classes.

## Correctness

| | maddi's bodies | LLM bodies |
|---|---|---|
| bodies compiling on the first try | n/a | slice 229/235; with field declarations 1,586/1,592 (99.6%) |
| after one fix round | n/a | 1,827 / 1,827 |
| fell back to maddi's body | n/a | 0 |
| files compiling | 199 / 199 | 199 / 199 |
| fernflower tests passing | 264 / 265 | 264 / 265 |

The test that fails is the same in both columns, `GroovyDeclarationDecompilerTest#testGroovyClass`, and it fails on
the Java classes too.

The errors before a fix:
- **In the slice:** all 15 errors came from other classes' fields the prompt didn't declare. The field declarations
  in the manifest fixed that.
- **After that, 9 bodies needed a fix:**
  - `String.join` resolving to kotlin.String;
  - `Objects.requireNonNull` keeping the nullable type;
  - a deprecated `MutableList.sort`;
  - anonymous `IGraph` overrides, written without the interface's declaration.

## Idioms (scripts/kotlin-idioms, the whole translation)

| | maddi's bodies | LLM bodies |
|---|---|---|
| lines | 36,392 | 39,525 |
| `!!` | 2,957 | 2,429 |
| `?.` | 34 | 21 |
| `?:` | 16 | 56 |
| `val` locals | 3,640 | 3,888 |
| `var` locals | 989 | 798 |
| `run` blocks | 11 | 0 |
| `?.let` blocks | 8 | 1 |
| `let` blocks | 0 | 3 |
| `apply` blocks | 6 | 3 |
| `also` blocks | 14 | 6 |
| nullable parameters | 755 | 755 |
| nullable properties | 216 | 216 |
| nullable returns | 291 | 291 |

The declarations are the same in both columns. The `run`, `?.let` and `also` blocks in maddi's column are printer
idioms.

## Behaviour risks the agents reported (their own notes, not caught by the tests)

- **`toString()` returning `x!!`:** the declared return type is non-null, so these throw where Java returned null.
  This happens in StructField, StructClass, StructMethod, ClassWrapper and MethodWrapper.
- **`!!` where Java stored or passed on a null:** ContextUnit, StructModuleAttribute, StructBootstrapMethodsAttribute,
  LazyLoader.addClassLink, SSAConstructorSparseEx.setCurrentVar, ClassWriter.methodToJava and
  SwitchPatternHelper.
- **`FunctionExprent.getExprType`:** `type2!!` throws for a binary function with one DOUBLE operand, where Java
  returned DOUBLE.
- **`InstructionSequence.clone()`:** it throws a NullPointerException. maddi throws `CloneNotSupportedException` here
  and says so (`NULL_OVERRIDE_THROWS`).
- **Dropped null checks:** `FieldExprent` and `ExceptionDeobfuscator` dropped null checks on values maddi declares
  non-null. That is safe only as far as the analysis is right.
- **try-with-resources written as `use {}`:** an exception thrown while closing is handled slightly differently.
- **Unchanged on purpose:** Java's `String.split` and `trim` were kept as `Pattern.split` and `trim { it <= ' ' }`.

## Cost

About 3.5M subagent tokens:
- the slice took about 0.5M;
- the three waves took about 1.1M, 1.0M and 0.85M;
- the fix rounds were small.
