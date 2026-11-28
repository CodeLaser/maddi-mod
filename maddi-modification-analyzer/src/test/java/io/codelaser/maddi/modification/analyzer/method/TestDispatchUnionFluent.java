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

package io.codelaser.maddi.modification.analyzer.method;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.FLUENT_METHOD;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.IDENTITY_METHOD;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * CodeLaser/maddi-mod#31, the dispatch union of #116 and #167 for @Fluent and @Identity: a concrete, overridable method
 * is fluent (identity) only when every source override is, since a call through it may reach any of them, and a call
 * site aliases the result to the receiver (the first argument).
 * <p>
 * {@code solve}: the base (a default method) returns {@code this}, an override sometimes another object.
 * {@code id}: the base returns its argument, an override a new object. {@code self} and {@code same} are the
 * controls: every override agrees. {@code setName} is javaparser's {@code CallableDeclaration.setName}, overridden by
 * {@code return super.setName(name)}: the super call reaches the base's own body, or the two lock each other at false.
 */
public class TestDispatchUnionFluent extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            public class X {
                static class C { boolean flag; }
                interface RT {
                    default RT solve(C c) { return this; }
                    default RT self(C c) { return this; }
                }
                static class W implements RT {
                    private final RT other;
                    W(RT other) { this.other = other; }
                    @Override public RT solve(C c) { if (c.flag) return other; return this; }
                    @Override public RT self(C c) { return this; }
                }
                static class Base {
                    Object id(Object o) { return o; }
                    Object same(Object o) { return o; }
                }
                static class Sub extends Base {
                    @Override Object id(Object o) { return new Object(); }
                    @Override Object same(Object o) { return o; }
                }
                @SuppressWarnings("unchecked")
                static abstract class Cd<T extends Cd<?>> {
                    String name;
                    T setName(String n) { if (n == name) return (T) this; name = n; return (T) this; }
                }
                static class Md extends Cd<Md> {
                    @Override Md setName(String n) { return super.setName(n); }
                }
            }
            """;

    @DisplayName("an override that disagrees makes the overridden concrete method non-fluent, non-identity")
    @Test
    public void test() {
        TypeInfo x = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(x);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).build()).analyze(ao);
        TypeInfo rt = x.findSubType("RT");
        TypeInfo w = x.findSubType("W");
        TypeInfo base = x.findSubType("Base");
        TypeInfo sub = x.findSubType("Sub");
        TypeInfo cd = x.findSubType("Cd");
        TypeInfo md = x.findSubType("Md");
        assertEquals("solve=false self=true W.solve=false W.self=true | id=false same=true Sub.id=false Sub.same=true"
                     + " | setName=true Md.setName=true",
                "solve=" + b(rt.findUniqueMethod("solve", 1), FLUENT_METHOD)
                + " self=" + b(rt.findUniqueMethod("self", 1), FLUENT_METHOD)
                + " W.solve=" + b(w.findUniqueMethod("solve", 1), FLUENT_METHOD)
                + " W.self=" + b(w.findUniqueMethod("self", 1), FLUENT_METHOD)
                + " | id=" + b(base.findUniqueMethod("id", 1), IDENTITY_METHOD)
                + " same=" + b(base.findUniqueMethod("same", 1), IDENTITY_METHOD)
                + " Sub.id=" + b(sub.findUniqueMethod("id", 1), IDENTITY_METHOD)
                + " Sub.same=" + b(sub.findUniqueMethod("same", 1), IDENTITY_METHOD)
                + " | setName=" + b(cd.findUniqueMethod("setName", 1), FLUENT_METHOD)
                + " Md.setName=" + b(md.findUniqueMethod("setName", 1), FLUENT_METHOD));
    }

    private static Object b(MethodInfo m, Property property) {
        ValueImpl.BoolImpl v = m.analysis().getOrNull(property, ValueImpl.BoolImpl.class);
        return v == null ? "null" : v.isTrue();
    }
}
