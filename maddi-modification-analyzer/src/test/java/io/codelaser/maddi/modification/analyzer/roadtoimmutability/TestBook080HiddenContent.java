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
 * The examples of road-to-immutability §080, "More on hidden content".
 */
public class TestBook080HiddenContent extends BookTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import io.codelaser.maddi.annotation.Independent;
            import io.codelaser.maddi.annotation.NotModified;
            import java.util.*;
            import java.util.function.Consumer;
            public class X {
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
                static class ImmutableOne<T> implements NonEmptyImmutableList<T> {
                    private final T t;
                    public ImmutableOne(T t) { this.t = t; }
                    @Override
                    public int size() { return 1; }
                    @Override
                    public T first() { return t; }
                    @Override
                    public void visit(Consumer<T> consumer) { consumer.accept(t); }
                }
                static class Lists<T> {
                    private final List<T> list = new ArrayList<>();
                    public void forEach(Consumer<T> consumer) {
                        for (T t : list) consumer.accept(t);
                    }
                }
                static void print(NonEmptyImmutableList<StringBuilder> list) {
                    list.visit(System.out::println);
                }
                static void addNewLine(NonEmptyImmutableList<StringBuilder> list) {
                    list.visit(sb -> sb.append("\\n"));
                }
                static void addNewLine2(NonEmptyList<StringBuilder> list) {
                    list.visit(sb -> sb.append("\\n"));
                }
                static void replace(NonEmptyList<StringBuilder> list) {
                    list.setFirst(new StringBuilder("?"));
                }
                static String useAddNewLine(StringBuilder input) {
                    NonEmptyList<StringBuilder> list = new One<>();
                    list.setFirst(input);
                    addNewLine2(list);
                    return list.first().toString();
                }
                static String useReplace(StringBuilder input) {
                    NonEmptyList<StringBuilder> list = new One<>();
                    list.setFirst(input);
                    replace(list);
                    return list.first().toString();
                }
                static class Strings {
                    private final List<String> strings = new ArrayList<>();
                    void addToStrings(NonEmptyList<StringBuilder> list) {
                        list.visit(sb -> strings.add(sb.toString()));
                    }
                }
                public static class ImmutableArray<T> implements Iterable<T> {
                    private final T[] elements;
                    @SuppressWarnings("unchecked")
                    public ImmutableArray(List<T> input) { this.elements = (T[]) input.toArray(); }
                    @Override
                    public Iterator<T> iterator() { return new IteratorImpl(); }
                    class IteratorImpl implements Iterator<T> {
                        private int i;
                        @Override
                        public boolean hasNext() { return i < elements.length; }
                        @Override
                        public T next() { return elements[i++]; }
                    }
                }
                static class GetterSetter {
                    private int i;
                    public int getI() { return i; }
                    public void setI(int i) { this.i = i; }
                }
            }
            """;

    @DisplayName("§080, More on hidden content")
    @Test
    public void test() throws IOException {
        Map<String, String> actual = verdicts("a.b.X", INPUT);
        assertVerdicts(actual,
                // DEVIATION, as in §050: the book says @Container, and @Independent(hc=true) on the consumer; the
                // Consumer parameter of a source method is not @IgnoreModifications implicitly
                "ImmutableOne = @Immutable(hc=true) !@Container",
                "ImmutableOne.<init>(t) = @Independent(hc=true)",
                "ImmutableOne.first() = @NotModified @Independent(hc=true)",
                "ImmutableOne.visit() = @NotModified",
                "ImmutableOne.visit(consumer) = @Modified @Independent",
                // DEVIATION: the book's generic forEach is @NotModified, with @Independent(hc=true) on the consumer
                "Lists.forEach() = @Modified",
                "Lists.forEach(consumer) = @Modified @Independent",
                "X.print(list) = @NotModified",
                // DEVIATION: the book says @Modified, and @Modified(hc=true) on a NonEmptyList: the lambda's modification
                // of its parameter is not propagated through the visit method of a source interface
                "X.addNewLine(list) = @NotModified",
                // addNewLine2 is the addNewLine of the contrast with replace, on a NonEmptyList
                "X.addNewLine2(list) = @NotModified",
                "X.replace(list) = @Modified",
                // DEVIATION: the book says @Modified, the step back to the elements through a call
                "X.useAddNewLine(input) = @NotModified",
                "X.useReplace(input) = @NotModified",
                "Strings.addToStrings() = @Modified",
                "Strings.addToStrings(list) = @NotModified",
                // DEVIATION: the book says @ImmutableContainer, with iterator() and IteratorImpl @Independent(hc=true)
                "ImmutableArray = @FinalFields @Container",
                "ImmutableArray.iterator() = @Independent",
                "IteratorImpl = @Independent @Container",
                "IteratorImpl.next() = @Modified",
                "GetterSetter = @Independent @Container");
    }
}
