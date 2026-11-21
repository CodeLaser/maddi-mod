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
 * The members of an annotation interface are its attribute accessors: they have no implementation in the sources, and
 * cannot modify anything. Left undecided, the reachability pass took them as "undecided abstract callees", hence
 * modifying, and every method reading an annotation attribute became modifying in turn (langchain4j's
 * {@code ToolSpecifications.getName(Tool, Method)} through {@code Tool.name()}).
 */
public class TestAnnotationMembers extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.lang.annotation.*;
            class X {
                @Retention(RetentionPolicy.RUNTIME)
                @interface P {
                    String value();
                    String description() default "";
                    String[] names() default {};
                }
                static String name(P p) { return p.value() + p.description() + p.names().length; }
                static String read(java.lang.reflect.Method m) {
                    P p = m.getAnnotation(P.class);
                    return p == null ? "" : p.value();
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
        TypeInfo P = X.findSubType("P");
        StringBuilder sb = new StringBuilder();
        for (MethodInfo m : P.methods()) {
            sb.append("P.").append(m.name()).append(" nonModifying=").append(m.isNonModifying()).append('\n');
        }
        for (String name : List.of("name", "read")) {
            MethodInfo m = X.methods().stream().filter(mi -> mi.name().equals(name)).findFirst().orElseThrow();
            sb.append(name);
            m.parameters().forEach(p -> sb.append(' ').append(p.name()).append('=').append(p.isUnmodified()));
            sb.append('\n');
        }
        assertEquals("""
                P.value nonModifying=true
                P.description nonModifying=true
                P.names nonModifying=true
                name p=true
                read m=true
                """, sb.toString());
    }
}
