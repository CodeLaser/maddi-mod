/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2025, Bart Naudts, https://github.com/CodeLaser/maddi
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details. You should have received a copy of the GNU Lesser General Public
 * License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.codelaser.maddi.modification.analyzer.modification;

import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CodeLaser/maddi-mod#39, guava's Forwarding shape. {@code delegate()} is abstract, and its only implementation is an
 * anonymous class returning a parameter it captured from the enclosing method. The captured parameter is the anonymous
 * object's state (its synthetic final field): the implementation is dependent, so the abstract method is, and the
 * mutators that go through {@code delegate()} modify.
 * <p>
 * Before the fix the implementation read {@code @Independent} (the independence rule judged links to fields of
 * {@code this} only), the fold made {@code ForwardingDeque.delegate()} independent, and {@code push} and {@code pop}
 * non-modifying: unsound as a library hint.
 */
public class TestCapturedDelegate extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.Deque;
            import java.util.ArrayDeque;
            class X {
                abstract static class ForwardingDeque<E> {
                    protected abstract Deque<E> delegate();
                    public void push(E e) { delegate().push(e); }
                    public E pop() { return delegate().pop(); }
                    public E peek() { return delegate().peek(); }
                }
                static <T> ForwardingDeque<T> wrap(Deque<T> delegate) {
                    return new ForwardingDeque<T>() {
                        @Override
                        protected Deque<T> delegate() {
                            return delegate;
                        }
                    };
                }
                static <T> ForwardingDeque<T> wrapLocal(Deque<T> source) {
                    Deque<T> copy = new ArrayDeque<>(source);
                    return new ForwardingDeque<T>() {
                        @Override
                        protected Deque<T> delegate() {
                            return copy;
                        }
                        public void clearIt() { copy.clear(); }
                        public int sizeIt() { return copy.size(); }
                    };
                }
            }
            """;

    private TypeInfo analyzed;

    private TypeInfo analyze() {
        if (analyzed == null) {
            analyzed = javaInspector.parse("a.b.X", INPUT);
            List<Info> ao = prepWork(analyzed);
            new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                    .setMaxIterations(10)
                    .build()).analyze(ao);
        }
        return analyzed;
    }

    private static MethodInfo method(TypeInfo typeInfo, String name) {
        return typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private static TypeInfo anonymousClassIn(MethodInfo methodInfo) {
        AtomicReference<TypeInfo> anon = new AtomicReference<>();
        methodInfo.methodBody().visit(e -> {
            if (e instanceof ConstructorCall cc && cc.anonymousClass() != null) anon.set(cc.anonymousClass());
            return true;
        });
        return anon.get();
    }

    private static String independent(MethodInfo methodInfo) {
        return String.valueOf(methodInfo.analysis().getOrNull(PropertyImpl.INDEPENDENT_METHOD,
                ValueImpl.IndependentImpl.class));
    }

    private static String links(MethodInfo mi) {
        MethodLinkedVariables mlv = mi.analysis().getOrNull(MethodLinkedVariablesImpl.METHOD_LINKS,
                MethodLinkedVariablesImpl.class);
        return String.valueOf(mlv);
    }

    @DisplayName("an anonymous delegate() returning a captured parameter is dependent, so the mutators modify")
    @Test
    public void capturedParameter() {
        TypeInfo X = analyze();
        MethodInfo anonDelegate = method(anonymousClassIn(method(X, "wrap")), "delegate");
        assertEquals("[] --> delegate←0:delegate", links(anonDelegate), "the captured parameter reaches the summary");
        assertEquals("@Dependent", independent(anonDelegate));

        TypeInfo fd = X.findSubType("ForwardingDeque");
        assertEquals("@Dependent", independent(method(fd, "delegate")), "the fold over the one implementation");
        assertEquals("push=false pop=false peek=true",
                "push=" + method(fd, "push").isNonModifying()
                + " pop=" + method(fd, "pop").isNonModifying()
                + " peek=" + method(fd, "peek").isNonModifying());
    }

    // The two sides of the same shape the engine does not cover yet, pinned as they are (CodeLaser/maddi-mod#43):
    // a captured LOCAL does not reach the summary's links (the statement-level link 'delegate ← copy' is not built),
    // so that delegate() still reads independent, while a modification of the local is the method's
    // (copyModificationsIntoMethod's closure set); and the creation site 'new ForwardingDeque<T>() {...}' does not
    // link the new object to what it captured, so wrap's result is independent of its parameter.
    @DisplayName("known gaps: a captured local, and the creation site")
    @Test
    public void knownGaps() {
        TypeInfo X = analyze();
        MethodInfo wrapLocal = method(X, "wrapLocal");
        TypeInfo anon = anonymousClassIn(wrapLocal);
        assertEquals("[] --> -", links(method(anon, "delegate")), "the captured local is not in the summary");
        assertEquals("@Independent", independent(method(anon, "delegate")), "unsound, see the comment");
        assertEquals("clearIt=false sizeIt=true",
                "clearIt=" + method(anon, "clearIt").isNonModifying()
                + " sizeIt=" + method(anon, "sizeIt").isNonModifying(), "the modification side is right");

        MethodInfo wrap = method(X, "wrap");
        assertEquals("[-] --> -", links(wrap), "no link from the new object to the captured parameter");
        assertEquals("@Independent", String.valueOf(wrap.parameters().getFirst().analysis()
                .getOrNull(PropertyImpl.INDEPENDENT_PARAMETER, ValueImpl.IndependentImpl.class)), "unsound, see the comment");
    }
}
