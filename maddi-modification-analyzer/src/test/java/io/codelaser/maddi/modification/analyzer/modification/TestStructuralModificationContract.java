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

import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
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
 * CodeLaser/maddi-mod#25: {@code @Modified(hc = true)}, "modified in the hidden content only", is the annotation form
 * of the structural verdict: deep modified, structurally unmodified. It is read as a contract on a declaration
 * without a body, carried to the arguments of a call, and printed by the decorator for computed verdicts.
 */
public class TestStructuralModificationContract extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import io.codelaser.maddi.annotation.Modified;
            import java.util.List;
            class Use {
                interface Bag {
                    void touchAll(@Modified(hc = true) List<StringBuilder> list);
                    void addAll(@Modified List<StringBuilder> list);
                    @Modified(hc = true) void shake();
                    @Modified void shuffle();
                }
                static void touch(Bag bag, List<StringBuilder> l) { bag.touchAll(l); }
                static void add(Bag bag, List<StringBuilder> l) { bag.addAll(l); }
                static void shake(Bag bag) { bag.shake(); }
                static void shuffle(Bag bag) { bag.shuffle(); }
                static void touchAll(List<StringBuilder> items) { for (StringBuilder sb : items) sb.append("x"); }
            }
            """;

    private static MethodInfo method(TypeInfo typeInfo, String name) {
        return typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private static String p(MethodInfo mi, int index) {
        ParameterInfo pi = mi.parameters().get(index);
        return pi.isUnmodified() + "/" + pi.isStructurallyUnmodified();
    }

    private static String m(MethodInfo mi) {
        return mi.isNonModifying() + "/" + mi.isStructurallyNonModifying();
    }

    @DisplayName("@Modified(hc=true) read as a contract")
    @Test
    public void contractReader() {
        TypeInfo bag = javaInspector.parse("a.b.Use", INPUT).findSubType("Bag");
        ContractReader reader = new ContractReader(runtime);
        Map<Property, Value> touchAll = reader.contracts(method(bag, "touchAll").parameters().getFirst());
        assertSame(ValueImpl.BoolImpl.FALSE, touchAll.get(PropertyImpl.UNMODIFIED_PARAMETER));
        assertSame(ValueImpl.BoolImpl.TRUE, touchAll.get(PropertyImpl.STRUCTURALLY_UNMODIFIED_PARAMETER));
        Map<Property, Value> addAll = reader.contracts(method(bag, "addAll").parameters().getFirst());
        assertSame(ValueImpl.BoolImpl.FALSE, addAll.get(PropertyImpl.UNMODIFIED_PARAMETER));
        assertNull(addAll.get(PropertyImpl.STRUCTURALLY_UNMODIFIED_PARAMETER));
        Map<Property, Value> shake = reader.contracts(method(bag, "shake"));
        assertSame(ValueImpl.BoolImpl.FALSE, shake.get(PropertyImpl.NON_MODIFYING_METHOD));
        assertSame(ValueImpl.BoolImpl.TRUE, shake.get(PropertyImpl.STRUCTURALLY_NON_MODIFYING_METHOD));
        assertNull(reader.contracts(method(bag, "shuffle")).get(PropertyImpl.STRUCTURALLY_NON_MODIFYING_METHOD));
    }

    @DisplayName("@Modified(hc=true) materialized on the bodiless declaration, carried to the caller, printed back")
    @Test
    public void analyzeAndPrint() {
        TypeInfo use = javaInspector.parse("a.b.Use", INPUT);
        TypeInfo bag = use.findSubType("Bag");
        List<Info> ao = prepWork(use);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).build()).analyze(ao);

        // the declarations: deep/structural
        assertEquals("false/true", p(method(bag, "touchAll"), 0));
        assertEquals("false/false", p(method(bag, "addAll"), 0));
        assertEquals("false/true", m(method(bag, "shake")));
        assertEquals("false/false", m(method(bag, "shuffle")));

        // the callers: the argument takes the parameter's verdict, the object the method's
        assertEquals("false/true", p(method(use, "touch"), 1));
        assertEquals("false/false", p(method(use, "add"), 1));
        assertEquals("false/true", p(method(use, "shake"), 0));
        assertEquals("false/false", p(method(use, "shuffle"), 0));
        // the computed verdict of TestStructuralModification.touchAll
        assertEquals("false/true", p(method(use, "touchAll"), 0));

        // the default decorator prints @Modified only for the "hidden content only" refinement (a positive verdict,
        // like @NotModified); the printer keeps the source annotations and adds the decorator's beside them
        @Language("java")
        String expected = """
                package a.b;
                import io.codelaser.maddi.annotation.Modified;
                import java.util.List;
                import io.codelaser.maddi.annotation.Immutable;
                import io.codelaser.maddi.annotation.Independent;
                import io.codelaser.maddi.annotation.Modified;
                import io.codelaser.maddi.annotation.NotModified;
                @Immutable(hc = true)
                @Independent
                class Use {
                    @Independent
                    interface Bag {
                        void touchAll(@Modified(hc = true) @Independent @Modified(hc = true) List<StringBuilder> list);
                        void addAll(@Modified @Independent List<StringBuilder> list);
                        @Modified(hc = true) @Modified(hc = true) void shake();
                        @Modified void shuffle();
                    }

                    @NotModified
                    static void touch(@Independent Use.Bag bag, @Independent @Modified(hc = true) List<StringBuilder> l) {
                        bag.touchAll(l);
                    }
                    @NotModified static void add(@Independent Use.Bag bag, @Independent List<StringBuilder> l) { bag.addAll(l); }
                    @NotModified static void shake(@Independent @Modified(hc = true) Use.Bag bag) { bag.shake(); }
                    @NotModified static void shuffle(@Independent Use.Bag bag) { bag.shuffle(); }
                    @NotModified
                    static void touchAll(@Independent @Modified(hc = true) List<StringBuilder> items) {
                        for (StringBuilder sb : items) { sb.append("x"); }
                    }
                }
                """;
        SourceSet sourceSetOfRequest = javaInspector.mainSources();
        assertEquals(expected, javaInspector.print2(use.compilationUnit(),
                new DecoratorImpl(runtime, sourceSetOfRequest),
                javaInspector.importComputer(4, sourceSetOfRequest)));
    }
}
