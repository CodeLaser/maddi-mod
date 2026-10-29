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

package io.codelaser.maddi.aapi.parser;

import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/*
What the JDK hints leave open: the methods that are modifying, and the parameters that are modified, only because no
annotation says otherwise (ASSUMED_DEFAULTS). Each is either a gap (ThreadLocal.get was one) or a true modification
nobody wrote down; annotating it, either way, takes it off the list.

The counts per package are a ratchet: a new unannotated method raises one, annotating lowers one; both fail until the
table below is updated. The full list is written to build/assumed-defaults.txt, ready to work through. The JDK's API
differs per release (java.security on 26), so the table holds only on the release the jdk results were compiled on
(CompileAnalysisHints.JDK_RELEASE_FILE); elsewhere the list is still written.
 */
public class TestAssumedDefaults extends CommonTest {

    // package -> {modifying methods, modified parameters} assumed by default
    private static final Map<String, List<Integer>> EXPECTED = new TreeMap<>(Map.ofEntries(
            Map.entry("java.awt", List.of(273, 176)),
            Map.entry("java.awt.event", List.of(1, 1)),
            Map.entry("java.awt.image", List.of(7, 1)),
            Map.entry("java.io", List.of(519, 152)),
            Map.entry("java.lang", List.of(172, 57)),
            Map.entry("java.lang.annotation", List.of(0, 3)),
            Map.entry("java.lang.constant", List.of(16, 10)),
            Map.entry("java.lang.invoke", List.of(5, 0)),
            Map.entry("java.lang.ref", List.of(12, 4)),
            Map.entry("java.lang.reflect", List.of(117, 15)),
            Map.entry("java.net", List.of(248, 73)),
            Map.entry("java.net.http", List.of(23, 22)),
            Map.entry("java.nio.charset", List.of(0, 2)),
            Map.entry("java.nio.file", List.of(13, 34)),
            Map.entry("java.security", List.of(173, 110)),
            Map.entry("java.time", List.of(16, 5)),
            Map.entry("java.time.chrono", List.of(34, 8)),
            Map.entry("java.time.format", List.of(40, 6)),
            Map.entry("java.time.temporal", List.of(31, 2)),
            Map.entry("java.util", List.of(695, 323)),
            Map.entry("java.util.concurrent", List.of(577, 75)),
            Map.entry("java.util.concurrent.atomic", List.of(93, 1)),
            Map.entry("java.util.function", List.of(41, 0)),
            Map.entry("java.util.jar", List.of(25, 8)),
            Map.entry("java.util.logging", List.of(90, 7)),
            Map.entry("java.util.random", List.of(80, 5)),
            Map.entry("java.util.regex", List.of(22, 4)),
            Map.entry("java.util.stream", List.of(175, 4)),
            Map.entry("java.util.zip", List.of(133, 69)),
            Map.entry("javax.accessibility", List.of(83, 15)),
            Map.entry("javax.security.auth", List.of(2, 0)),
            Map.entry("javax.swing", List.of(315, 157)),
            Map.entry("javax.swing.event", List.of(17, 17)),
            Map.entry("javax.swing.table", List.of(16, 7)),
            Map.entry("javax.swing.text", List.of(54, 38)),
            Map.entry("org.junit.jupiter.api", List.of(0, 92)),
            Map.entry("org.junit.jupiter.api.function", List.of(2, 0)),
            Map.entry("org.slf4j", List.of(52, 0))
    ));

    @Test
    public void test() throws IOException {
        Map<String, int[]> counts = new TreeMap<>();
        List<String> lines = new ArrayList<>();
        Set<TypeInfo> seen = new HashSet<>();
        for (TypeInfo typeInfo : allTypes) {
            if (!seen.add(typeInfo)) continue;
            int[] c = counts.computeIfAbsent(typeInfo.packageName(), _ -> new int[2]);
            typeInfo.constructorAndMethodStream().forEach(mi -> {
                if (assumed(mi, NON_MODIFYING_METHOD)) {
                    c[0]++;
                    lines.add("M " + mi.fullyQualifiedName());
                }
                for (ParameterInfo pi : mi.parameters()) {
                    if (assumed(pi, UNMODIFIED_PARAMETER)) {
                        c[1]++;
                        lines.add("P " + mi.fullyQualifiedName() + " " + pi.name());
                    }
                }
            });
        }
        Collections.sort(lines);
        Files.write(Path.of("build/assumed-defaults.txt"), lines);

        Map<String, List<Integer>> actual = counts.entrySet().stream()
                .filter(e -> e.getValue()[0] + e.getValue()[1] > 0)
                .collect(Collectors.toMap(Map.Entry::getKey, e -> List.of(e.getValue()[0], e.getValue()[1]),
                        (a, _) -> a, TreeMap::new));
        assumeTrue(CompileAnalysisHints.runningJdkRelease() == CompileAnalysisHints.committedJdkRelease(),
                "the table holds on the JDK release of the jdk results only");
        assertEquals(table(EXPECTED), table(actual), "assumed modifying methods and modified parameters per package;"
                + " see build/assumed-defaults.txt");
    }

    private static boolean assumed(Info info, Property property) {
        return info.analysis().getOrDefault(ASSUMED_DEFAULTS, ValueImpl.SetOfStringsImpl.EMPTY_SET).set()
                .contains(property.key());
    }

    // one entry per line, in the form of the EXPECTED table, so that a failure can be pasted in
    private static String table(Map<String, List<Integer>> map) {
        return map.entrySet().stream()
                .map(e -> "Map.entry(\"" + e.getKey() + "\", List.of(" + e.getValue().get(0) + ", "
                          + e.getValue().get(1) + "))")
                .collect(Collectors.joining(",\n"));
    }
}
