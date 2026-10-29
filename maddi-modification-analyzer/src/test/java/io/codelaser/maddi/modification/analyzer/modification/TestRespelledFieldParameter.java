package io.codelaser.maddi.modification.analyzer.modification;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/*
 guava's ImmutableList.construct(Object...), reduced (CodeLaser/maddi-mod#72 4a). The summary is the union of its
 paths: 'construct ← EMPTY' from one, 'construct.element ← elements[0]' from another, so the parameter carries
 'elements[0] → EMPTY.element'. That field is a Singleton's 'element' respelled onto the IList-typed EMPTY: a fresh
 'IList.element' that IList does not declare, whose modification is never decided. It kept the parameter undecided,
 and the provisional 'modified' of the first iteration stuck; with it every ImmutableList.of(e1..eN) parameter.
 constructIf has the shape with if-else, construct with guava's old-style switch.
 */
public class TestRespelledFieldParameter extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.Arrays;
            import java.util.Objects;
            class X {
                static abstract class IList<E> {
                    static final IList<Object> EMPTY = new Regular<>(new Object[0]);
                    @SuppressWarnings("unchecked")
                    static <E> IList<E> of() { return (IList<E>) EMPTY; }
                    static <E> IList<E> of(E e1) { return new Singleton<>(e1); }
                    static <E> IList<E> of(E e1, E e2) { return construct(e1, e2); }
                    static <E> IList<E> construct(Object... elements) {
                        return asImmutableList(checkElementsNotNull(elements));
                    }
                    static <E> IList<E> constructIf(Object... elements) {
                        return asIf(checkElementsNotNull(elements), elements.length);
                    }
                    @SuppressWarnings("unchecked")
                    static <E> IList<E> asImmutableList(Object[] elements) {
                        switch (elements.length) {
                            case 0:
                                return of();
                            case 1:
                                E onlyElement = (E) Objects.requireNonNull(elements[0]);
                                return of(onlyElement);
                            default:
                                return new Regular<>(elements);
                        }
                    }
                    @SuppressWarnings("unchecked")
                    static <E> IList<E> asIf(Object[] elements, int length) {
                        if (length == 0) {
                            return of();
                        } else if (length == 1) {
                            E onlyElement = (E) Objects.requireNonNull(elements[0]);
                            return of(onlyElement);
                        } else {
                            Object[] trimmed = length < elements.length ? Arrays.copyOf(elements, length) : elements;
                            return new Regular<>(trimmed);
                        }
                    }
                }
                static final class Regular<E> extends IList<E> {
                    final Object[] array;
                    Regular(Object[] array) { this.array = array; }
                }
                static final class Singleton<E> extends IList<E> {
                    final E element;
                    Singleton(E element) { this.element = Objects.requireNonNull(element); }
                }
                static Object[] checkElementsNotNull(Object... array) {
                    for (int i = 0; i < array.length; i++) {
                        if (array[i] == null) throw new NullPointerException("at index " + i);
                    }
                    return array;
                }
            }
            """;

    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(X);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(20).build()).analyze(ao);
        TypeInfo iList = X.findSubType("IList");
        StringBuilder sb = new StringBuilder();
        for (String name : List.of("construct", "constructIf")) {
            MethodInfo m = iList.findUniqueMethod(name, 1);
            sb.append(name).append(": ").append(m.parameters().getFirst().isUnmodified()).append('\n');
        }
        MethodInfo of2 = iList.findUniqueMethod("of", 2);
        sb.append("of: ").append(of2.parameters().get(0).isUnmodified()).append(' ')
                .append(of2.parameters().get(1).isUnmodified()).append('\n');
        // was 'of: true false' (gate NORESPELLEDFIELD): which provisional 'modified' sticks depends on the analysis order
        assertEquals("""
                construct: true
                constructIf: true
                of: true true
                """, sb.toString());
    }
}
