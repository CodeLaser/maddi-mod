package io.codelaser.maddi.modification.analyzer.nullability;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Tolerance}: maddi-mod#68's tolerated nulls, and where tolerance stops.
 */
public class TestTolerance extends NullabilityFixtures {

    @Language("java")
    private static final String TOLERANT = """
            package a.b;
            import java.util.Objects;
            public class TL {
                private final Object delegate;
                private final Object mutex;
                private String name = "";
                // Synchronized: null means 'this'
                public TL(Object delegate, Object mutex) {
                    this.delegate = Objects.requireNonNull(delegate);
                    this.mutex = mutex == null ? this : mutex;
                }
                // Preconditions: the message arguments are formatted, null as "null"
                public static void checkArgument(boolean b, String template, Object p1) {
                    if (!b) throw new IllegalArgumentException(lenientFormat(template, p1));
                }
                public static String lenientFormat(String template, Object... args) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(template);
                    for (Object a : args) sb.append(lenientToString(a));
                    return sb.toString();
                }
                static String lenientToString(Object o) {
                    if (o == null) return "null";
                    return o.toString();
                }
                public static void unused(String s) { }
                public int plusOne(Integer i) { return i + 1; }
                public void locked(Object lock) { synchronized (lock) { System.out.println(); } }
                public int onOnePath(boolean b, String s) { return b ? s.length() : 0; }
                public void rejects(String s) {
                    if (s == null) throw new IllegalArgumentException("s");
                }
                public void viaLocal(String s) {
                    String t = s;
                    System.out.println(t.length());
                }
                public void passedOnToDereference(String s) { onOnePath(true, s); }
                public void setName(String s) { this.name = s; }
                public int nameLength() { return name.length(); }
                private void helper(String s) { }
                public void callsHelper() { helper("x"); }
                public Object delegate() { return delegate; }
                public Object mutex() { return mutex; }
                // an exception's message and cause
                public static class UncheckedEx extends RuntimeException {
                    public UncheckedEx(String message, Throwable cause) { super(message, cause); }
                }
            }
            """;

    /*
     Without nullTests: a comparison with null would make 'mutex' and 'rejects(s)' nullable on its own.
     */
    @Language("java")
    private static final String TOLERANT_UNREAD = """
            package a.b;
            public class LS {
                public static class Listener {
                    public void failed(String from, Throwable failure) { }
                    public void logged(String message) { if (message != null) System.out.println(message.length()); }
                    public void ignored(String unused) { }
                }
                public static class Logging extends Listener {
                    @Override public void failed(String from, Throwable failure) {
                        if (failure != null) System.out.println(failure.getMessage());
                    }
                }
            }
            """;

    /*
     An empty hook tolerates nothing: a parameter an overridden method never reads is no evidence. guava's
     Service.Listener.failed(from, failure) is a hook to override; its parameters made nullable, the overrides in
     guava's tests no longer matched what kotlinc resolves (J2K, "'failed' overrides nothing"). A body that reads the
     parameter and tolerates its null is #68's case, but not in an override of an analysed method, whose tolerance the
     family would raise into the hook (guava's ServiceManager listener logging 'failure'). A method nothing analysed
     overrides ignoring a parameter tolerates its null ('ignored', guava's StringCatcher.methodWithoutAnnotation).
     */
    @DisplayName("#68: a parameter an overridden method never reads is not tolerant")
    @Test
    public void toleratedNullsNotUnread() {
        Map<String, String> v = byName(run("a.b.LS", TOLERANT_UNREAD, Policy.NULL_MARKED_FLOW_ONLY));
        assertEquals("String", v.get("a.b.LS.Listener.failed(String,Throwable):0:from"));
        assertEquals("Throwable", v.get("a.b.LS.Listener.failed(String,Throwable):1:failure"));
        assertEquals("String?", v.get("a.b.LS.Listener.logged(String):0:message"));
        assertEquals("String?", v.get("a.b.LS.Listener.ignored(String):0:unused"));
        assertEquals("Throwable", v.get("a.b.LS.Logging.failed(String,Throwable):1:failure"));
    }

    @Language("java")
    private static final String TOLERANT_OVERLOADED = """
            package a.b;
            public class VU {
                public static Object ensureNotNull(Object object, String name) {
                    return ensureNotNull(object, "%s cannot be null", name);
                }
                public static Object ensureNotNull(Object object, String format, Object... args) {
                    if (object == null) throw new IllegalArgumentException(String.format(format, args));
                    return object;
                }
                public static <T> T ensureGeneric(T object, String name) {
                    return ensureGeneric(object, "%s cannot be null", name);
                }
                public static <T> T ensureGeneric(T object, String format, Object... args) {
                    if (object == null) throw new IllegalArgumentException(String.format(format, args));
                    return object;
                }
                public void logged(String message) { if (message != null) System.out.println(message.length()); }
                public void logged(int code) { System.out.println(code); }
                public static void check(boolean b, String template, Object p1, char p2) {
                    if (!b) throw new IllegalArgumentException(template + p1 + p2);
                }
                public static void check(boolean b, String template, char p1, Object p2) {
                    if (!b) throw new IllegalArgumentException(template + p1 + p2);
                }
                public static class Failed extends RuntimeException {
                    public Failed(String message) { super(message); }
                    public Failed(String message, Throwable cause) { super(message, cause); }
                }
            }
            """;

    /*
     A parameter whose position and type an overload shares keeps its non-null verdict: Kotlin prefers the overload
     with the more specific parameter, String over String?, where Java goes by arity (langchain4j's ValidationUtils,
     "text" instead of "text cannot be null"). An overload with another type there leaves the tolerance, and so does
     one the arguments tell apart: another arity (Failed), or another type elsewhere (check, guava's Preconditions).
     */
    @DisplayName("#68: a parameter an overload shares the position and type of is not tolerant")
    @Test
    public void toleratedNullsNotOverloaded() {
        Map<String, String> v = byName(run("a.b.VU", TOLERANT_OVERLOADED, Policy.NULL_MARKED_FLOW_ONLY));
        assertEquals("String", v.get("a.b.VU.ensureNotNull(Object,String):1:name"));
        // each overload's own T: the same erasure, Object
        assertEquals("String", v.get("a.b.VU.ensureGeneric(Object,String):1:name"));
        assertEquals("String?", v.get("a.b.VU.logged(String):0:message"));
        assertEquals("Object?", v.get("a.b.VU.check(boolean,String,Object,char):2:p1"));
        assertEquals("String?", v.get("a.b.VU.Failed.<init>(String):0:message"));
    }

    @Language("java")
    private static final String TOLERANT_ANNOTATED = """
            package a.b;
            public class GD {
                @interface NonNull { }
                public void guarded(@NonNull String s) { if (s == null) return; System.out.println(s.length()); }
                public void plain(String s) { if (s == null) return; System.out.println(s.length()); }
                public void callsGuarded() { guarded("y"); plain("y"); }
            }
            """;

    /*
     A defensive null check under an explicit non-null annotation is not null reaching the parameter, also when the
     policy does not hold the annotation as a contract (a diagnosis checking it: diagnostics' TestNullIntoNonNull);
     the same body without the annotation is #68's tolerance.
     */
    @DisplayName("#68: a parameter annotated non-null is not tolerant, with or without contracts")
    @Test
    public void toleratedNullsAnnotatedNonNull() {
        // flow only: a null test would seed the parameter by itself; without contracts in a closed world is the policy
        // of diagnostics' Analyses
        Map<String, Policy> policies = Map.of("GC", Policy.NULL_MARKED_FLOW_ONLY,
                "GW", Policy.NULL_MARKED_FLOW_ONLY.withoutContracts()
                        .withWorld(World.CLOSED));
        policies.forEach((name, policy) -> {
            Map<String, String> v = byName(run("a.b." + name, TOLERANT_ANNOTATED.replace("GD", name), policy));
            assertEquals("String", v.get("a.b." + name + ".guarded(String):0:s"), name);
            assertEquals("String?", v.get("a.b." + name + ".plain(String):0:s"), name);
        });
    }

    @DisplayName("#68: a visible parameter whose body tolerates null is nullable, whatever the analysed callers pass")
    @Test
    public void toleratedNulls() {
        Report report = run("a.b.TL", TOLERANT, Policy.NULL_MARKED_FLOW_ONLY);
        System.out.println(explain(report));
        Map<String, String> v = byName(report);
        // Preconditions' message argument, through lenientFormat's varargs into lenientToString's null check; the
        // template appended to a StringBuilder; the varargs array itself is iterated
        assertEquals("Object?", v.get("a.b.TL.checkArgument(boolean,String,Object):2:p1"));
        assertEquals("String?", v.get("a.b.TL.checkArgument(boolean,String,Object):1:template"));
        assertEquals("Object?[]", v.get("a.b.TL.lenientFormat(String,Object[]):1:args"));
        // Synchronized: the mutex is tolerated, the delegate required; the field stays non-null
        assertEquals("Object?", v.get("a.b.TL.<init>(Object,Object):1:mutex"));
        assertEquals("Object", v.get("a.b.TL.<init>(Object,Object):0:delegate"));
        assertEquals("Object", v.get("a.b.TL.mutex"));
        // the exception constructor's message and cause, handed to RuntimeException
        assertEquals("String?", v.get("a.b.TL.UncheckedEx.<init>(String,Throwable):0:message"));
        assertEquals("Throwable?", v.get("a.b.TL.UncheckedEx.<init>(String,Throwable):1:cause"));
        assertEquals("String?", v.get("a.b.TL.unused(String):0:s"));
        // dereferenced on one path, rejected, dereferenced through a local or a callee: required
        assertEquals("String", v.get("a.b.TL.onOnePath(boolean,String):1:s"));
        assertEquals("String", v.get("a.b.TL.rejects(String):0:s"));
        assertEquals("String", v.get("a.b.TL.viaLocal(String):0:s"));
        // unboxed, locked on
        assertEquals("Integer", v.get("a.b.TL.plusOne(Integer):0:i"));
        assertEquals("Object", v.get("a.b.TL.locked(Object):0:lock"));
        assertEquals("String", v.get("a.b.TL.passedOnToDereference(String):0:s"));
        // stored into a field that is not nullable otherwise: not tolerated, the field keeps its verdict
        assertEquals("String", v.get("a.b.TL.setName(String):0:s"));
        // a private helper keeps the flow verdict
        assertEquals("String", v.get("a.b.TL.helper(String):0:s"));

        // gate NOTOLERANT: the closed world, as before
        Switches.offForNextPasses(Set.of("NOTOLERANT"));
        try {
            Map<String, String> off = byName(new NullabilityPass(Policy.NULL_MARKED_FLOW_ONLY)
                    .go(analysisOrder));
            assertEquals("Object", off.get("a.b.TL.checkArgument(boolean,String,Object):2:p1"));
            assertEquals("Object", off.get("a.b.TL.<init>(Object,Object):1:mutex"));
            assertEquals("String", off.get("a.b.TL.unused(String):0:s"));
        } finally {
            Switches.offForNextPasses(Set.of());
        }
    }

    @Language("java")
    private static final String TOLERANT_DEMANDS = """
            package a.b;
            import java.util.function.Consumer;
            import java.util.function.Function;
            public class TD {
                public interface Handler { void onPartial(String s); }
                public interface Director { void before(Object o); }
                // langchain4j LambdaStreamingResponseHandler: dereferenced in the anonymous class that captures it
                public static Handler onPartial(Consumer<String> consumer) {
                    return new Handler() {
                        @Override public void onPartial(String s) { consumer.accept(s); }
                    };
                }
                // the same in a lambda
                public static Handler viaLambda(Consumer<String> consumer) {
                    return s -> consumer.accept(s);
                }
                // timefold ChangedVariableNotifier.of: the receiver of a bound method reference
                public static Consumer<Object> of(Director director) {
                    return director::before;
                }
                // timefold Joiners/DefaultBiJoiner/AbstractJoiner: arrays whose elements are null only before they are filled
                public abstract static class AbstractJoiner<R> {
                    protected final Function<R, Object>[] rightMappings;
                    protected final int[] types;
                    @SuppressWarnings("unchecked")
                    protected AbstractJoiner(Function<R, ?> rightMapping, int type) {
                        this(new Function[] { rightMapping }, new int[] { type });
                    }
                    @SuppressWarnings("unchecked")
                    protected AbstractJoiner(Function<R, ?>[] rightMappings, int[] types) {
                        this.rightMappings = (Function<R, Object>[]) java.util.Objects.requireNonNull(rightMappings);
                        this.types = java.util.Objects.requireNonNull(types);
                    }
                    public final Function<R, Object> getRightMapping(int index) { return rightMappings[index]; }
                    public final int count() { return types.length; }
                }
                public static final class Joiner<A, B> extends AbstractJoiner<B> {
                    private final Function<A, Object>[] leftMappings;
                    @SuppressWarnings("unchecked")
                    public Joiner(Function<A, ?> leftMapping, int type, Function<B, ?> rightMapping) {
                        super(rightMapping, type);
                        this.leftMappings = new Function[] { leftMapping };
                    }
                    @SuppressWarnings("unchecked")
                    public Joiner(Function<A, ?>[] leftMappings, int[] types, Function<B, ?>[] rightMappings) {
                        super(rightMappings, types);
                        this.leftMappings = (Function<A, Object>[]) java.util.Objects.requireNonNull(leftMappings);
                    }
                    public Function<A, Object> getLeftMapping(int index) { return leftMappings[index]; }
                    public Joiner<A, B> and(Joiner<A, B> other) {
                        int n = count();
                        int m = other.count();
                        var newTypes = java.util.Arrays.copyOf(this.types, n + m);
                        var newLeft = java.util.Arrays.copyOf(this.leftMappings, n + m);
                        var newRight = java.util.Arrays.copyOf(this.rightMappings, n + m);
                        for (int i = 0; i < m; i++) {
                            newTypes[n + i] = other.types[i];
                            newLeft[n + i] = other.getLeftMapping(i);
                            newRight[n + i] = other.getRightMapping(i);
                        }
                        return new Joiner<>(newLeft, newTypes, newRight);
                    }
                    @SuppressWarnings("unchecked")
                    public Joiner<A, B> reordered(int[] order) {
                        var newLeft = new Function[order.length];
                        var newTypes = new int[order.length];
                        var newRight = new Function[order.length];
                        for (int i = 0; i < order.length; i++) {
                            newLeft[i] = leftMappings[order[i]];
                            newTypes[i] = types[order[i]];
                            newRight[i] = rightMappings[order[i]];
                        }
                        return new Joiner<>(newLeft, newTypes, newRight);
                    }
                    public boolean matches(A a, B b) {
                        for (int i = 0; i < count(); i++) {
                            if (!java.util.Objects.equals(getLeftMapping(i).apply(a), getRightMapping(i).apply(b))) return false;
                        }
                        return true;
                    }
                }
                public static <A, P> Joiner<A, A> equal(Function<A, P> mapping) {
                    return equal(mapping, mapping);
                }
                public static <A, B, P> Joiner<A, B> equal(Function<A, P> leftMapping, Function<B, P> rightMapping) {
                    return new Joiner<>(leftMapping, 0, rightMapping);
                }
                // timefold ReflectionHelper.getDeclaredField: the body does handle null, the control
                public static Class<?> top(Class<?> containingClass) {
                    Class<?> base = containingClass;
                    Class<?> last = null;
                    while (base != null) {
                        last = base;
                        base = base.getSuperclass();
                    }
                    return last;
                }
            }
            """;

    @DisplayName("#68: a demand in an enclosed class or lambda, a bound method reference, a filled array (timefold, langchain4j C#)")
    @Test
    public void toleratedNullsMissedDemands() {
        Report report = run("a.b.TD", TOLERANT_DEMANDS, Policy.NULL_MARKED_FLOW_ONLY);
        System.out.println(explain(report));
        Map<String, String> v = byName(report);
        // the consumer is called in the anonymous class that captures it, or in the lambda
        assertEquals("Consumer<String>", v.get("a.b.TD.onPartial(java.util.function.Consumer):0:consumer"));
        assertEquals("Consumer<String>", v.get("a.b.TD.viaLambda(java.util.function.Consumer):0:consumer"));
        // 'director::before' throws on a null director
        assertEquals("Director", v.get("a.b.TD.of(a.b.TD.Director):0:director"));
        // the mappings go into arrays filled before anything reads them: left directly, right through this(..)
        assertEquals("Function<A!, P!>", v.get("a.b.TD.equal(java.util.function.Function):0:mapping"));
        assertEquals("Function<A!, P!>",
                v.get("a.b.TD.equal(java.util.function.Function,java.util.function.Function):0:leftMapping"));
        assertEquals("Function<B!, P!>",
                v.get("a.b.TD.equal(java.util.function.Function,java.util.function.Function):1:rightMapping"));
        assertEquals("Function<A!, Object>[]", v.get("a.b.TD.Joiner.leftMappings"));
        // the control: the body does handle null
        assertEquals("Class<?!>?", v.get("a.b.TD.top(Class):0:containingClass"));

        // each gate brings back the tolerance it closes
        Map<String, String> gates = Map.of(
                "NOENCLOSEDDEMANDS", "a.b.TD.onPartial(java.util.function.Consumer):0:consumer",
                "NOREFERENCEDEMANDS", "a.b.TD.of(a.b.TD.Director):0:director",
                "NOSIBLINGFILLS", "a.b.TD.equal(java.util.function.Function,java.util.function.Function):0:leftMapping",
                "NOINITIALIZERARGUMENTS", "a.b.TD.equal(java.util.function.Function,java.util.function.Function):1:rightMapping");
        gates.forEach((gate, key) -> {
            Switches.offForNextPasses(Set.of(gate));
            try {
                Map<String, String> off = byName(new NullabilityPass(Policy.NULL_MARKED_FLOW_ONLY)
                        .go(analysisOrder));
                assertTrue(off.get(key).endsWith("?"), gate + ": " + off.get(key));
            } finally {
                Switches.offForNextPasses(Set.of());
            }
        });
    }

    @Language("java")
    private static final String TOLERANT_HIDDEN = """
            package a.b;
            class TH {
                public static void unused(String s) { }
            }
            """;

    @DisplayName("#68: a public method of a type not visible from outside keeps the closed-world verdict")
    @Test
    public void toleratedNullsNeedVisibility() {
        Map<String, String> v = byName(run("a.b.TH", TOLERANT_HIDDEN));
        assertEquals("String", v.get("a.b.TH.unused(String):0:s"));
    }
}
