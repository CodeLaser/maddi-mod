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

import static io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.METHOD_LINKS;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;

/*
 CodeLaser/maddi-mod#107: state kept in an object held by a static ThreadLocal (fernflower's DecompilerContext).
 */
public class TestThreadLocalContext extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            public class X {
                static class Collector { final List<String> names = new ArrayList<>(); void add(String s) { names.add(s); } }
                static class Ctx {
                    Collector collector;
                }
                private static final ThreadLocal<Ctx> current = new ThreadLocal<>();
                private static Ctx staticCtx;

                static Ctx getCurrent() { return current.get(); }

                public static void startTL(Collector c) {
                    Ctx ctx = getCurrent();
                    ctx.collector = c;
                }
                public static Collector getTL() { return getCurrent().collector; }

                public static void startStatic(Collector c) {
                    Ctx ctx = staticCtx;
                    ctx.collector = c;
                }
                public static Collector getStatic() { return staticCtx.collector; }

                public static void startDirect(Collector c) {
                    current.get().collector = c;
                }
                public static void startStaticDirect(Collector c) {
                    staticCtx.collector = c;
                }
                public static void startStaticLocalVoid(Collector c) {
                    Ctx ctx = staticCtx;
                    ctx.collector = c;
                    System.out.println(ctx);
                }
                public static void startTLTwo(Collector c) {
                    Ctx ctx = getCurrent();
                    ctx.collector = c;
                    ctx.collector.add("x");
                }
                private Ctx own;
                private final ThreadLocal<Ctx> itl = new ThreadLocal<>();
                private final List<Ctx> ilist = new ArrayList<>();
                public Ctx instTL() { return itl.get(); }
                public Collector instTLField() { return itl.get().collector; }
                public Ctx instList() { return ilist.get(0); }
                public Collector instListField() { return ilist.get(0).collector; }
                public void instTLSet(Collector c) { itl.get().collector = c; }
                public void startInstance(Collector c) {
                    Ctx ctx = own;
                    ctx.collector = c;
                }
                public static void startParam(Ctx ctx, Collector c) {
                    Ctx local = ctx;
                    local.collector = c;
                }
            }
            """;

    private static MethodInfo method(TypeInfo x, String name) {
        return x.methods().stream().filter(mi -> mi.name().equals(name)).findFirst().orElseThrow();
    }

    private static String method(MethodInfo m) {
        return String.valueOf(m.analysis().getOrNull(PropertyImpl.INDEPENDENT_METHOD, ValueImpl.IndependentImpl.class));
    }

    private static String parameter(MethodInfo m) {
        return String.valueOf(m.parameters().getLast().analysis().getOrNull(PropertyImpl.INDEPENDENT_PARAMETER,
                ValueImpl.IndependentImpl.class));
    }

    @DisplayName("the setters' parameters and the getters' results are dependent on the state behind the ThreadLocal")
    @Test
    public void test() {
        TypeInfo x = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepAnalyzer.doPrimaryTypes(Set.of(x));
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(true).build()).analyze(ao);

        // a store through a local aliasing a static, an instance field, or the content of a static ThreadLocal
        assertEquals("[0:c→X.staticCtx*.collector] --> -",
                String.valueOf(method(x, "startStatic").analysis().getOrNull(METHOD_LINKS, MethodLinkedVariablesImpl.class)));
        assertEquals("[0:c→this.own*.collector] --> -",
                String.valueOf(method(x, "startInstance").analysis().getOrNull(METHOD_LINKS, MethodLinkedVariablesImpl.class)));
        assertEquals("[0:c→X.current.§$.collector] --> -",
                String.valueOf(method(x, "startTL").analysis().getOrNull(METHOD_LINKS, MethodLinkedVariablesImpl.class)));
        for (String setter : List.of("startTL", "startStatic", "startDirect", "startStaticDirect",
                "startStaticLocalVoid", "startInstance", "instTLSet")) {
            assertEquals("@Dependent", parameter(method(x, setter)), setter);
        }
        // stored into a field that is modified elsewhere: modified, however the field is reached (#108)
        for (String setter : List.of("startTL", "startStatic", "startDirect")) {
            assertEquals("false", String.valueOf(method(x, setter).parameters().getLast().analysis()
                    .getOrNull(PropertyImpl.UNMODIFIED_PARAMETER, ValueImpl.BoolImpl.class)), setter);
        }
        // a parameter's field is not the type's state
        assertEquals("@Independent", parameter(method(x, "startParam")));
        for (String getter : List.of("getCurrent", "getTL", "getStatic", "instTL", "instTLField", "instList",
                "instListField")) {
            assertEquals("@Dependent", method(method(x, getter)), getter);
        }
    }
}
