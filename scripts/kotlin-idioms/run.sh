#!/usr/bin/env bash
# Idiom counts for translated Kotlin (CodeLaser/maddi-mod#35). Usage: run.sh [--files] <label>=<dir> ...
# Needs Java 22+ (multi-file source launch). The Kotlin parser's jars come from ~/.m2, else from Maven Central into
# ~/.cache/kotlin-idioms.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
kotlin=2.4.10
jars=(
  "org/jetbrains/kotlin/kotlin-compiler-embeddable/$kotlin/kotlin-compiler-embeddable-$kotlin.jar"
  "org/jetbrains/kotlin/kotlin-stdlib/$kotlin/kotlin-stdlib-$kotlin.jar"
  "org/jetbrains/kotlin/kotlin-script-runtime/$kotlin/kotlin-script-runtime-$kotlin.jar"
  "org/jetbrains/kotlin/kotlin-daemon-embeddable/$kotlin/kotlin-daemon-embeddable-$kotlin.jar"
  "org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar"
  "org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0/kotlinx-coroutines-core-jvm-1.8.0.jar"
  "org/jetbrains/annotations/13.0/annotations-13.0.jar"
)
cache="${XDG_CACHE_HOME:-$HOME/.cache}/kotlin-idioms"
cp=""
for jar in "${jars[@]}"; do
  path="$HOME/.m2/repository/$jar"
  if [[ ! -f "$path" ]]; then
    path="$cache/$jar"
    if [[ ! -f "$path" ]]; then
      mkdir -p "$(dirname "$path")"
      curl -fsSL -o "$path" "https://repo1.maven.org/maven2/$jar"
    fi
  fi
  cp="$cp${cp:+:}$path"
done
exec java -Xmx2g -cp "$cp" "$here/KotlinIdioms.java" "$@"
