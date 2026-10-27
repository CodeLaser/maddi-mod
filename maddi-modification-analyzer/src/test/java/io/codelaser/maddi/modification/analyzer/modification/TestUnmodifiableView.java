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

import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.common.defaults.ContractReader;
import io.codelaser.maddi.modification.prepwork.io.DecoratorImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CodeLaser/maddi-mod#52: nothing is added to or removed from this object through this reference. The type-level
 * property is rule 0 and the structural rule 1 before the independence cap; the method-level one is a contract on a
 * bodiless declaration.
 */
public class TestUnmodifiableView extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import io.codelaser.maddi.annotation.UnmodifiableView;
            import java.util.ArrayList;
            import java.util.List;
            class Views {
                // a view: a final field, only reads; the constructor stores what it is given, so the type is dependent
                // and immutability stops at FINAL_FIELDS -- the verdict this property looks past
                static class View {
                    private final List<String> list;
                    View(List<String> list) { this.list = list; }
                    String get(int i) { return list.get(i); }
                    int size() { return list.size(); }
                }
                static class Bag {
                    private final List<String> list = new ArrayList<>();
                    void add(String s) { list.add(s); }
                    int size() { return list.size(); }
                }
                static class Counter {
                    private int n;
                    void inc() { n++; }
                    int n() { return n; }
                }
                interface Source {
                    @UnmodifiableView List<String> view();
                    List<String> plain();
                }
                // computed from the returned values
                static class Wraps {
                    private final List<String> list = new ArrayList<>();
                    List<String> view() { return java.util.Collections.unmodifiableList(list); }
                    List<String> copy() { return new ArrayList<>(list); }
                    List<String> both(boolean b) { return b ? java.util.Collections.unmodifiableList(list) : null; }
                    View asView() { return new View(list); }
                    List<String> chained() { return view(); }
                }
                // a bodiless declaration with implementations: the fold
                interface Src2 { List<String> get(); }
                static class Impl implements Src2 {
                    private final List<String> l = new ArrayList<>();
                    public List<String> get() { return java.util.Collections.unmodifiableList(l); }
                }
            }
            """;

    private static MethodInfo method(TypeInfo typeInfo, String name) {
        return typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private static String unmodifiableView(MethodInfo methodInfo) {
        Value.Bool b = methodInfo.analysis().getOrNull(PropertyImpl.UNMODIFIABLE_VIEW_METHOD, ValueImpl.BoolImpl.class);
        return b == null ? "undecided" : String.valueOf(b.isTrue());
    }

    private static Value.Bool unmodifiable(TypeInfo typeInfo) {
        return typeInfo.analysis().getOrNull(PropertyImpl.UNMODIFIABLE_TYPE, ValueImpl.BoolImpl.class);
    }

    @DisplayName("@UnmodifiableView read as a contract")
    @Test
    public void contractReader() {
        TypeInfo source = javaInspector.parse("a.b.Views", INPUT).findSubType("Source");
        ContractReader reader = new ContractReader(runtime);
        Map<Property, Value> view = reader.contracts(method(source, "view"));
        assertSame(ValueImpl.BoolImpl.TRUE, view.get(PropertyImpl.UNMODIFIABLE_VIEW_METHOD));
        assertNull(view.get(PropertyImpl.IMMUTABLE_METHOD)); // a view is not a copy
        assertNull(reader.contracts(method(source, "plain")).get(PropertyImpl.UNMODIFIABLE_VIEW_METHOD));
    }

    @DisplayName("rule 0 and the structural rule 1 decide the type; the contract is materialized and printed")
    @Test
    public void analyzeAndPrint() {
        TypeInfo views = javaInspector.parse("a.b.Views", INPUT);
        List<Info> ao = prepWork(views);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).build()).analyze(ao);

        TypeInfo view = views.findSubType("View");
        assertSame(ValueImpl.BoolImpl.TRUE, unmodifiable(view));
        Value.Immutable viewImmutable = view.analysis().getOrDefault(PropertyImpl.IMMUTABLE_TYPE,
                ValueImpl.ImmutableImpl.MUTABLE);
        assertTrue(viewImmutable.isFinalFields(), "a dependent constructor caps immutability: " + viewImmutable);

        assertSame(ValueImpl.BoolImpl.FALSE, unmodifiable(views.findSubType("Bag")));     // rule 1
        assertSame(ValueImpl.BoolImpl.FALSE, unmodifiable(views.findSubType("Counter"))); // rule 0

        TypeInfo source = views.findSubType("Source");
        MethodInfo viewMethod = method(source, "view");
        assertSame(ValueImpl.BoolImpl.TRUE, viewMethod.analysis().getOrNull(PropertyImpl.UNMODIFIABLE_VIEW_METHOD,
                ValueImpl.BoolImpl.class));
        MethodInfo plainMethod = method(source, "plain");
        assertNull(plainMethod.analysis().getOrNull(PropertyImpl.UNMODIFIABLE_VIEW_METHOD, ValueImpl.BoolImpl.class));
        // the contract says nothing about immutability: both bodiless methods get the same verdict there (the
        // abstract-method fold's, IMMUTABLE for a declaration without implementations)
        assertSame(plainMethod.analysis().getOrDefault(PropertyImpl.IMMUTABLE_METHOD, ValueImpl.ImmutableImpl.MUTABLE),
                viewMethod.analysis().getOrDefault(PropertyImpl.IMMUTABLE_METHOD, ValueImpl.ImmutableImpl.MUTABLE));

        TypeInfo wraps = views.findSubType("Wraps");
        assertEquals("true/false/true/true/true", String.join("/",
                unmodifiableView(method(wraps, "view")), unmodifiableView(method(wraps, "copy")),
                unmodifiableView(method(wraps, "both")), unmodifiableView(method(wraps, "asView")),
                unmodifiableView(method(wraps, "chained"))));
        assertEquals("true", unmodifiableView(method(views.findSubType("Impl"), "get")));
        assertEquals("true", unmodifiableView(method(views.findSubType("Src2"), "get")));

        SourceSet sourceSetOfRequest = javaInspector.mainSources();
        String printed = javaInspector.print2(views.compilationUnit(),
                new DecoratorImpl(runtime, sourceSetOfRequest),
                javaInspector.importComputer(4, sourceSetOfRequest));
        // the source annotation, and the decorator's beside it (the printer keeps the source annotations and adds
        // the decorator's, as TestStructuralModificationContract shows); plain() gets none
        assertTrue(printed.contains("@UnmodifiableView @UnmodifiableView "), printed);
        String plainLine = printed.lines().filter(l -> l.contains("plain()")).findFirst().orElseThrow();
        assertFalse(plainLine.contains("@UnmodifiableView"), plainLine);
    }
}
