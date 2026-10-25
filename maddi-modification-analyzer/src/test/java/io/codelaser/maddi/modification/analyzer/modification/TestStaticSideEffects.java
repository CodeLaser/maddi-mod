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

import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code @StaticSideEffects}: a method modifies static/global state of another type (road-to-immutability §050).
 * The global-escape signal underpinning the confinement guard's method-granularity arm: an assignment to, or a
 * modifying call on, another type's static field, directly or through a callee.
 */
public class TestStaticSideEffects extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            class Config {
                static int level;
            }
            public class X {
                private int n;
                void raise() { Config.level = 5; }       // assignment to another type's static field -> SSE
                void pure() { int x = 1 + 1; }           // no static state touched
                void own() { this.n = 3; }               // own instance field -> not a static side effect
            }
            """;

    private static boolean sse(MethodInfo mi) {
        return mi.analysis().getOrDefault(PropertyImpl.STATIC_SIDE_EFFECTS_METHOD, ValueImpl.BoolImpl.FALSE).isTrue();
    }

    @DisplayName("assignment to another type's static field is a static side effect; own/pure methods are not")
    @Test
    public void test() throws IOException {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(X);
        analyzer.go(ao);

        assertTrue(sse(X.findUniqueMethod("raise", 0)), "Config.level = 5 is a static side effect");
        assertFalse(sse(X.findUniqueMethod("pure", 0)), "a pure method has no static side effect");
        assertFalse(sse(X.findUniqueMethod("own", 0)), "modifying an own instance field is not a static side effect");
    }

    /**
     * The callee-annotation half (the AAPI safe-surface): a method contracted {@code @StaticSideEffects} (here
     * {@code reconfigure()}, standing in for {@code System.setOut}) whose effect is invisible from its body makes
     * its callers a static-side-effect method too, transitively. {@code SourceContractMaterializer} lifts the
     * source annotation into {@code analysis()}; the analyzer then propagates it.
     */
    @Language("java")
    private static final String PROPAGATION = """
            package a.b;
            import io.codelaser.maddi.annotation.rare.StaticSideEffects;
            public class X {
                @StaticSideEffects void reconfigure() { }   // effect invisible from body, asserted by contract
                void direct() { reconfigure(); }            // calls an SSE method -> SSE
                void indirect() { direct(); }               // transitive -> SSE
                void clean() { int x = 1 + 1; }             // calls nothing global -> not SSE
            }
            """;

    @DisplayName("a call to a @StaticSideEffects method propagates to the caller, transitively")
    @Test
    public void testPropagation() throws IOException {
        TypeInfo X = javaInspector.parse("a.b.X", PROPAGATION);
        List<Info> ao = prepWork(X);
        analyzer.go(ao);

        assertTrue(sse(X.findUniqueMethod("reconfigure", 0)), "contracted @StaticSideEffects");
        assertTrue(sse(X.findUniqueMethod("direct", 0)), "calls a static-side-effect method");
        assertTrue(sse(X.findUniqueMethod("indirect", 0)), "transitively calls a static-side-effect method");
        assertFalse(sse(X.findUniqueMethod("clean", 0)), "touches no global state");
    }
    /**
     * CodeLaser/maddi-mod#65: a type's own static state is its own, but not its callers'. {@code Counter.next()}
     * modifies Counter's static counter, no static side effect; {@code Client.twice()} calls it and modifies state
     * that is not Client's: a static side effect, whether the callee is static, an instance method, or a
     * constructor (road §050's {@code HasUniqueIdentifier}, its generator incremented on every construction).
     */
    @Language("java")
    private static final String OWN_STATE = """
            package a.b;
            import java.util.concurrent.atomic.AtomicInteger;
            class Counter {
                private static int count;
                static int next() { return ++count; }
                static int nextTwice() { return next() + next(); }   // own state, through an own method
                int instanceNext() { return next(); }
                static int recursive(int n) { return n == 0 ? next() : recursive(n - 1); }
                static int read() { return count; }
            }
            class HasUniqueIdentifier {
                private static final AtomicInteger generator = new AtomicInteger();
                final int identifier;
                HasUniqueIdentifier() { identifier = generator.getAndIncrement(); }
            }
            class Child extends HasUniqueIdentifier {
                Child() { super(); }                                  // a parent type's state: excused
            }
            public class Client {
                static int twice() { return Counter.next() + Counter.next(); }
                static int viaInstance() { return new Counter().instanceNext(); }
                static int viaRecursion() { return Counter.recursive(3); }
                static Object create() { return new HasUniqueIdentifier(); }
                static Object createChild() { return new Child(); }
                static int reads() { return Counter.read(); }
                static int indirect() { return twice(); }
            }
            """;

    private static Set<String> modified(MethodInfo mi) {
        return mi.analysis().getOrDefault(PropertyImpl.STATIC_STATE_MODIFIED_METHOD, ValueImpl.SetOfTypeInfoImpl.EMPTY)
                .typeInfoSet().stream().map(TypeInfo::simpleName).collect(Collectors.toSet());
    }

    @DisplayName("#65: calling another type's method that modifies that type's static state is a static side effect")
    @Test
    public void testOwnStateOfAnotherType() throws IOException {
        TypeInfo parsed = javaInspector.parse("a.b.Client", OWN_STATE);
        // four primary types in one file: all of them analysed
        List<Info> ao = prepAnalyzer.doPrimaryTypes(Set.copyOf(parsed.compilationUnit().types()));
        analyzer.go(ao);
        TypeInfo client = type(parsed, "Client");
        TypeInfo counter = type(parsed, "Counter");
        TypeInfo unique = type(parsed, "HasUniqueIdentifier");
        TypeInfo child = type(parsed, "Child");

        // own state: modified, no static side effect
        for (String name : List.of("next", "nextTwice", "instanceNext")) {
            MethodInfo mi = counter.findUniqueMethod(name, 0);
            assertEquals(Set.of("Counter"), modified(mi), name);
            assertFalse(sse(mi), name);
        }
        assertEquals(Set.of("Counter"), modified(counter.findUniqueMethod("recursive", 1)));
        assertEquals(Set.of(), modified(counter.findUniqueMethod("read", 0)));
        MethodInfo constructor = unique.findConstructor(0);
        assertEquals(Set.of("HasUniqueIdentifier"), modified(constructor));
        assertFalse(sse(constructor));
        assertFalse(sse(child.findConstructor(0)), "a parent type's static state is excused");

        // another type's state: a static side effect, through a static method, an instance method, a
        // constructor, recursion, and on to the callers
        for (String name : List.of("twice", "viaInstance", "viaRecursion", "create", "createChild", "indirect")) {
            assertTrue(sse(client.findUniqueMethod(name, 0)), name);
        }
        assertEquals(Set.of("Counter"), modified(client.findUniqueMethod("twice", 0)));
        assertEquals(Set.of("HasUniqueIdentifier"), modified(client.findUniqueMethod("createChild", 0)));
        assertFalse(sse(client.findUniqueMethod("reads", 0)), "reading another type's static state");
    }

    private static TypeInfo type(TypeInfo primary, String simpleName) {
        return primary.compilationUnit().types().stream()
                .filter(t -> simpleName.equals(t.simpleName())).findFirst().orElseThrow();
    }
}
