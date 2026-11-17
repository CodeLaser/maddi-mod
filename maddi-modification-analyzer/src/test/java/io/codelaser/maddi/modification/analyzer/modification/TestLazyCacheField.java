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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.prepwork.callgraph.LazyCacheField;

/**
 * hashCode, equals, compareTo and toString are queries by contract (CodeLaser/maddi#208, maddi-mod#124).
 * <ul>
 *     <li>A lazily cached hash (fernflower's {@code VarVersion}, the {@code String.hashCode()} copy-to-local form) is
 *     a memo: its field is disclaimed as {@code @IgnoreModifications} would, so hashCode is non-modifying, and a key
 *     looked up in a {@code HashMap} is rightly unmodified. A hashCode that writes anything else stays modifying.</li>
 *     <li>A bodiless re-declaration of equals/hashCode in an interface takes Object's contract, whatever an
 *     implementation does.</li>
 * </ul>
 */
public class TestLazyCacheField extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            class X {
                static class VarVersion {
                    final int var;
                    final int version;
                    private int hashCode = -1;
                    VarVersion(int var, int version) { this.var = var; this.version = version; }
                    @Override public int hashCode() {
                        if (hashCode == -1) hashCode = this.var * 3 + this.version;
                        return hashCode;
                    }
                    @Override public boolean equals(Object o) {
                        return o instanceof VarVersion v && v.var == var && v.version == version;
                    }
                }
                static class StrLike {
                    private final String s;
                    private int hash;
                    StrLike(String s) { this.s = s; }
                    @Override public int hashCode() {
                        int h = hash;
                        if (h == 0 && !s.isEmpty()) {
                            h = s.hashCode();
                            hash = h;
                        }
                        return h;
                    }
                }
                static class Counter {
                    private int calls;
                    @Override public int hashCode() {
                        calls++;
                        return 3;
                    }
                }
                static class Lookup {
                    private final Map<VarVersion, String> map = new HashMap<>();
                    String get(VarVersion pair) { return map.get(pair); }
                }
                interface Keyed {
                    boolean equals(Object o);
                    int hashCode();
                    String name();
                }
                static class CountingKey implements Keyed {
                    private int n;
                    @Override public boolean equals(Object o) { n++; return o == this; }
                    @Override public int hashCode() { return 1; }
                    @Override public String name() { return "k"; }
                }
                static class ClearingKey implements Keyed {
                    @Override public boolean equals(Object o) {
                        if (o instanceof List<?> list) list.clear();
                        return o == this;
                    }
                    @Override public int hashCode() { return 2; }
                    @Override public String name() { return "c"; }
                }
            }
            """;

    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(X);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10)
                .setModificationViaReachability(true)
                .build()).analyze(ao);
        StringBuilder sb = new StringBuilder();
        for (String typeName : List.of("VarVersion", "StrLike", "Counter", "Keyed", "CountingKey",
                "ClearingKey")) {
            TypeInfo t = X.findSubType(typeName);
            for (FieldInfo f : t.fields()) {
                sb.append(typeName).append('.').append(f.name()).append(" ignoreMod=").append(f.isIgnoreModifications())
                        .append('\n');
            }
            for (String name : List.of("hashCode", "equals")) {
                t.methodStream().filter(m -> m.name().equals(name)).forEach(m -> {
                    sb.append(typeName).append('.').append(name).append(" nonModifying=").append(m.isNonModifying());
                    m.parameters().forEach(p -> sb.append(' ').append(p.name()).append('=').append(p.isUnmodified()));
                    sb.append('\n');
                });
            }
        }
        MethodInfo get = X.findSubType("Lookup").findUniqueMethod("get", 1);
        sb.append("Lookup.get pair=").append(get.parameters().getFirst().isUnmodified()).append('\n');
        assertEquals("""
                VarVersion.var ignoreMod=false
                VarVersion.version ignoreMod=false
                VarVersion.hashCode ignoreMod=true
                VarVersion.hashCode nonModifying=true
                VarVersion.equals nonModifying=true o=true
                StrLike.s ignoreMod=false
                StrLike.hash ignoreMod=true
                StrLike.hashCode nonModifying=true
                Counter.calls ignoreMod=false
                Counter.hashCode nonModifying=false
                Keyed.hashCode nonModifying=true
                Keyed.equals nonModifying=true o=true
                CountingKey.n ignoreMod=false
                CountingKey.hashCode nonModifying=true
                CountingKey.equals nonModifying=false o=true
                ClearingKey.hashCode nonModifying=true
                ClearingKey.equals nonModifying=true o=false
                Lookup.get pair=true
                """, sb.toString());
    }

    // maddi-mod#136: a lazily created view (gson's LinkedTreeMap.entrySet) is a memo of its slot only
    @Language("java")
    private static final String INPUT_VIEW = """
            package a.b;
            import java.util.*;
            class Y {
                static class Tree<K, V> extends AbstractMap<K, V> {
                    private final Map<K, V> store = new HashMap<>();
                    private EntrySet entrySet;
                    private List<V> copy;
                    private final List<V> items = new ArrayList<>();
                    class EntrySet extends AbstractSet<Map.Entry<K, V>> {
                        @Override public int size() { return store.size(); }
                        @Override public Iterator<Map.Entry<K, V>> iterator() { return store.entrySet().iterator(); }
                        @Override public void clear() { Tree.this.store.clear(); }
                    }
                    @Override public Set<Map.Entry<K, V>> entrySet() {
                        EntrySet result = entrySet;
                        if (result == null) {
                            result = entrySet = new EntrySet();
                        }
                        return result;
                    }
                    List<V> copy() {
                        if (copy == null) copy = new ArrayList<>(items);
                        return copy;
                    }
                    void clearViaView() { entrySet().clear(); }
                }
                static int count(Tree<String, String> tree) {
                    int n = 0;
                    for (Map.Entry<String, String> e : tree.entrySet()) n++;
                    return n;
                }
            }
            """;

    @Test
    public void views() {
        TypeInfo Y = javaInspector.parse("a.b.Y", INPUT_VIEW);
        List<Info> ao = prepWork(Y);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10)
                .setModificationViaReachability(true)
                .build()).analyze(ao);
        TypeInfo tree = Y.findSubType("Tree");
        StringBuilder sb = new StringBuilder();
        for (String f : List.of("entrySet", "copy")) {
            FieldInfo fi = tree.getFieldByName(f, true);
            sb.append(f).append(" lazy=").append(LazyCacheField.isLazyCacheField(fi))
                    .append(" ignoreMod=").append(fi.isIgnoreModifications()).append('\n');
        }
        for (String m : List.of("entrySet", "copy", "clearViaView")) {
            sb.append(m).append(" nonModifying=").append(tree.findUniqueMethod(m, 0).isNonModifying()).append('\n');
        }
        sb.append("count tree=").append(Y.findUniqueMethod("count", 1).parameters().getFirst().isUnmodified())
                .append('\n');
        assertEquals("""
                entrySet lazy=true ignoreMod=false
                copy lazy=false ignoreMod=false
                entrySet nonModifying=true
                copy nonModifying=false
                clearViaView nonModifying=false
                count tree=true
                """, sb.toString());
    }
}
