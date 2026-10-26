# KotlinConf scoreboard, 2026-10-27

Java -> Kotlin on three corpora: IntelliJ's J2K against maddi's printer in four arms. The arms switch the analysis's
facts on and off (TestJavaToKotlinAblation): `none` prints from the Java source alone, `modification` adds the
modification facts (read-only collection types), `nullability` adds the nullability verdicts, `both` is maddi as
shipped. The judge is the same for every column: kotlinc 2.4.0 on JDK 27, then the corpus's own tests against the
translated classes (TestJavaToKotlinExternal for J2K's output).

## Provenance

- maddi arms: laser1, 2026-10-27, maddi f46b16b9d, maddi-mod a5c42cbb.
- J2K: IntelliJ 2026.2.3 through the maddi-j2k plugin, on laser1; langchain4j dab06ac18 (2026-10-12), fernflower
  f59df903 (2026-10-13), the same corpus commits as maddi's. Guava's J2K run did not complete.
- Idiom counts: `scripts/kotlin-idioms/run.sh` (CodeLaser/maddi-mod#35), Kotlin parser 2.4.10, over the main sources.
- Meta's kotlin_ast_tools cleanups are not applied to J2K's output: they are three rewrites in 211 lines (Android
  TextUtils, Guava Strings, one var -> val pattern); the J2K column is IntelliJ's output as is.

## Correctness

| corpus | J2K | none | modification | nullability | both |
|---|---|---|---|---|---|
| langchain4j, files compiling / 346 | 148 | 306 | 306 | 345 | **346** |
| langchain4j, tests passing / 1,106 | 326 | 628 | 628 | 1,106 | **1,106** |
| fernflower, files compiling / 199 | 37 | 99 | 99 | 198 | **199** |
| fernflower, tests passing / 264 | 39 | 24 | 24 | 264 | **264** |
| guava, files compiling / 610 | did not complete | 473 | 473 | 603 | **603** |
| guava, tests passing | did not complete | 561,182 | 561,182 | 861,116 | **861,124** |

The nullability verdicts carry correctness; the modification facts change types, not whether they compile (one
file each on langchain4j and fernflower).

## Idioms (counts over the translated main sources)

| corpus | measure | J2K | none | modification | nullability | both |
|---|---|---|---|---|---|---|
| langchain4j | `!!` | 220 | 182 | 182 | 234 | 233 |
| langchain4j | read-only / mutable collection types | 7 / 628 | 2 / 657 | 152 / 507 | 2 / 657 | 152 / 507 |
| langchain4j | nullable parameters / returns | 1,420 / 573 | 148 / 45 | 148 / 45 | 1,011 / 273 | 1,011 / 273 |
| langchain4j | `?.` / `?:` / `?.let` | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 | 6 / 7 / 6 | 6 / 7 / 6 |
| langchain4j | val / var properties | 506 / 247 | 505 / 247 | 505 / 247 | 505 / 247 | 505 / 247 |
| fernflower | parse errors | 10 | 0 | 0 | 0 | 0 |
| fernflower | `!!` | 997 | 393 | 393 | 2,863 | 2,856 |
| fernflower | read-only / mutable collection types | 0 / 712 | 5 / 807 | 144 / 668 | 5 / 807 | 144 / 668 |
| fernflower | nullable parameters / returns | 725 / 236 | 31 / 2 | 31 / 2 | 764 / 291 | 764 / 291 |
| fernflower | val / var properties | 1,264 / 265 | 1,177 / 262 | 1,177 / 262 | 1,177 / 262 | 1,177 / 262 |
| guava | `!!` | — | 797 | 797 | 1,566 | 1,566 |
| guava | read-only / mutable collection types | — | 113 / 2,979 | 214 / 2,878 | 113 / 2,979 | 214 / 2,878 |
| guava | nullable parameters / returns | — | 162 / 21 | 162 / 21 | 1,728 / 1,009 | 1,728 / 1,009 |
| guava | `?.` / `?:` / `?.let` | — | 1 / 0 / 0 | 1 / 0 / 0 | 58 / 76 / 25 | 58 / 76 / 25 |

Reading the `!!` row: J2K treats most values it cannot prove nullable as non-null, so it writes fewer `!!` and
compiles fewer files (fernflower: 37 of 199). maddi's verdicts mark the values that can be null; a dereference of
such a value gets `!!`, where Java would throw the same NullPointerException. On fernflower 965 of the `!!` dereference
a call result (`getExprents()` 174, `Map.get` 118). Lines are not comparable across the J2K and maddi columns (J2K's
output has 63% more lines on langchain4j), so the table gives counts, not rates per kLOC.

## Reproducibility

`reproduce.sh` on the Mac, maddi f46b16b9d / maddi-mod a5c42cbb, 2026-10-26: two runs per corpus, translations,
messages, reports and idiom counts byte-identical on all three (#50).

## Open

- Guava, 7 files that do not compile in `both`: 2 from verdicts (`Table.Cell.getValue(): V?`,
  `IndexedImmutableSet.get(): E?`), 5 from the printer (`Ordering`, `Iterators`, `HashBiMap`, `ImmutableMultiset`,
  `TypeToken`).
