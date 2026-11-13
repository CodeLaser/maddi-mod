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

package io.codelaser.maddi.modification.analyzer.modification;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CodeLaser/maddi-mod#62, the channel left after df3d86a2: an element of a map field reached through one of the map's
 * views, a for-each over {@code values()} or an entry's {@code getValue()}, modified. The map's content is modified,
 * so the method is deep-modifying; not structurally, the map itself keeps its keys and values.
 */
public class TestMapViewElementModification extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class P {
                private final Map<String, Set<String>> articleTags = new HashMap<>();
                private final List<Set<String>> lists = new ArrayList<>();
                public void renameTags(String oldTag, String newTag) {
                    for (Set<String> tags : articleTags.values()) { if (tags.remove(oldTag)) tags.add(newTag); }
                }
                public void clearTags() {
                    for (Map.Entry<String, Set<String>> e : articleTags.entrySet()) { e.getValue().clear(); }
                }
                public void renameInList(String oldTag) {
                    for (Set<String> tags : lists) tags.remove(oldTag);
                }
                public int count() {
                    int n = 0;
                    for (Set<String> tags : articleTags.values()) n += tags.size();
                    return n;
                }
            }
            """;

    @DisplayName("modifying an element reached through a map's values() or an entry's getValue() modifies the map's content")
    @Test
    public void fixpoint() {
        test(false);
    }

    @DisplayName("idem, with the reachability pass")
    @Test
    public void reachability() {
        test(true);
    }

    private void test(boolean reach) {
        TypeInfo p = javaInspector.parse("a.b.P", INPUT);
        List<Info> ao = prepWork(p);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(reach).build()).analyze(ao);
        String verdicts = Stream.of("renameTags", "clearTags", "renameInList", "count")
                .map(n -> n + "=" + m(p, n)).collect(Collectors.joining(", "));
        assertEquals("renameTags=false/true, clearTags=false/true, renameInList=false/true, count=true/true",
                verdicts);
    }

    private static String m(TypeInfo typeInfo, String name) {
        MethodInfo mi = typeInfo.methods().stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
        return mi.isNonModifying() + "/" + mi.isStructurallyNonModifying();
    }
}
