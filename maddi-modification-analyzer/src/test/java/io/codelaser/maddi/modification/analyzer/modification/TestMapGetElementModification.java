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
 * CodeLaser/maddi-mod#104: an element of a collection held as a map field's value, reached by a for-each directly over
 * the lookup ({@code for (C c : groups.get(k))}), modified. The element sits two links deep; the map's content is
 * modified, so the method is deep-modifying, not structurally.
 */
public class TestMapGetElementModification extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class Owner {
                public static class C {
                    private Object pool;
                    public Object getPool() { if (pool == null) pool = new Object(); return pool; }
                }
                private final Map<String, List<C>> groups = new HashMap<>();
                private final List<List<C>> nested = new ArrayList<>();
                public void viaMapGet(String k) { for (C c : groups.get(k)) c.getPool(); }
                public void viaListGet(int i) { for (C c : nested.get(i)) c.getPool(); }
                public void viaLocal(String k) { List<C> l = groups.get(k); for (C c : l) c.getPool(); }
                public int reads(String k) { int n = 0; for (C c : groups.get(k)) if (c != null) n++; return n; }
            }
            """;

    @DisplayName("modifying an element of a map value, iterated directly over map.get(k), modifies the map's content")
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
        TypeInfo x = javaInspector.parse("a.b.Owner", INPUT);
        List<Info> ao = prepWork(x);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(reach).build()).analyze(ao);
        String verdicts = Stream.of("viaMapGet", "viaListGet", "viaLocal", "reads")
                .map(n -> n + "=" + m(x, n)).collect(Collectors.joining(", "));
        assertEquals("viaMapGet=false/true, viaListGet=false/true, viaLocal=false/true, reads=true/true", verdicts);
    }

    private static String m(TypeInfo typeInfo, String name) {
        MethodInfo mi = typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
        return mi.isNonModifying() + "/" + mi.isStructurallyNonModifying();
    }
}
