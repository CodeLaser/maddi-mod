#!/bin/bash
# runtests.sh <kotlin classes dir> <out dir>: fernflower's own tests with those classes in front of the originals
set -u
mkdir -p "$2"
sed "s#/Users/bnaudts/git/ws/maddi-all/maddi-mod/maddi-run-analysis/build/j2k/fernflower-reproduce/compile-classes/classes#$1#" /Users/bnaudts/git/ws/maddi-all/maddi-mod/maddi-run-analysis/build/j2k/fernflower-reproduce/tests/kotlin/affected/java.args > "$2/java.args"
cd /Users/bnaudts/git/test-oss/fernflower
java -Xmx2g -XX:+EnableDynamicAgentLoading @"$2/java.args" org.junit.platform.console.ConsoleLauncher execute   --disable-banner --details=summary --scan-classpath /Users/bnaudts/git/test-oss/fernflower/build/classes/java/test   --reports-dir "$2/reports" > "$2/tests.log" 2>&1
tail -15 "$2/tests.log" | grep -E "tests (successful|failed|found)"
