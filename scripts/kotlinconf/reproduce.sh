#!/usr/bin/env bash
# KotlinConf "numbers reproduce" (CodeLaser/maddi-mod#50): the scoreboard's maddi translation (TestKotlinConfReproduce,
# the ablation's -both output) twice per corpus on this machine, diffed: the translated .kt (main and tests), the
# printer's messages, the report (compiling files, errors, tests), and the idiom counts of scripts/kotlin-idioms (#35).
# Measure-only: no .ratchet file is read or written.
#
#   maddi-mod/scripts/kotlinconf/reproduce.sh <out dir> [corpus ...]     from the workspace root; corpus: langchain4j |
#                                                                       fernflower | guava (default: all three)
#
# Writes <out>/<corpus>/run-{1,2}/ (src, test-src, report.txt, messages.txt), run-{1,2}.log, idioms-{1,2}.txt, and
# <out>/SUMMARY.txt: machine, commits, corpus SHAs, and per corpus whether the runs agree.
set -uo pipefail
mkdir -p "$1"
out="$(cd "$1" && pwd)"
shift
corpora=("$@")
[[ ${#corpora[@]} -eq 0 ]] && corpora=(langchain4j fernflower guava)
root="$(pwd)"
idioms="$root/maddi-mod/scripts/kotlin-idioms/run.sh"
oss="${CORPUS_ROOT:-$HOME/git/test-oss}"

summary="$out/SUMMARY.txt"
{
  echo "machine: $(hostname) $(uname -sm), $(nproc 2>/dev/null || sysctl -n hw.ncpu) cpus"
  echo "java: $(java -version 2>&1 | head -1)"
  for r in maddi maddi-mod; do echo "$r: $(git -C "$root/$r" rev-parse --short HEAD)$(git -C "$root/$r" diff --quiet HEAD || echo ' (dirty)')"; done
  echo "date: $(date -u +%Y-%m-%dT%H:%MZ)"
} > "$summary"

status=0
for c in "${corpora[@]}"; do
  echo "$c: corpus $(git -C "$oss/$c" rev-parse --short HEAD 2>/dev/null || echo '?')" >> "$summary"
  mkdir -p "$out/$c"
  for i in 1 2; do
    built="$root/maddi-mod/maddi-run-analysis/build/j2k/$c-reproduce"
    rm -rf "$built" "$out/$c/run-$i"
    start=$(date +%s)
    (cd "$root/maddi-mod" && ABLATION_CORPUS=$c ./gradlew -q --no-daemon :maddi-run-analysis:slowTest \
      --tests "*TestKotlinConfReproduce" --rerun) > "$out/$c/run-$i.log" 2>&1
    rc=$?
    mkdir -p "$out/$c/run-$i"
    for part in src test-src report.txt messages.txt; do
      [[ -e "$built/$part" ]] && cp -R "$built/$part" "$out/$c/run-$i/"
    done
    files=$(find "$out/$c/run-$i/src" -name '*.kt' 2>/dev/null | wc -l | tr -d ' ')
    echo "$c: run $i rc=$rc, $files main .kt files, $(( $(date +%s) - start ))s" >> "$summary"
    [[ $rc -ne 0 ]] && status=1
    "$idioms" "$c=$out/$c/run-$i/src" > "$out/$c/idioms-$i.txt" 2>&1 || echo "$c: idioms $i failed" >> "$summary"
  done
  if diff -r "$out/$c/run-1" "$out/$c/run-2" > "$out/$c/diff.txt"; then
    echo "$c: identical (.kt, messages, report)" >> "$summary"
  else
    echo "$c: DIFFERENT, $(grep -c '^diff\|^Only in' "$out/$c/diff.txt") files (diff.txt)" >> "$summary"
    status=1
  fi
  if diff <(sed "s#run-1#run-N#g" "$out/$c/idioms-1.txt") <(sed "s#run-2#run-N#g" "$out/$c/idioms-2.txt") > /dev/null; then
    echo "$c: idiom counts identical" >> "$summary"
  else
    echo "$c: idiom counts DIFFERENT" >> "$summary"
    status=1
  fi
done
cat "$summary"
exit $status
