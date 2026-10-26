#!/usr/bin/env bash
# Java -> Rust -> LLVM: a second opinion on maddi's verdicts from what LLVM and clippy infer about the Rust they print
# as. rustc's borrow checker catches unsound verdicts (README, "rustc as a judge"); LLVM's inferred parameter attributes
# (captures, readonly) and clippy's lints (needless_pass_by_ref_mut, needless_pass_by_value, ptr_arg) bear on
# imprecise ones, and on dependence: compare.py says which reading each attribute supports.
#
#   maddi-mod/scripts/rust-llvm/run.sh <out dir> [<source dir> <packages>]     from the workspace root
#   maddi-mod/scripts/rust-llvm/run.sh <out dir> --tests                       the Java inputs of maddi-mod's tests
#
# Default input: the shapes beside this script. --tests extracts the text blocks of the analyzer and link tests, each
# in a package of its own (extract.py). Every Java package becomes a crate of its own, compiled function by function
# (stub.py): rustc's errors are recorded per function and those bodies stubbed, so that one error loses one function.
# Needs maddi-dist's CLI (`./gradlew :maddi-cli:installDist`), rustc with clippy-driver, and llvm-cxxfilt (Homebrew
# llvm, or LLVM_CXXFILT). Writes <out>/{results,translated,maddi.log}, <out>/crates/<module>/ (src, rustc.json, lib.ll,
# clippy.json), and <out>/REPORT.txt, compare.py's table.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$1"
out="$(cd "$1" && pwd)"
root="$(pwd)"
lib="$root/maddi-dist/maddi-cli/build/install/maddi/lib"
if [[ "${2:-}" == --tests ]]; then
  src="$out/tests"
  rm -rf "$src"
  python3 -I "$here/extract.py" "$src" "$root/maddi-mod/maddi-modification-analyzer/src/test/java" \
    "$root/maddi-mod/maddi-modification-link/src/test/java" > /dev/null
  # maddi stops at a parse error, and some tests feed it broken Java on purpose: drop what javac rejects, a package
  # at a time (one text block's package), until javac accepts the rest
  for _ in 1 2 3 4 5; do
    rejected="$(javac -proc:none -nowarn -d "$out/javac" -cp "$(echo "$lib"/maddi-annotation-*.jar)" \
      $(printf '%s\n' "$src"/*/*.java) 2>&1 | grep -oE "^$src/[^/]+/" | sort -u)"
    [[ -z "$rejected" ]] && break
    echo "$rejected" | xargs rm -rf
  done
  rm -rf "$out/javac"
  packages="$(cd "$src" && printf '%s\n' */ | tr -d / | paste -sd, -)"
  echo "$(echo "$packages" | tr ',' '\n' | wc -l | tr -d ' ') test inputs javac accepts"
else
  src="${2:-$here/shapes}"
  packages="${3:-a.b}"
fi
cli="$root/maddi-dist/maddi-cli/build/install/maddi/bin/maddi"
archive="$root/maddi/maddi-aapi-archive/src/main/resources/io/codelaser/maddi/aapi/archive/analyzedPackageFiles"
[[ -x "$cli" ]] || { echo "no maddi CLI at $cli: run ./gradlew :maddi-cli:installDist in maddi-dist" >&2; exit 2; }

rm -rf "$out/results" "$out/translated" "$out/crates"
JAVA_OPTS="${JAVA_OPTS:--Xmx3g}" "$cli" --source "$src" --source-packages "$packages" --jmod java.base \
  --classpath "$(echo "$lib"/maddi-annotation-*.jar)" --preload-analysis-results-dirs "$archive/jdk" \
  --translate rust --translate-output "$out/translated" --analysis-results-dir "$out/results" > "$out/maddi.log" 2>&1
[[ -d "$out/translated/src" ]] || { echo "no translation, see $out/maddi.log" >&2; exit 1; }

# one crate per module, with the runtime; every function codegened on its own, attributes and all (small pub
# functions are otherwise only inlined)
for rs in "$out"/translated/src/*.rs; do
  m="$(basename "$rs" .rs)"
  [[ "$m" == lib || "$m" == jrt ]] && continue
  mkdir -p "$out/crates/$m/src"
  cp "$rs" "$out/translated/src/jrt.rs" "$out/crates/$m/src/"
  printf 'pub mod %s;\npub mod jrt;\n' "$m" > "$out/crates/$m/src/lib.rs"
  sed -i.orig -E 's/^([[:space:]]*)((pub )?(unsafe )?fn )/\1#[inline(never)]\n\1\2/' "$out/crates/$m/src/$m.rs"
  rm -f "$out/crates/$m/src/$m.rs.orig"
done

clippy="$(dirname "$(readlink -f "$(command -v cargo-clippy)")")/clippy-driver"
build() {
  local c="$1" m
  m="$(basename "$c")"
  cd "$c" || return
  python3 -I "$here/stub.py" "$c" "$m"
  if rustc --edition 2024 --crate-type lib --crate-name "$m" -A warnings -O -C codegen-units=1 \
      --emit=llvm-ir=lib.ll src/lib.rs 2> /dev/null; then
    "$clippy" --edition 2024 --crate-type lib --crate-name "$m" --emit=metadata=clippy.rmeta --error-format=json \
      -A clippy::all -W clippy::needless_pass_by_ref_mut -W clippy::needless_pass_by_value -W clippy::ptr_arg \
      src/lib.rs 2> clippy.json
  fi
}
export -f build
export clippy here
printf '%s\n' "$out"/crates/*/ | xargs -P "${JOBS:-8}" -I{} bash -c 'build {}'
python3 -I "$here/compare.py" "$out" | tee "$out/REPORT.txt"
