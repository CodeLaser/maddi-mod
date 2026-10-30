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

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.common.util.Culprits;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class TestTypeCulprit extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            public class X {
                static final class Counter {
                    private int count;
                    void bump() { count = count + 1; }
                    int get() { return count; }
                }
                static final class Bag {
                    private final List<String> items = new ArrayList<>();
                    void add(String s) { items.add(s); }
                    int size() { return items.size(); }
                }
                static final class Holder {
                    private final List<String> items = new ArrayList<>();
                    List<String> items() { return items; }
                }
                interface Shape {
                    double area();
                }
                static final class Circle implements Shape {
                    private static int calls;
                    private final double radius;
                    Circle(double radius) { this.radius = radius; }
                    public double area() { calls++; return 3.14 * radius * radius; }
                }
                static final class Square implements Shape {
                    private final double side;
                    Square(double side) { this.side = side; }
                    public double area() { return side * side; }
                }
                static final class Box<T> {
                    private final T t;
                    Box(T t) { this.t = t; }
                    T get() { return t; }
                }
                static class Name {
                    private final String name;
                    Name(String name) { this.name = name; }
                    String name() { return name; }
                }
                static final class Point {
                    private final int x;
                    Point(int x) { this.x = x; }
                    int x() { return x; }
                }
            }
            """;

    private static String culprit(Info info, Property property) {
        var c = info.analysis().getOrNull(property, ValueImpl.CulpritImpl.class);
        return c == null ? null : c.steps().stream().map(st -> st.line() + " " + st.text())
                .collect(Collectors.joining(" <- "));
    }

    private TypeInfo analyze() {
        TypeInfo x = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepAnalyzer.doPrimaryTypes(Set.of(x));
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10)
                .setModificationViaReachability(true)
                .build()).analyze(ao);
        return x;
    }

    private static String why(TypeInfo x, String name) {
        return culprit(nested(x, name), PropertyImpl.IMMUTABLE_CULPRIT);
    }

    private static TypeInfo nested(TypeInfo x, String name) {
        return x.subTypes().stream().filter(t -> name.equals(t.simpleName())).findFirst().orElseThrow();
    }

    @DisplayName("why a type is not @Immutable: the first rule it fails, at the member that fails it")
    @Test
    public void test() {
        boolean before = Culprits.ENABLED;
        Culprits.ENABLED = true;
        try {
            TypeInfo x = analyze();
            // @Mutable: rule 0, the field and where it is assigned after construction
            assertEquals("6 field count is assigned after construction (rule 0: all fields effectively final)"
                         + " <- 7 assigned in bump(..): this.count=this.count+1", why(x, "Counter"));
            // @FinalFields: rule 1, with the guard's evidence
            assertEquals("11 field items is modified (rule 1: all fields @NotModified)"
                         + " <- 12 Method 'add' modifies field 'items' ('items.add(...)')", why(x, "Bag"));
            // @FinalFields: rule 3; and the type is @Dependent for the same reason, said without the rule
            assertEquals("16 field items is dependent: exposed to, or shared with, the outside world (rule 3)"
                         + " <- 17 Method 'items' returns field 'items': the caller can modify it", why(x, "Holder"));
            assertEquals("16 field items is dependent: exposed to, or shared with, the outside world"
                         + " <- 17 Method 'items' returns field 'items': the caller can modify it",
                    culprit(nested(x, "Holder"), PropertyImpl.DEPENDENT_CULPRIT));
            // @FinalFields on an interface: its abstract method is modifying because an implementation is
            assertEquals("20 abstract method area(..) is @Modified (abstract methods must be @NotModified)"
                         + " <- 26 because Circle.area(..), which implements it, is @Modified: modifies its type's"
                         + " own static state", why(x, "Shape"));
            assertEquals("23 field calls is assigned after construction (rule 0: all fields effectively final)"
                         + " <- 26 assigned in area(..): Circle.calls++", why(x, "Circle"));
            // @Immutable(hc=true): a type parameter, or a type that can be extended
            assertEquals("34 field t is of type parameter T: its objects are hidden content, of any type",
                    why(x, "Box"));
            assertEquals("38 Name can be extended: a subtype may add content this type cannot see", why(x, "Name"));
            // @Immutable: nothing to explain
            assertNull(why(x, "Square"));
            assertNull(why(x, "Point"));
            assertNull(culprit(nested(x, "Bag"), PropertyImpl.DEPENDENT_CULPRIT), "independent");
        } finally {
            Culprits.ENABLED = before;
        }
    }

    @DisplayName("no type culprit unless a run asks for it")
    @Test
    public void offByDefault() {
        TypeInfo x = analyze();
        assertNull(culprit(nested(x, "Counter"), PropertyImpl.IMMUTABLE_CULPRIT));
    }
}
