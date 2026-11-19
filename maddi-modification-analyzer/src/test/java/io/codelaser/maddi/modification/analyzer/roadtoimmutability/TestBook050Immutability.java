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

package io.codelaser.maddi.modification.analyzer.roadtoimmutability;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

/**
 * The examples of road-to-immutability §050, "Immutability: definition, examples, abstract methods".
 */
public class TestBook050Immutability extends BookTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import io.codelaser.maddi.annotation.Independent;
            import io.codelaser.maddi.annotation.NotModified;
            import java.util.*;
            import java.util.function.Consumer;
            import java.util.stream.Stream;
            public class X {
                public static class Pair<K, V> {
                    public final K k;
                    public final V v;
                    public Pair(K k, V v) {
                        this.k = k;
                        this.v = v;
                    }
                }
                static class Point {
                    private double x;
                    private double y;
                    Point(double x, double y) { this.x = x; this.y = y; }
                    public double getX() { return x; }
                    public void setX(double x) { this.x = x; }
                    public double getY() { return y; }
                    public void setY(double y) { this.y = y; }
                }
                static class Line {
                    private Point point1;
                    private Point point2;
                    public Line(Point point1, Point point2) {
                        this.point1 = point1;
                        this.point2 = point2;
                    }
                    public Point middle() {
                        return new Point((point1.getX() + point2.getX()) / 2.0, (point1.getY() + point2.getY()) / 2.0);
                    }
                    public void translateHorizontally(double x) {
                        point1.setX(point1.getX() + x);
                        point2.setX(point2.getX() + x);
                    }
                }
                static class ArrayContainer1<T> {
                    private final T[] data;
                    public ArrayContainer1(T[] ts) { this.data = ts; }
                    public Stream<T> stream() { return Arrays.stream(data); }
                }
                static class ArrayContainer2<T> {
                    public final T[] data;
                    public ArrayContainer2(T[] ts) { this.data = Arrays.copyOf(ts, ts.length); }
                    public Stream<T> stream() { return Arrays.stream(data); }
                }
                static class ArrayContainer3<T> {
                    private final T[] data;
                    public ArrayContainer3(T[] ts) { this.data = Arrays.copyOf(ts, ts.length); }
                    public Stream<T> stream() { return Arrays.stream(data); }
                }
                // not in the book: the same type, copying with System.arraycopy, and with clone()
                static class ArrayContainer3b<T> {
                    private final T[] data;
                    @SuppressWarnings("unchecked")
                    public ArrayContainer3b(T[] ts) {
                        this.data = (T[]) new Object[ts.length];
                        System.arraycopy(ts, 0, data, 0, ts.length);
                    }
                    public Stream<T> stream() { return Arrays.stream(data); }
                }
                static class ArrayContainer3c<T> {
                    private final T[] data;
                    public ArrayContainer3c(T[] ts) { this.data = ts.clone(); }
                    public Stream<T> stream() { return Arrays.stream(data); }
                }
                static class SetBasedContainer1<T> {
                    private final Set<T> data;
                    public SetBasedContainer1(Set<T> ts) { this.data = ts; }
                    public Stream<T> stream() { return data.stream(); }
                }
                static class SetBasedContainer2<T> {
                    public final Set<T> data;
                    public SetBasedContainer2(Set<T> ts) { this.data = new HashSet<>(ts); }
                    public Stream<T> stream() { return data.stream(); }
                }
                static class SetBasedContainer3<T> {
                    private final Set<T> data;
                    public SetBasedContainer3(Set<T> ts) { this.data = new HashSet<>(ts); }
                    public Stream<T> stream() { return data.stream(); }
                }
                static class SetBasedContainer4<T> {
                    public final Set<T> data;
                    public SetBasedContainer4(Set<T> ts) { this.data = Set.copyOf(ts); }
                    public Stream<T> stream() { return data.stream(); }
                }
                static class SetBasedContainer5<T> {
                    private final Set<T> data;
                    public SetBasedContainer5(Set<T> ts) { this.data = new HashSet<>(ts); }
                    public Set<T> getSet() { return data; }
                }
                static class SetBasedContainer6<T> {
                    public final Set<T> set = new HashSet<>();
                    public void add(T t) { set.add(t); }
                    public Stream<T> stream() { return set.stream(); }
                }
                public static class OddPair {
                    private final Set<String> set;
                    private final StringBuilder sb;
                    public OddPair(Set<String> set, StringBuilder sb) {
                        this.set = set;
                        this.sb = sb;
                    }
                    public Set<String> getSet() { return set; }
                    public StringBuilder getSb() { return sb; }
                }
                interface Marker { }
                static class EmptyClass { }
                static class ImplementsMarker implements Marker { }
                static class ExtendsEmptyClass extends ImplementsMarker { }

                interface HasSize {
                    @NotModified
                    int size();
                    default boolean isEmpty() { return size() == 0; }
                }
                interface NonEmptyImmutableList<T> extends HasSize {
                    @NotModified
                    @Independent(hc = true)
                    T first();
                    @NotModified
                    void visit(@Independent(hc = true) Consumer<T> consumer);
                    @Override
                    default boolean isEmpty() { return false; }
                }
                interface NonEmptyList<T> extends NonEmptyImmutableList<T> {
                    void setFirst(@Independent(hc = true) T t);
                }
                static class One<T> implements NonEmptyList<T> {
                    private T t;
                    @Override
                    public T first() { return t; }
                    @Override
                    public void setFirst(T t) { this.t = t; }
                    @Override
                    public int size() { return 1; }
                    @Override
                    public void visit(Consumer<T> consumer) { consumer.accept(t); }
                }
                static class OneWithOne<T> implements NonEmptyList<T> {
                    private final One<T> one = new One<>();
                    @Override
                    public T first() { return one.first(); }
                    @Override
                    public void setFirst(T t) { one.setFirst(t); }
                    @Override
                    public int size() { return 1; }
                    @Override
                    public void visit(Consumer<T> consumer) { consumer.accept(first()); }
                }
                static class ReceiveStrings implements Consumer<String> {
                    public final List<String> list = new ArrayList<>();
                    @Override
                    public void accept(String string) { list.add(string); }
                }
            }
            """;

    @DisplayName("§050, Immutability: definition, examples, abstract methods")
    @Test
    public void test() throws IOException {
        Map<String, String> actual = verdicts("a.b.X", INPUT);
        assertVerdicts(actual,
                "Pair = @Immutable(hc=true) @Container",
                "Point = @Container",
                "Point.getX() = @NotModified",
                "Point.setX() = @Modified",
                // the constructor stores two points that translateHorizontally modifies: not a @Container
                "Line = @FinalFields !@Container",
                "Line.<init>(point1) = @Modified",
                "Line.point1 = @Final @Modified",
                "Line.point2 = @Final @Modified",
                "Line.middle() = @NotModified",
                "Line.translateHorizontally() = @Modified",
                "ArrayContainer1 = @FinalFields @Container",
                "ArrayContainer1.data = @NotModified",
                "ArrayContainer1.stream() = @NotModified @Independent(hc=true)",
                "ArrayContainer2 = @FinalFields @Container",
                "ArrayContainer2.<init>(ts) = @Independent(hc=true)",
                "ArrayContainer3 = @Immutable(hc=true) @Container",
                "ArrayContainer3.<init>(ts) = @Independent(hc=true)",
                "ArrayContainer3.stream() = @NotModified @Independent(hc=true)",
                // DEVIATION, not an example of the book: copying with System.arraycopy in the constructor counts as a
                // modification of the field, and the type stops at @FinalFields
                "ArrayContainer3b = @FinalFields @Container",
                "ArrayContainer3b.data = @Modified",
                // with clone(), the type is immutable, but the parameter loses its hc=true
                "ArrayContainer3c = @Immutable(hc=true) @Container",
                "ArrayContainer3c.<init>(ts) = @Independent",
                "SetBasedContainer1 = @FinalFields @Container",
                "SetBasedContainer1.<init>(ts) = @Dependent",
                "SetBasedContainer2 = @FinalFields @Container",
                "SetBasedContainer2.<init>(ts) = @Independent(hc=true)",
                "SetBasedContainer3 = @Immutable(hc=true) @Container",
                "SetBasedContainer3.<init>(ts) = @Independent(hc=true)",
                "SetBasedContainer3.stream() = @NotModified @Independent(hc=true)",
                // DEVIATION: the book says @ImmutableContainer(hc=true), the public field holding the result of
                // Set.copyOf; rule 2 is decided on the field's declared type, Set
                "SetBasedContainer4 = @FinalFields @Container",
                "SetBasedContainer4.<init>(ts) = @Independent(hc=true)",
                "SetBasedContainer5 = @FinalFields @Container",
                "SetBasedContainer5.getSet() = @NotModified",
                "SetBasedContainer6 = @FinalFields @Container",
                "SetBasedContainer6.set = @Modified",
                "SetBasedContainer6.add() = @Modified",
                "SetBasedContainer6.add(t) = @Independent(hc=true)",
                "SetBasedContainer6.stream() = @NotModified @Independent(hc=true)",
                "OddPair = @FinalFields @Container",
                "Marker = @Immutable(hc=true) @Container",
                "EmptyClass = @Immutable(hc=true) @Container",
                "ImplementsMarker = @Immutable(hc=true) @Container",
                "ExtendsEmptyClass = @Immutable(hc=true) @Container",
                "HasSize = @Immutable(hc=true) @Container",
                "HasSize.isEmpty() = @NotModified",
                // DEVIATION, here and on One and OneWithOne: the book says @Container, the Consumer parameter of
                // visit being @IgnoreModifications implicitly; that convention is applied in the annotated APIs only
                "NonEmptyImmutableList = @Immutable(hc=true) !@Container",
                "NonEmptyImmutableList.visit(consumer) = @Modified",
                "NonEmptyImmutableList.isEmpty() = @NotModified",
                "NonEmptyList = @FinalFields @Container",
                "One = @Mutable !@Container",
                "One.first() = @NotModified",
                "One.setFirst() = @Modified",
                "One.visit() = @NotModified",
                "OneWithOne = @FinalFields !@Container",
                "OneWithOne.first() = @NotModified",
                "OneWithOne.setFirst() = @Modified",
                // DEVIATION: the book says @NotModified
                "OneWithOne.visit() = @Modified",
                "ReceiveStrings = @FinalFields @Container",
                "ReceiveStrings.list = @Modified",
                "ReceiveStrings.accept() = @Modified");
    }
}
