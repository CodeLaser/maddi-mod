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

package io.codelaser.maddi.modification.prepwork.io;

import io.codelaser.maddi.modification.prepwork.CommonTest;
import io.codelaser.maddi.cst.api.expression.AnnotationExpression;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code DecoratorImpl} must surface a computed/contracted {@code STATIC_SIDE_EFFECTS_METHOD} verdict as a
 * {@code @StaticSideEffects} decoration, so the global-escape signal reaches the IDE surfaces (all three
 * front-ends render {@code DecoratorImpl.annotations(...)}). See road-to-immutability §050 "Static side effects"
 * and docs/design/eventual-info-hierarchy.md.
 */
public class TestDecorateStaticSideEffects extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            public class X {
                public void reconfigure() { }
                public void plain() { }
            }
            """;

    private static AnnotationExpression named(List<AnnotationExpression> list, String simpleName) {
        return list.stream().filter(a -> simpleName.equals(a.typeInfo().simpleName())).findFirst().orElse(null);
    }

    @DisplayName("a static-side-effect verdict renders as @StaticSideEffects; a plain method carries none")
    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        MethodInfo reconfigure = X.findUniqueMethod("reconfigure", 0);
        MethodInfo plain = X.findUniqueMethod("plain", 0);

        // what StaticSideEffectAnalyzerImpl / the safe-surface contract produces
        reconfigure.analysis().set(PropertyImpl.STATIC_SIDE_EFFECTS_METHOD, ValueImpl.BoolImpl.TRUE);

        DecoratorImpl d = new DecoratorImpl(runtime, javaInspector.mainSources());

        assertNotNull(named(d.annotations(reconfigure), "StaticSideEffects"),
                "expected @StaticSideEffects on the reconfiguring method");
        assertNull(named(d.annotations(plain), "StaticSideEffects"),
                "a method with no static side effect must not carry @StaticSideEffects");
    }
}
