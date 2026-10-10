/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2025, Bart Naudts, https://github.com/CodeLaser/maddi
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details. You should have received a copy of the GNU Lesser General Public
 * License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.codelaser.maddi.modification.analyzer.modification;

import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CodeLaser/maddi-mod#25: the structural (shallow) modification properties beside the deep ones. A collection is
 * structurally modified when the collection object itself is: a mutator called on it, an alias of it, an iterator's
 * {@code remove()}, a parameter that is structurally modified. Modifying its elements -- a for-each that mutates each
 * element, {@code iterator().next()} handed to a mutating method, a map value mutated -- is a deep modification
 * only. The Java-to-Kotlin printer reads the structural verdict to choose a read-only {@code List}/{@code Map}: its
 * elements are what they are, and Kotlin lets them be mutated through a read-only {@code List}.
 * <p>
 * Each verdict is written {@code deep/structural}, by the fixpoint and by the reachability pass.
 * <ul>
 * <li>{@code useReader}: an argument handed to the SAM of one of the method's own functional parameters is
 *     "modified for some function" ({@code MODIFIED_THROUGH_PASSED_FUNCTION}), whatever the SAM's own verdict; the
 *     structural twin follows the deep one's pessimism.</li>
 * <li>The reachability pass follows no element link for a parameter (its E1/E2 projections walk assignment
 *     links only), so its DEEP verdict never saw an element-only modification of a parameter in a walkable body:
 *     the two columns agree there, before and after #25. A field keeps its deep evidence through the
 *     statement-level seeds ({@code Holder.touched}), and the structural twin classifies that seed.</li>
 * </ul>
 */
public class TestStructuralModification extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.Collection;
            import java.util.Collections;
            import java.util.Iterator;
            import java.util.List;
            import java.util.Map;
            class S {
                static class Item {
                    int n;
                    void touch() { n++; }
                }
                static void touch(Item i) { i.touch(); }
                // the elements are modified, the collection is not
                static void touchAll(List<Item> items) { for (Item i : items) i.touch(); }
                static void touchFirst(Collection<Item> items) { Item first = items.iterator().next(); touch(first); }
                static void touchValue(Map<String, Item> m, String k) { m.get(k).touch(); }
                // the collection itself
                static void add(List<Item> items, Item i) { items.add(i); }
                static void addAlias(List<Item> items, Item i) { List<Item> l = items; l.add(i); }
                static void sort(List<String> l) { Collections.sort(l); }
                static void dropFirst(List<Item> items) { Iterator<Item> it = items.iterator(); it.next(); it.remove(); }
                static Item peek(List<Item> items) { Iterator<Item> it = items.iterator(); return it.next(); }
                // through a callee's parameter: the callee's verdict is the argument's
                static void addVia(List<Item> items, Item i) { add(items, i); }
                static void touchVia(List<Item> items) { touchAll(items); }
                // through a functional interface
                static void addLambda(List<Item> items, List<Item> target) { items.forEach(target::add); }
                // fields
                static class Holder {
                    private final List<Item> items = new ArrayList<>();
                    private final List<Item> touched = new ArrayList<>();
                    void add(Item i) { items.add(i); }
                    void touchAll() { for (Item i : touched) i.touch(); }
                    int size() { return items.size() + touched.size(); }
                }
                // the abstract method's verdict is the union over its implementations
                interface Sink { void accept(List<Item> items); }
                static class Toucher implements Sink { public void accept(List<Item> items) { touchAll(items); } }
                static class Adder implements Sink { public void accept(List<Item> items) { items.add(new Item()); } }
                interface Reader { void read(List<Item> items); }
                static class R1 implements Reader { public void read(List<Item> items) { touchAll(items); } }
                static class R2 implements Reader { public void read(List<Item> items) { for (Item i : items) i.touch(); } }
                static void useSink(Sink sink, List<Item> items) { sink.accept(items); }
                static void useReader(Reader reader, List<Item> items) { reader.read(items); }
            }
            """;

    private static MethodInfo method(TypeInfo typeInfo, String name) {
        return typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private static String p(MethodInfo mi, int index) {
        ParameterInfo pi = mi.parameters().get(index);
        return pi.isUnmodified() + "/" + pi.isStructurallyUnmodified();
    }

    private static String f(FieldInfo fi) {
        return fi.isUnmodified() + "/" + fi.isStructurallyUnmodified();
    }

    private static String m(MethodInfo mi) {
        return mi.isNonModifying() + "/" + mi.isStructurallyNonModifying();
    }

    private String verdicts(boolean modReach) {
        TypeInfo S = javaInspector.parse("a.b.S", INPUT);
        List<Info> ao = prepWork(S);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10)
                .setModificationViaReachability(modReach)
                .build()).analyze(ao);
        TypeInfo holder = S.findSubType("Holder");
        return "touchAll=" + p(method(S, "touchAll"), 0)
               + " touchFirst=" + p(method(S, "touchFirst"), 0)
               + " touchValue=" + p(method(S, "touchValue"), 0)
               + " add=" + p(method(S, "add"), 0)
               + " addAlias=" + p(method(S, "addAlias"), 0)
               + " sort=" + p(method(S, "sort"), 0)
               + " dropFirst=" + p(method(S, "dropFirst"), 0)
               + " peek=" + p(method(S, "peek"), 0)
               + " addVia=" + p(method(S, "addVia"), 0)
               + " touchVia=" + p(method(S, "touchVia"), 0)
               + " addLambda=" + p(method(S, "addLambda"), 0) + "," + p(method(S, "addLambda"), 1)
               + " Holder.items=" + f(holder.getFieldByName("items", true))
               + " Holder.touched=" + f(holder.getFieldByName("touched", true))
               + " Holder.add=" + m(method(holder, "add"))
               + " Holder.touchAll=" + m(method(holder, "touchAll"))
               + " Sink.accept=" + p(method(S.findSubType("Sink"), "accept"), 0)
               + " Reader.read=" + p(method(S.findSubType("Reader"), "read"), 0)
               + " useSink=" + p(method(S, "useSink"), 1)
               + " useReader=" + p(method(S, "useReader"), 1);
    }

    private static final String EXPECTED_FIXPOINT = "touchAll=false/true touchFirst=false/true touchValue=false/true"
                                                    + " add=false/false addAlias=false/false sort=false/false dropFirst=false/false peek=true/true"
                                                    + " addVia=false/false touchVia=false/true"
                                                    + " addLambda=true/true,false/false"
                                                    + " Holder.items=false/false Holder.touched=false/true"
                                                    + " Holder.add=false/false Holder.touchAll=false/true"
                                                    + " Sink.accept=false/false Reader.read=false/true"
                                                    + " useSink=false/false useReader=false/false";

    // the deep column differs from the fixpoint's exactly where a parameter is modified through its elements
    private static final String EXPECTED_REACHABILITY = "touchAll=true/true touchFirst=true/true touchValue=true/true"
                                                        + " add=false/false addAlias=false/false sort=false/false dropFirst=false/false peek=true/true"
                                                        + " addVia=false/false touchVia=true/true"
                                                        + " addLambda=true/true,false/false"
                                                        + " Holder.items=false/false Holder.touched=false/true"
                                                        + " Holder.add=false/false Holder.touchAll=true/true"
                                                        + " Sink.accept=false/false Reader.read=true/true"
                                                        + " useSink=false/false useReader=true/true";

    @DisplayName("structural beside deep: fixpoint")
    @Test
    public void fixpoint() {
        assertEquals(EXPECTED_FIXPOINT, verdicts(false));
    }

    @DisplayName("structural beside deep: reachability")
    @Test
    public void reachability() {
        assertEquals(EXPECTED_REACHABILITY, verdicts(true));
    }
}
