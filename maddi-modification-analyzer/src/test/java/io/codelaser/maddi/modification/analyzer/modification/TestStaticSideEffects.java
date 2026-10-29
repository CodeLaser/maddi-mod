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

import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
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

    private static boolean nonModifying(MethodInfo mi) {
        return mi.analysis().getOrDefault(PropertyImpl.NON_MODIFYING_METHOD, ValueImpl.BoolImpl.FALSE).isTrue();
    }

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
     * The state of an object a static field holds is static state too: an assignment to its field, a modifying call
     * on its field. A Kotlin {@code object}'s properties are such fields of its {@code INSTANCE}: {@code
     * Settings.level = 5} assigns {@code Settings.INSTANCE.level}, which had no static side effect where {@code
     * Settings.raise()}, a modifying call on {@code INSTANCE}, had one.
     */
    @Language("java")
    private static final String FIELD_OF_STATIC = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            public class X {
                void configure() { Settings.INSTANCE.level = 5; }        // a field of a static field's object -> SSE
                void addName(String s) { Settings.INSTANCE.names.add(s); } // a modifying call on such a field -> SSE
                int read() { return Settings.INSTANCE.names.size(); }      // reads only -> not SSE
            }
            class Settings {
                static final Settings INSTANCE = new Settings();
                int level;
                final List<String> names = new ArrayList<>();
            }
            """;

    @DisplayName("assigning or modifying a field of an object held by another type's static field is a static side effect")
    @Test
    public void testFieldOfStatic() throws IOException {
        TypeInfo X = javaInspector.parse("a.b.X", FIELD_OF_STATIC);
        List<Info> ao = prepWork(X);
        analyzer.go(ao);

        assertTrue(sse(X.findUniqueMethod("configure", 0)), "Settings.INSTANCE.level = 5");
        assertTrue(sse(X.findUniqueMethod("addName", 1)), "Settings.INSTANCE.names.add(s)");
        assertFalse(sse(X.findUniqueMethod("read", 0)), "a read is no side effect");
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
     * constructor (road §050's {@code HasUniqueIdentifier}, its generator incremented on every construction), an
     * instance field's initializer (it runs in the constructor), or a method or constructor reference (as a lambda
     * calling it).
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
            class Service {
                private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("s");
                static void work() { LOG.info("x"); System.out.println("y"); }   // both @IgnoreModifications
            }
            class Numbered {
                private static int next;
                final int id = next++;                                // runs in the constructor
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
                static Object numbered() { return new Numbered(); }
                static void callsService() { Service.work(); }
                static java.util.function.IntSupplier reference() { return Counter::next; }
                static java.util.function.Supplier<HasUniqueIdentifier> factory() { return HasUniqueIdentifier::new; }
                static int use(Counter c) { return c.instanceNext(); }
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
        MethodInfo numberedConstructor = type(parsed, "Numbered").findConstructor(0);
        assertEquals(Set.of("Numbered"), modified(numberedConstructor), "the field initializer's next++");
        assertFalse(sse(numberedConstructor));

        // another type's state: a static side effect, through a static method, an instance method, a
        // constructor, recursion, and on to the callers
        for (String name : List.of("twice", "viaInstance", "viaRecursion", "create", "createChild", "indirect", "numbered",
                "reference", "factory")) {
            assertTrue(sse(client.findUniqueMethod(name, 0)), name);
        }
        assertEquals(Set.of("Counter"), modified(client.findUniqueMethod("twice", 0)));
        assertEquals(Set.of("HasUniqueIdentifier"), modified(client.findUniqueMethod("createChild", 0)));
        assertFalse(sse(client.findUniqueMethod("reads", 0)), "reading another type's static state");
        // System.out is an @IgnoreModifications field, Logger.info an @IgnoreModifications method
        assertEquals(Set.of(), modified(type(parsed, "Service").findUniqueMethod("work", 0)));
        assertFalse(sse(client.findUniqueMethod("callsService", 0)));

        // modifying the own static state is a modification (road §050); another type's is a static side effect only
        for (String name : List.of("next", "nextTwice", "instanceNext")) {
            assertFalse(nonModifying(counter.findUniqueMethod(name, 0)), name);
        }
        assertFalse(nonModifying(counter.findUniqueMethod("recursive", 1)));
        assertTrue(nonModifying(counter.findUniqueMethod("read", 0)));
        for (String name : List.of("twice", "viaRecursion", "create", "createChild", "indirect", "numbered", "reads",
                "callsService", "reference", "factory")) {
            assertTrue(nonModifying(client.findUniqueMethod(name, 0)), name);
        }
        assertTrue(nonModifying(type(parsed, "Service").findUniqueMethod("work", 0)));
    }

    // the same verdicts with the modification reachability pass, the CLI's and the IDE daemon's default and then the
    // single writer of NON_MODIFYING_METHOD; and through it, a caller's argument is modified
    @DisplayName("own static state is a modification under the reachability pass too")
    @Test
    public void testOwnStateUnderReachability() throws IOException {
        TypeInfo parsed = javaInspector.parse("a.b.Client", OWN_STATE);
        List<Info> ao = prepAnalyzer.doPrimaryTypes(Set.copyOf(parsed.compilationUnit().types()));
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10)
                .setModificationViaReachability(true)
                .build()).analyze(ao);
        TypeInfo client = type(parsed, "Client");
        TypeInfo counter = type(parsed, "Counter");

        for (String name : List.of("next", "nextTwice", "instanceNext")) {
            assertFalse(nonModifying(counter.findUniqueMethod(name, 0)), name);
        }
        assertFalse(nonModifying(counter.findUniqueMethod("recursive", 1)));
        assertTrue(nonModifying(counter.findUniqueMethod("read", 0)));
        for (String name : List.of("twice", "viaRecursion", "create", "createChild", "indirect", "numbered", "reads",
                "callsService", "reference", "factory")) {
            assertTrue(nonModifying(client.findUniqueMethod(name, 0)), name);
        }
        MethodInfo use = client.findUniqueMethod("use", 1);
        assertTrue(nonModifying(use), "Counter's state, not Client's");
        assertEquals(ValueImpl.BoolImpl.FALSE, use.parameters().getFirst().analysis()
                .getOrNull(PropertyImpl.UNMODIFIED_PARAMETER, ValueImpl.BoolImpl.class), "c.instanceNext() modifies c");
    }

    /**
     * The culprit: the first statement responsible for the static side effect, followed into the callee to the
     * assignment itself, even where the callee has no static side effect of its own (Registry.register modifies
     * Registry's own state). Computed only when a run asks for it.
     */
    @Language("java")
    private static final String CULPRIT = """
            package a.b;
            public class Big {
                void big(String s) {
                    int x = 1;
                    Registry.register(s);
                    Registry.count = 3;
                }
            }
            class Registry {
                static int count;
                static void register(String s) {
                    String t = s.trim();
                    count = count + t.length();
                }
            }
            """;

    private static String culprit(MethodInfo mi) {
        var c = mi.analysis().getOrNull(PropertyImpl.STATIC_SIDE_EFFECTS_CULPRIT_METHOD, ValueImpl.CulpritImpl.class);
        return c == null ? null : c.steps().stream().map(st -> st.line() + " " + st.text())
                .collect(Collectors.joining(" <- "));
    }

    @DisplayName("culprits: the first responsible statement, followed into the callee to the assignment")
    @Test
    public void testCulprit() throws IOException {
        boolean before = io.codelaser.maddi.modification.analyzer.impl.StaticSideEffectAnalyzerImpl.CULPRITS;
        io.codelaser.maddi.modification.analyzer.impl.StaticSideEffectAnalyzerImpl.CULPRITS = true;
        try {
            TypeInfo X = javaInspector.parse("a.b.Big", CULPRIT);
            List<Info> ao = prepWork(X);
            analyzer.go(ao);

            MethodInfo big = X.findUniqueMethod("big", 1);
            assertTrue(sse(big));
            assertEquals("5 calls Registry.register(..) <- 13 assigns Registry.count", culprit(big));
            MethodInfo register = type(X, "Registry").findUniqueMethod("register", 1);
            assertFalse(sse(register), "its own static state");
            assertNull(culprit(register), "no static side effect, no culprit");
        } finally {
            io.codelaser.maddi.modification.analyzer.impl.StaticSideEffectAnalyzerImpl.CULPRITS = before;
        }
    }

    @DisplayName("culprits: a call of a contracted @StaticSideEffects method ends the chain")
    @Test
    public void testCulpritContract() throws IOException {
        boolean before = io.codelaser.maddi.modification.analyzer.impl.StaticSideEffectAnalyzerImpl.CULPRITS;
        io.codelaser.maddi.modification.analyzer.impl.StaticSideEffectAnalyzerImpl.CULPRITS = true;
        try {
            TypeInfo X = javaInspector.parse("a.b.X", PROPAGATION);
            List<Info> ao = prepWork(X);
            analyzer.go(ao);

            assertEquals("6 calls X.direct(..) <- 5 calls X.reconfigure(..), contracted @StaticSideEffects",
                    culprit(X.findUniqueMethod("indirect", 0)));
        } finally {
            io.codelaser.maddi.modification.analyzer.impl.StaticSideEffectAnalyzerImpl.CULPRITS = before;
        }
    }

    /** fernflower's LambdaProcessor.processClass: the recursive call comes first, the culprit later. */
    @Language("java")
    private static final String RECURSIVE = """
            package a.b;
            import java.util.List;
            public class Rec {
                void process(List<Rec> nested) {
                    for (Rec child : nested) process(List.of());
                    Registry2.count = 3;
                }
            }
            class Registry2 {
                static int count;
            }
            """;

    @DisplayName("culprits: a recursive call is no culprit; the walk goes on to the statement that is")
    @Test
    public void testCulpritRecursive() throws IOException {
        boolean before = io.codelaser.maddi.modification.analyzer.impl.StaticSideEffectAnalyzerImpl.CULPRITS;
        io.codelaser.maddi.modification.analyzer.impl.StaticSideEffectAnalyzerImpl.CULPRITS = true;
        try {
            TypeInfo rec = javaInspector.parse("a.b.Rec", RECURSIVE);
            List<Info> ao = prepWork(rec);
            analyzer.go(ao);

            assertEquals("6 assigns Registry2.count", culprit(rec.findUniqueMethod("process", 1)));
        } finally {
            io.codelaser.maddi.modification.analyzer.impl.StaticSideEffectAnalyzerImpl.CULPRITS = before;
        }
    }

    @DisplayName("culprits are off by default")
    @Test
    public void testNoCulpritByDefault() throws IOException {
        TypeInfo X = javaInspector.parse("a.b.Big", CULPRIT);
        List<Info> ao = prepWork(X);
        analyzer.go(ao);

        MethodInfo big = X.findUniqueMethod("big", 1);
        assertTrue(sse(big));
        assertNull(culprit(big));
    }

    private static TypeInfo type(TypeInfo primary, String simpleName) {
        return primary.compilationUnit().types().stream()
                .filter(t -> simpleName.equals(t.simpleName())).findFirst().orElseThrow();
    }
}
