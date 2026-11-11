# Translate Java files to Kotlin (the whole file: declarations and bodies)

The program is fernflower, the Java decompiler: 199 Java files under `/Users/bnaudts/git/test-oss/fernflower/src`.
Every one of them is being translated to Kotlin, by several translators working in parallel, one group of files
each. Your group is listed in `/Users/bnaudts/git/ws/maddi-mcp/llm-bodies-exp/step3/w-alone/groups/<GROUP>.txt`
(paths relative to the source root).

All 199 Kotlin files are compiled together with kotlinc 2.4.0 (no Java sources of fernflower: every fernflower class
comes from Kotlin), against fernflower's library dependencies. Then fernflower's own tests, which are Java compiled
against the Java classes, run with the Kotlin classes in their place. So the Kotlin has to keep the Java binary API.

For each Java file `a/b/C.java` write `/Users/bnaudts/git/ws/maddi-mcp/llm-bodies-exp/step3/w-alone/src/a/b/C.kt`
with the Write tool. Read the Java files of your group; you may read any other Java file of fernflower to see how
your code is used or what it calls. Do not read anything else under `/Users/bnaudts/git` (in particular no existing
Kotlin translation of fernflower, nothing under `maddi`, `maddi-mod` or `llm-bodies-exp` outside `step3/w-alone`).
Do not run kotlinc or the tests: the compile is done for you, and its errors come back to you.

## The conventions every translator follows (so the files agree with each other and with the Java tests)

- Same package, one Kotlin file per Java file, the same class names. A static nested class becomes a nested class,
  an inner (non-static) class an `inner class`. Anonymous classes become `object :` expressions.
- Java `static` members go into a `companion object` of their class (of an interface too): `@JvmStatic` on every
  function, `@JvmField` on every field, or `const val` for a `static final` primitive or String compile-time
  constant. Kotlin callers write `C.foo()` / `C.FIELD`. No top-level functions or properties.
- A non-private Java field becomes a `@JvmField` property with the same name (`val` when final). A private field
  becomes a private property. Kotlin code in other files reads and writes these fields as `x.name`.
- A method keeps its name, its parameters and their order. Getters and setters stay functions: `getName()` stays
  `fun getName()`, and Kotlin callers call `x.getName()`. Never turn a method into a property, or the reverse.
- Visibility: private stays `private`; public, protected and package-private all become public (no modifier).
  Do not use `internal` or `protected`.
- A Java class that is not final becomes `open class`; an abstract class `abstract class`. Every non-private,
  non-static, non-final method of a non-final class is `open` (or `abstract`). Overrides say `override`.
- An enum becomes an `enum class`; a record becomes a `@JvmRecord data class` whose components are `val`
  properties, read as `r.x` by Kotlin code; an interface stays an interface (default methods get bodies).
- Overloaded methods stay overloaded. Varargs stay `vararg`. Checked exceptions: add `@Throws(...)` where the
  Java method declares `throws`.
- Types: you decide nullability (`T` or `T?`) and read-only versus mutable collections (`List` / `MutableList`),
  from what the Java code does. Choose what the code needs; other translators decide for their files the same way,
  and a mismatch shows up as a compile error that will come back to one of you.
- Java library calls may stay as they are (`java.util.HashMap`, `map.get(k)`, `Objects.requireNonNull`, ...).

## The bodies

- Translate faithfully: the same behaviour, the same exceptions in the same places (where Java throws a
  NullPointerException Kotlin may throw one too, `!!` at the same dereference), the same order of side effects.
- Write idiomatic Kotlin where it does not change behaviour: `val` over `var`, `for (x in xs)`, `when`, string
  templates, smart casts. Keep the comments.

## When you are done

Answer briefly (no code): the files written, and a short list of the places where the translation may behave
differently from the Java, if any. Keep that answer under 300 words.

## Fix rounds

Later you may get a message saying that the compile errors of your files are in
`/Users/bnaudts/git/ws/maddi-mcp/llm-bodies-exp/step3/w-alone/errors/<GROUP>.txt`. Fix them by editing your own
files only. Then you may read the other translators' Kotlin files under `w-alone/src` to see their declarations.
An error in your file caused by another file's declaration is fixed at your use site (`!!`, `?.`, a conversion), not
by asking for that declaration to change; change one of your own declarations when that is the better fix and its
callers are in your files. Answer with one line per file changed.

## Test-fix rounds

Everything compiles now, but fernflower's tests (run with the Kotlin classes) fail. A message may say that the
exceptions thrown in the test run whose top stack frame is in one of your files are in
`/Users/bnaudts/git/ws/maddi-mcp/llm-bodies-exp/step3/w-alone/errors-tests/<GROUP>.txt` (with the stack), or give you
other test failures to look at. The Java code does not throw there: make the Kotlin behave like the Java. Compare with
the Java source; the usual cause is a `!!`, a non-null declaration or a `lateinit` where the Java value is null.
- Fix it where the cause is, which may be a declaration of yours (make it nullable) rather than the line that throws.
- Changing a declaration may break its callers in other files: that is fine, their compile errors go back to their
  translators. If the cause is in another translator's file, say which file and what change in your answer.
- Edit only your own files. Do not run kotlinc or the tests. Answer with one line per file changed.
