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

import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CodeLaser/maddi-mod#113 (shape 1, fernflower's ControlFlowGraph.removeMarkers over a VBStyleCollection): a for-each
 * over a field whose type is a source subclass of ArrayList, modifying the elements. The subclass had no hidden content
 * when its own methods say nothing about E (Plain), and an ArrayList method's elements were not translated when it
 * adds a type parameter of its own (Keyed, VBStyleCollection's shape).
 */
public class TestCollectionSubclassElements extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class X {
                public static class Block { public int mark; public void clear() { mark = 0; } }
                public static class Plain<E> extends ArrayList<E> { }
                public static class Keyed<E, K> extends ArrayList<E> {
                    private final ArrayList<K> keys = new ArrayList<>();
                    public void addWithKey(E e, K k) { add(e); keys.add(k); }
                    public ArrayList<K> getLstKeys() { return keys; }
                }
                private final Plain<Block> plain = new Plain<>();
                private final Keyed<Block, Integer> keyed = new Keyed<>();
                private final List<Block> list = new ArrayList<>();
                public void plainMarkers() { for (Block b : plain) b.mark = 0; }
                public void keyedMarkers() { for (Block b : keyed) b.mark = 0; }
                public void keyedCall() { for (Block b : keyed) b.clear(); }
                public void keyedGet() { keyed.get(0).clear(); }
                public void listMarkers() { for (Block b : list) b.mark = 0; }
                public int keyedRead() { int n = 0; for (Block b : keyed) n += b.mark; return n; }
            }
            """;

    @DisplayName("modifying the elements of a collection subclass modifies its hidden content")
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
        TypeInfo x = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(x);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(reach).build()).analyze(ao);
        assertEquals("false/true", m(x, "listMarkers"));
        assertEquals("false/true", m(x, "plainMarkers"));
        assertEquals("false/true", m(x, "keyedMarkers"));
        assertEquals("false/true", m(x, "keyedCall"));
        assertEquals("false/true", m(x, "keyedGet"));
        assertEquals("true/true", m(x, "keyedRead"));
        assertEquals("false/true", f(x, "keyed"));
        assertEquals("false/true", f(x, "plain"));
    }

    private static String m(TypeInfo typeInfo, String name) {
        MethodInfo mi = typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
        return mi.isNonModifying() + "/" + mi.isStructurallyNonModifying();
    }

    private static String f(TypeInfo typeInfo, String name) {
        FieldInfo fi = typeInfo.getFieldByName(name, true);
        return fi.isUnmodified() + "/" + fi.isStructurallyUnmodified();
    }
}
