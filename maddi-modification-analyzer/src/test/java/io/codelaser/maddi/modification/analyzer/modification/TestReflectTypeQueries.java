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

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * The getters of {@code GenericArrayType}, {@code WildcardType} and {@code TypeVariable} are queries, as those of
 * {@code ParameterizedType}: a method that only inspects a {@code Type} does not modify it (CodeLaser/maddi#207,
 * seen on gson's {@code GsonTypes}).
 */
public class TestReflectTypeQueries extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.lang.reflect.*;
            class X {
                static Type component(GenericArrayType g) { return g.getGenericComponentType(); }
                static int bounds(WildcardType w) { return w.getUpperBounds().length + w.getLowerBounds().length; }
                static String variable(TypeVariable<?> v) {
                    return v.getName() + v.getBounds().length + v.getGenericDeclaration() + v.getAnnotatedBounds().length;
                }
                static boolean equals(Type a, Type b) {
                    if (a instanceof ParameterizedType pa && b instanceof ParameterizedType pb) {
                        return pa.getRawType().equals(pb.getRawType());
                    } else if (a instanceof GenericArrayType ga && b instanceof GenericArrayType gb) {
                        return equals(ga.getGenericComponentType(), gb.getGenericComponentType());
                    } else if (a instanceof WildcardType wa && b instanceof WildcardType wb) {
                        return wa.getUpperBounds().length == wb.getUpperBounds().length
                               && wa.getLowerBounds().length == wb.getLowerBounds().length;
                    } else if (a instanceof TypeVariable<?> va && b instanceof TypeVariable<?> vb) {
                        return va.getGenericDeclaration() == vb.getGenericDeclaration() && va.getName().equals(vb.getName());
                    }
                    return false;
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
        for (String name : List.of("component", "bounds", "variable", "equals")) {
            MethodInfo m = X.methods().stream().filter(mi -> mi.name().equals(name)).findFirst().orElseThrow();
            sb.append(name);
            m.parameters().forEach(p -> sb.append(' ').append(p.name()).append('=').append(p.isUnmodified()));
            sb.append('\n');
        }
        assertEquals("""
                component g=true
                bounds w=true
                variable v=true
                equals a=true b=true
                """, sb.toString());
    }
}
