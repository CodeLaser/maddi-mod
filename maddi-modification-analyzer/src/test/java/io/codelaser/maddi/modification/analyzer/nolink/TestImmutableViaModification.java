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

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.IMMUTABLE_TYPE;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent @Immutable tests seen through the modification lens (plain classes rather than records). A type with
 * effectively-final fields of immutable type and no modifying methods is immutable; introducing a single field setter
 * makes it mutable (road-to-immutability, "Immutability"). Asserts the computed IMMUTABLE_TYPE property.
 */
public class TestImmutableViaModification extends CommonTest {

    private static Value.Immutable immutable(TypeInfo typeInfo) {
        return typeInfo.analysis().getOrDefault(IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.MUTABLE);
    }

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            class X {
                // final class, final fields of immutable type, no modification, returns fresh copies -> deeply
                // immutable (a non-final class would only be immutable-with-hidden-content, as a subtype could add
                // hidden state)
                static final class Point {
                    private final int x;
                    private final int y;
                    Point(int x, int y) { this.x = x; this.y = y; }
                    int x() { return x; }
                    int y() { return y; }
                    Point translate(int dx, int dy) { return new Point(x + dx, y + dy); }
                }
                // same shape but with a setter that mutates a field -> mutable
                static class MutablePoint {
                    private int x;
                    private int y;
                    MutablePoint(int x, int y) { this.x = x; this.y = y; }
                    int x() { return x; }
                    void setX(int x) { this.x = x; }
                }
            }
            """;

    @DisplayName("no modification + final immutable fields is immutable; a single setter makes it mutable")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT1);
        List<Info> ao = prepWork(X);
        analyzer.go(ao);

        TypeInfo point = X.findSubType("Point");
        assertTrue(point.findUniqueMethod("translate", 2).isNonModifying());
        Value.Immutable immutablePoint = immutable(point);
        assertTrue(immutablePoint.isImmutable(), "Have " + immutablePoint);

        TypeInfo mutablePoint = X.findSubType("MutablePoint");
        assertTrue(mutablePoint.findUniqueMethod("setX", 1).isModifying());
        assertTrue(immutable(mutablePoint).isMutable(), "a setter makes the type mutable");
    }
}
