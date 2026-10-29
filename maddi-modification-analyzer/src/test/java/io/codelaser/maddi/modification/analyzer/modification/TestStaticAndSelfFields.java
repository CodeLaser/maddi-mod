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

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Static and instance fields are treated the same (road to immutability §050: "The definitions make no distinction
 * between static and instance fields"), and a SELF-REFERENCING field -- one whose type is the owning type -- is
 * skipped entirely, for immutability and independence alike: whatever it could contribute is already the type's own
 * verdict, so reading it only makes that verdict wait on itself.
 * <ul>
 * <li>{@code CountAccess} is the book's example: a static counter the type modifies itself makes it
 *     {@code @FinalFields}. Statics count.</li>
 * <li>{@code WithStaticList}: a private, never-modified {@code static final List} is a field that allows hidden
 *     content, so the label is {@code hc=true} -- until 2026-09-23 the hc-free gate skipped statics and granted
 *     {@code @Immutable}.</li>
 * <li>{@code WithStaticString}: the control, a static constant of deeply immutable type stays hc-free.</li>
 * <li>{@code Cell}: the precondition for skipping self fields in rule 1 -- a modification made through another
 *     instance ({@code other.g.add(..)}) lands on {@code Cell.g}.</li>
 * <li>{@code Node}: a MUTABLE type returning its self field. Self fields are skipped only in the independence that
 *     feeds the immutability rule; the PUBLISHED independence of a mutable type exposing one stays dependent.</li>
 * <li>{@code Link}: rule 0 is not skipped -- an assignable self field makes the type mutable.</li>
 * <li>{@code Single}: a singleton ({@code static final Single INSTANCE}, returned by {@code instance()}), the shape
 *     of vavr's {@code Option.None}: its own-typed field graded it DEPENDENT and kept its hc label undecided.</li>
 * </ul>
 */
public class TestStaticAndSelfFields extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import io.codelaser.maddi.annotation.rare.IgnoreModifications;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.concurrent.atomic.AtomicInteger;

            public class X {

                static final class CountAccess {
                    private static final AtomicInteger counter = new AtomicInteger();
                    private final String k;

                    CountAccess(String k) {
                        this.k = k;
                    }

                    String getK() {
                        counter.getAndIncrement();
                        return k;
                    }
                }

                // an assignment counts as a modifying call does: area() is @Modified
                record Circle(double radius) {
                    private static int cntCalls;

                    double area() {
                        ++cntCalls;
                        return Math.PI * radius * radius;
                    }
                }

                // the book's second CountAccess: the field disclaims its modifications
                static final class CountIgnored {
                    @IgnoreModifications
                    private static final AtomicInteger counter = new AtomicInteger();
                    private final String k;

                    CountIgnored(String k) {
                        this.k = k;
                    }

                    String getK() {
                        counter.getAndIncrement();
                        return k;
                    }

                    static int countAccessToK() {
                        return counter.get();
                    }
                }

                static final class WithStaticList {
                    private static final List<String> NAMES = new ArrayList<>();
                    private final String s;

                    WithStaticList(String s) {
                        this.s = s;
                    }

                    static int count() {
                        return NAMES.size();
                    }

                    String s() {
                        return s;
                    }
                }

                static final class WithStaticString {
                    private static final String PREFIX = "p";
                    private final String s;

                    WithStaticString(String s) {
                        this.s = s;
                    }

                    String s() {
                        return PREFIX + s;
                    }
                }

                // the precondition of skipping self fields for rule 1: a modification made THROUGH another instance
                // must land on the owning type's own field
                static final class Cell {
                    private final List<String> g = new ArrayList<>();
                    private final Cell other;

                    Cell(Cell other) {
                        this.other = other;
                    }

                    void touchOther() {
                        other.g.add("x");
                    }

                    int size() {
                        return g.size();
                    }
                }

                // a MUTABLE type exposing its self field: the PUBLISHED independence must stay dependent
                static final class Node {
                    private final List<String> value = new ArrayList<>();
                    private final Node next;

                    Node(Node next) {
                        this.next = next;
                    }

                    void add(String s) {
                        value.add(s);
                    }

                    Node next() {
                        return next;
                    }
                }

                // rule 0 is NOT skipped: an assignable self field makes the type mutable
                static final class Link {
                    private Link next;

                    void setNext(Link next) {
                        this.next = next;
                    }
                }

                static final class Single {
                    private static final Single INSTANCE = new Single("x");
                    private final String s;

                    private Single(String s) {
                        this.s = s;
                    }

                    static Single instance() {
                        return INSTANCE;
                    }

                    String s() {
                        return s;
                    }
                }
            }
            """;

    private static boolean nonModifying(MethodInfo mi) {
        return mi.analysis().getOrDefault(PropertyImpl.NON_MODIFYING_METHOD, ValueImpl.BoolImpl.FALSE).isTrue();
    }

    @DisplayName("statics count like instance fields; a self-referencing field is skipped")
    @Test
    public void test() throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo x = bundle.javaInspector().parse("a.b.X", INPUT);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(x);
        IteratingAnalyzer analyzer = new IteratingAnalyzerImpl(bundle.javaInspector(),
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(10).build());
        analyzer.analyze(analysisOrder);
        for (String name : List.of("CountAccess", "WithStaticList", "WithStaticString", "Cell", "Node", "Link",
                "Single")) {
            TypeInfo t = x.findSubType(name);
            System.out.println("### " + name + " " + immutable(t) + " " + t.analysis()
                    .getOrNull(PropertyImpl.INDEPENDENT_TYPE, ValueImpl.IndependentImpl.class));
        }

        assertFalse(immutable(x.findSubType("CountAccess")).isAtLeastImmutableHC(),
                "the book's CountAccess modifies its own static counter");
        assertFalse(nonModifying(x.findSubType("CountAccess").findUniqueMethod("getK", 0)),
                "the book's CountAccess.getK() is @Modified");
        assertFalse(nonModifying(x.findSubType("Circle").findUniqueMethod("area", 0)), "++cntCalls");
        assertTrue(nonModifying(x.findSubType("CountIgnored").findUniqueMethod("getK", 0)), "@IgnoreModifications");
        assertTrue(nonModifying(x.findSubType("CountIgnored").findUniqueMethod("countAccessToK", 0)));
        assertTrue(nonModifying(x.findSubType("WithStaticList").findUniqueMethod("count", 0)), "reads only");
        assertTrue(nonModifying(x.findSubType("Single").findUniqueMethod("instance", 0)), "reads only");
        assertSame(ValueImpl.ImmutableImpl.IMMUTABLE, immutable(x.findSubType("WithStaticString")));
        assertSame(ValueImpl.ImmutableImpl.IMMUTABLE_HC, immutable(x.findSubType("WithStaticList")),
                "a static List field allows hidden content");
        assertSame(ValueImpl.ImmutableImpl.IMMUTABLE, immutable(x.findSubType("Single")),
                "the own-typed INSTANCE field is skipped");
        assertSame(ValueImpl.IndependentImpl.INDEPENDENT, independent(x.findSubType("Single")),
                "published: Single is immutable, so exposing INSTANCE is independent");

        TypeInfo cell = x.findSubType("Cell");
        assertEquals(ValueImpl.BoolImpl.FALSE, cell.getFieldByName("g", true).analysis()
                .getOrNull(PropertyImpl.UNMODIFIED_FIELD, ValueImpl.BoolImpl.class),
                "precondition: other.g.add(..) is a modification of Cell.g");
        assertFalse(immutable(cell).isAtLeastImmutableHC(), "Cell modifies g through 'other'");

        TypeInfo node = x.findSubType("Node");
        assertFalse(immutable(node).isAtLeastImmutableHC(), "Node modifies value");
        assertEquals(ValueImpl.IndependentImpl.DEPENDENT, node.getFieldByName("next", true).analysis()
                .getOrNull(PropertyImpl.INDEPENDENT_FIELD, ValueImpl.IndependentImpl.class),
                "published: next() exposes a mutable Node");
        assertSame(ValueImpl.IndependentImpl.DEPENDENT, independent(node));

        assertSame(ValueImpl.ImmutableImpl.MUTABLE, immutable(x.findSubType("Link")), "rule 0 is not skipped");
    }

    private static Value.Independent independent(TypeInfo t) {
        return t.analysis().getOrNull(PropertyImpl.INDEPENDENT_TYPE, ValueImpl.IndependentImpl.class);
    }

    private static Value.Immutable immutable(TypeInfo t) {
        return t.analysis().getOrNull(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.class);
    }
}
