#!/bin/bash
# check.sh <dir with my .kt files>: compile them alone against fernflower's Java classes (A),
# and with maddi's other 196 Kotlin files (B, every class Kotlin). Errors in my files only.
E=/Users/bnaudts/git/ws/maddi-mcp/llm-bodies-exp
F=/Users/bnaudts/git/ws/maddi-all/maddi-mod/maddi-run-analysis/build/j2k/fernflower-reproduce/src
mine="$1"; mode="${2:-AB}"
cd "$E"
if [[ $mode == *A* ]]; then
  rm -rf out-A; $E/kc/kotlinc -no-stdlib -no-reflect -jvm-target 21 -classpath "$(cat fernflower.cp)" -d out-A "$mine"/*.kt 2>&1 | tee A.raw | grep -E "\.kt:[0-9]+:[0-9]+: error" > A.log; echo "A: $(wc -l < A.log) errors"; cat A.log
fi
if [[ $mode == *B* ]]; then
  names=$(cd "$mine"; ls *.kt)
  others=$(cd $F; find . -name '*.kt' | while read f; do b=$(basename $f); echo "$names" | grep -qx "$b" || echo "$F/${f#./}"; done)
  # classpath without fernflower's own classes: every fernflower class from Kotlin
  cp=$(tr ':' '\n' < fernflower.cp | grep -v 'test-oss/fernflower/build' | paste -sd: -)
  rm -rf out-B; $E/kc/kotlinc -no-stdlib -no-reflect -jvm-target 21 -classpath "$cp" -d out-B $others "$mine"/*.kt 2>&1 | tee B.raw | grep -E "\.kt:[0-9]+:[0-9]+: error" > B-all.log
  grep -E "^$mine/|^$(cd $mine; pwd)/" B-all.log > B.log; echo "B: $(wc -l < B-all.log) errors in all, $(wc -l < B.log) in mine"; cat B.log
fi
echo "classes: A $(find out-A -name "*.class" 2>/dev/null | wc -l), B $(find out-B -name "*.class" 2>/dev/null | wc -l)"
