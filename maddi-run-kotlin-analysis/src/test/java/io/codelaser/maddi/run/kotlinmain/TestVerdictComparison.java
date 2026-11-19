/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.codelaser.maddi.run.kotlinmain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.codelaser.maddi.run.kotlinmain.VerdictComparison.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The pairing and sorting of {@link VerdictComparison}, on dumps small enough to read. */
public class TestVerdictComparison {

    private static final List<String> JAVA = List.of(
            "F true a.A.name",
            "F true a.A.flag",
            "M true a.A.f(int)",
            "M true a.A.g()",
            "M false a.A.h()",
            "M true a.A.helper(String)",
            "M true a.L.remove(int)",
            "M true a.A.javaOnly()",
            "M true a.R.edge()",
            "M false a.A.box(Integer,String)",
            "M true a.A.<init_0>()",
            "P true a.A.f(int)#0");
    private static final List<String> KOTLIN = List.of(
            "F true a.A.name",
            "F false a.A.flag",
            "F true a.A.Companion",
            "M true a.A.f(int)",
            "M false a.A.g()",
            "M true a.A.h()",
            "M NONE a.A.Companion.helper(String)",
            "M true a.L.removeAt(int)",
            "M true a.A.<static_0>()",
            "M true a.A.getName()",
            "M false a.A.setName(String)",
            "M true a.A.isFlag()",
            "M true a.A.kotlinOnly()",
            "M true a.R.getEdge()",
            "M true a.A.box(int,String)",
            "M true a.E.name()",
            "M true a.R.component1()",
            "M true a.R.copy(a.E)",
            "F true a.A.is_static",
            "M true a.A.is_static()",
            "M false a.A.set_static(boolean)",
            "P NONE a.A.f(int)#0");

    @Test
    public void namesArePairedAndDifferencesSorted() {
        Map<String, String> java = read(JAVA, false, false);
        Map<String, String> kotlin = read(KOTLIN, false, true);
        Set<String> kotlinFields = only(kotlin, "F").keySet();

        Outcome f = compare("F", only(java, "F"), only(kotlin, "F"), kotlinFields, Set.of("a.A"));
        assertEquals(1, f.count(AGREE));
        assertEquals(1, f.count(KOTLIN_ONLY), "is_static has no Java field here");
        assertEquals(1, f.count(WEAKER), "flag: true -> false");
        assertEquals(1, f.count(COMPANION_ONLY), "the companion's own field");

        Outcome m = compare("M", only(java, "M"), only(kotlin, "M"), kotlinFields, Set.of("a.A"));
        assertEquals(3, m.count(AGREE), "f, removeAt as remove(int), the record's getEdge() as edge()");
        assertEquals(1, m.count(WEAKER), "g");
        assertEquals(1, m.count(JAVA_INITIALIZER_ONLY));
        assertEquals(1, m.count(ENUM_ONLY));
        assertEquals(2, m.count(STRONGER), "h, and box: Integer paired with int");
        assertEquals(1, m.count(KOTLIN_NONE), "the companion's helper, paired without .Companion");
        assertEquals(1, m.count(JAVA_ONLY));
        assertEquals(5, m.count(ACCESSOR_ONLY), "getName, setName, isFlag, is_static(), set_static");
        assertEquals(2, m.count(DATA_CLASS_ONLY));
        assertEquals(1, m.count(STATIC_INIT_ONLY));
        assertEquals(List.of("true  a.A.kotlinOnly()"), m.categories().get(KOTLIN_ONLY));

        Outcome p = compare("P", only(java, "P"), only(kotlin, "P"), kotlinFields, Set.of("a.A"));
        assertEquals(1, p.count(KOTLIN_NONE));
    }

    @Test
    public void aNestedTypeIsKnownByItsPrimaryType() {
        Map<String, String> java = read(List.of("@FinalFields a.A"), true, false);
        Map<String, String> kotlin = read(List.of("@FinalFields a.A", "@Immutable a.A.B", "@Immutable a.A.B.C",
                "@Immutable a.Z.Q"), true, true);
        Outcome t = compare("T", java, kotlin, Set.of(), java.keySet());
        assertEquals(2, t.count(NESTED_ONLY));
        assertEquals(1, t.count(KOTLIN_ONLY), "a.Z is not a Java type");
    }

    @Test
    public void theRatchetPinsDifferencesAndNamingCounts() {
        Map<String, String> java = Map.of("a.A.f", "true", "a.A.g", "false", "a.A.h", "true");
        Map<String, String> kotlin = Map.of("a.A.f", "false", "a.A.g", "true", "a.A.h", "true", "a.A.getH()", "true");
        Outcome o = compare("M", java, kotlin, Set.of("a.A.h"), Set.of("a.A"));
        List<String> lines = ratchetLines(List.of("java primaryTypes=1 placeholders=0 prepErrors=0"), List.of(o));
        assertEquals(List.of(
                "java primaryTypes=1 placeholders=0 prepErrors=0",
                o.summary(),
                "M kotlin-weaker  true -> false  a.A.f",
                "M kotlin-stronger  false -> true  a.A.g"), lines, "the accessor is counted in the summary only");

        List<String> recorded = List.of("# a comment", "", lines.get(1), lines.get(3), lines.get(2), lines.get(0));
        assertEquals("", ratchetDiff(recorded, lines), "order, comments and blank lines are free");
        assertEquals("", ratchetDiff(recorded, lines.subList(1, 4)), "dumps only: no per-side lines to compare");

        String diff = ratchetDiff(recorded, List.of(lines.get(0), lines.get(1), lines.get(2), "M kotlin-stronger  false -> true  a.A.x"));
        assertEquals("""
                new, not recorded (a regression, or a change to record deliberately):
                + M kotlin-stronger  false -> true  a.A.x
                recorded, now gone (an improvement: remove them from the ratchet in this commit):
                - M kotlin-stronger  false -> true  a.A.g
                """, diff);
    }
}
