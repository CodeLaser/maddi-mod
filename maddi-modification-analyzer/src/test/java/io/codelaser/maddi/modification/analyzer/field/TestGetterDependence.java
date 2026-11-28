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

package io.codelaser.maddi.modification.analyzer.field;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.INDEPENDENT_METHOD;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * A call on a field of the caller's own type: {@code first.self()} in {@code S}, the object {@code this.first}. The
 * callee's {@code this} was translated to the object twice (ExpressionVisitor, then LinkMethodCall's hierarchy map),
 * and the second pass rewrote the caller's {@code this} inside the object: {@code viaSelf←this.first.first}, which
 * the summary then dropped, and the getter came out independent. fernflower's tree and graph getters
 * ({@code Statement.getBasichead}, the dynamic check's 2c). Gate NOSINGLETHIS.
 * <p>
 * {@code self} and {@code maybeSelf} return this: independent by definition. {@code viaSelf} and {@code viaMaybe}
 * return the receiver's field through them, on the same type; {@code U.via} is the control on another type, where the
 * second pass found nothing to rewrite.
 */
public class TestGetterDependence extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class X {
                static class S {
                    int type;
                    S first;
                    void setType(int t) { type = t; }
                    S self() { return this; }
                    S maybeSelf() { if (type == 0) return this; return null; }
                    S viaSelf() { return first.self(); }
                    S viaMaybe() { return first.maybeSelf(); }
                    S getFirst() { return first; }
                }
                static class U {
                    S s;
                    S via() { return s.self(); }
                }
            }
            """;

    @DisplayName("a getter returning a field of its own type through a call is dependent")
    @Test
    public void test() {
        TypeInfo x = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(x);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).build()).analyze(ao);
        TypeInfo s = x.findSubType("S");
        TypeInfo u = x.findSubType("U");
        assertEquals("self=@Independent maybeSelf=@Independent viaSelf=@Dependent viaMaybe=@Dependent"
                     + " getFirst=@Dependent U.via=@Dependent",
                "self=" + ind(s.findUniqueMethod("self", 0))
                + " maybeSelf=" + ind(s.findUniqueMethod("maybeSelf", 0))
                + " viaSelf=" + ind(s.findUniqueMethod("viaSelf", 0))
                + " viaMaybe=" + ind(s.findUniqueMethod("viaMaybe", 0))
                + " getFirst=" + ind(s.findUniqueMethod("getFirst", 0))
                + " U.via=" + ind(u.findUniqueMethod("via", 0)));
    }

    private static Object ind(MethodInfo m) {
        return m.analysis().getOrNull(INDEPENDENT_METHOD, ValueImpl.IndependentImpl.class);
    }
}
