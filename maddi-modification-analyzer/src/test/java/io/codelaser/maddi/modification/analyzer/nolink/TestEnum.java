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

package io.codelaser.maddi.modification.analyzer.nolink;

import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.CONTAINER_TYPE;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.IMMUTABLE_TYPE;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent enum tests. An enum with only immutable (or no) instance fields and no modifying methods is immutable and
 * a container. Asserts the computed IMMUTABLE_TYPE / CONTAINER_TYPE properties.
 */
public class TestEnum extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            class X {
                enum Color { RED, GREEN, BLUE }
                enum Planet {
                    EARTH(9.8), MARS(3.7);
                    private final double gravity;
                    Planet(double g) { this.gravity = g; }
                    double gravity() { return gravity; }
                }
            }
            """;

    @DisplayName("a plain enum and an enum with a final immutable field are immutable containers")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT1);
        List<Info> ao = prepWork(X);
        analyzer.go(ao);

        TypeInfo color = X.findSubType("Color");
        assertTrue(immutable(color).isAtLeastImmutableHC(), "have " + immutable(color));
        assertTrue(isContainer(color));

        TypeInfo planet = X.findSubType("Planet");
        assertTrue(immutable(planet).isAtLeastImmutableHC(), "final double field, no modification, have " + immutable(planet));
        assertTrue(isContainer(planet));
    }

    private static Value.Immutable immutable(TypeInfo typeInfo) {
        return typeInfo.analysis().getOrDefault(IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.MUTABLE);
    }

    private static boolean isContainer(TypeInfo typeInfo) {
        return typeInfo.analysis().getOrDefault(CONTAINER_TYPE, ValueImpl.BoolImpl.FALSE).isTrue();
    }
}
