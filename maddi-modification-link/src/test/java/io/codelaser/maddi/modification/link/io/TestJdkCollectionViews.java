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

package io.codelaser.maddi.modification.link.io;

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import io.codelaser.maddi.modification.common.CommonTest;
import io.codelaser.maddi.modification.prepwork.io.LoadAnalysisResults;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.INDEPENDENT_METHOD;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.INDEPENDENT_PARAMETER;
import static io.codelaser.maddi.modification.prepwork.io.LoadAnalysisResults.ANALYZED_RESULTS_JDK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JDK hints' independence of the {@code java.util} methods that return a view or a copy, against what the JDK
 * does: build a source, call the method, change the source, and look at the result.
 * <ul>
 *     <li>the change shows in the result: DEPENDENT (a view: {@code subList}, {@code keySet},
 *     {@code Collections.unmodifiableList}, {@code Arrays.asList}, ...);</li>
 *     <li>it does not, but the result holds the source's element objects: INDEPENDENT_HC (a copy:
 *     {@code List.copyOf}, {@code new ArrayList<>(c)}, {@code toArray()});</li>
 *     <li>neither: INDEPENDENT.</li>
 * </ul>
 * The verdict checked is the one the engine uses after loading the JDK hints as the Java → Kotlin runs do: for a
 * view of the receiver the method's {@code independentMethod}, for a static or a constructor the parameter's
 * {@code independentParameter}, where a link to the return value (-1) says how the result shares it.
 * CodeLaser/maddi-mod#60 was this test's first shape: {@code Collections.unmodifiable*} read as independent views.
 * <p>
 * An iterator is judged the other way round. The hints say {@code @Independent(hc = true, except = "remove")}
 * ({@code Iterable.iterator()}): it hands out the source's elements, and only the methods in {@code except} write
 * through to the source; {@code next()} moves the iterator, not the iterable. A change of the source while iterating
 * is not modelled: an iterator lives inside one loop, and the JDK enforces that with
 * {@code ConcurrentModificationException}. So for a method that makes an iterator the test calls every method of the
 * iterator on a fresh one, collects those that change the source, and holds that set to the hint's {@code except}
 * ({@link Value.Independent#dependentMethods()}); the level is INDEPENDENT_HC when {@code next()} hands out the
 * source's own element.
 */
public class TestJdkCollectionViews {

    enum Level {DEPENDENT, INDEPENDENT_HC, INDEPENDENT}

    /**
     * One case: the executable whose verdict is checked (-1: the method's own; else that parameter's), the run, and
     * the names of the result's methods that write through to the source (none for a view or a copy).
     */
    private record Case(Executable executable, int parameter, Supplier<Level> run, Supplier<Set<String>> through) {
        Case(Executable executable, int parameter, Supplier<Level> run) {
            this(executable, parameter, run, Set::of);
        }
    }

    @Test
    public void test() throws Exception {
        SourceSet sourceSet = SourceSetImpl.testProtocolSourceSet();
        JavaInspector javaInspector = CommonTest.javaInspectorFactory().withSources(sourceSet);
        javaInspector.onlyPreload();
        new LoadAnalysisResults(javaInspector.runtime(), sourceSet).go(List.of(ANALYZED_RESULTS_JDK));

        List<String> wrong = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        List<Case> cases = cases();
        for (Case c : cases) {
            String where = label(c.executable());
            MethodInfo methodInfo = find(javaInspector, c.executable());
            if (methodInfo == null) {
                notFound.add(where);
                continue;
            }
            Level jdk = c.run().get();
            Set<String> jdkThrough = c.through().get();
            Value.Independent independent = c.parameter() < 0
                    ? methodInfo.analysis().getOrDefault(INDEPENDENT_METHOD, ValueImpl.IndependentImpl.DEPENDENT)
                    : methodInfo.parameters().get(c.parameter()).analysis().getOrDefault(INDEPENDENT_PARAMETER,
                    ValueImpl.IndependentImpl.DEPENDENT);
            Level hints = c.parameter() < 0 ? level(independent) : parameterLevel(independent);
            Set<String> hintsThrough = new TreeSet<>();
            independent.dependentMethods().forEach(mi -> hintsThrough.add(mi.name()));
            if (jdk != hints || !jdkThrough.equals(hintsThrough)) {
                wrong.add(where + ": the JDK says " + jdk + (jdkThrough.isEmpty() ? "" : " except " + jdkThrough)
                          + ", the hints " + hints + (hintsThrough.isEmpty() ? "" : " except " + hintsThrough));
            }
        }
        assertEquals(List.of(), notFound, "executables the CST does not have");
        assertTrue(cases.size() > 50, "only " + cases.size() + " cases");
        assertEquals("", String.join("\n", wrong));
    }

    // ---- the hints' verdict

    private static Level parameterLevel(Value.Independent independent) {
        Integer toReturnValue = independent.linkToParametersReturnValue().get(-1);
        if (toReturnValue != null) return Level.values()[toReturnValue];
        return level(independent);
    }

    private static Level level(Value.Independent independent) {
        if (independent.isDependent()) return Level.DEPENDENT;
        if (independent.isIndependentHc()) return Level.INDEPENDENT_HC;
        return Level.INDEPENDENT;
    }

    // ---- the cases

    private static List<Case> cases() throws Exception {
        List<Case> cases = new ArrayList<>();

        // views of the receiver; a list is changed in place (set), so that its views are not invalidated
        cases.add(receiver(List.class.getMethod("subList", int.class, int.class), List.class,
                l -> ((List<?>) l).subList(0, 1)));
        cases.add(receiver(List.class.getMethod("reversed"), List.class, l -> ((List<?>) l).reversed()));
        cases.add(receiver(List.class.getMethod("toArray"), List.class, l -> ((List<?>) l).toArray()));
        cases.add(receiver(Collection.class.getMethod("toArray"), Collection.class, l -> ((Collection<?>) l).toArray()));
        for (String name : List.of("keySet", "values", "entrySet")) {
            cases.add(receiver(Map.class.getMethod(name), Map.class, m -> invoke(Map.class, name, m)));
        }
        for (String name : List.of("sequencedKeySet", "sequencedValues", "sequencedEntrySet", "reversed")) {
            cases.add(receiver(SequencedMap.class.getMethod(name), SequencedMap.class,
                    m -> invoke(SequencedMap.class, name, m)));
        }
        for (String name : List.of("descendingMap", "navigableKeySet", "descendingKeySet")) {
            cases.add(receiver(NavigableMap.class.getMethod(name), NavigableMap.class,
                    m -> invoke(NavigableMap.class, name, m)));
        }
        cases.add(receiver(SortedMap.class.getMethod("headMap", Object.class), SortedMap.class,
                m -> ((SortedMap<String, ?>) m).headMap("z")));
        cases.add(receiver(SortedMap.class.getMethod("tailMap", Object.class), SortedMap.class,
                m -> ((SortedMap<String, ?>) m).tailMap("")));
        cases.add(receiver(SortedMap.class.getMethod("subMap", Object.class, Object.class), SortedMap.class,
                m -> ((SortedMap<String, ?>) m).subMap("", "z")));
        cases.add(receiver(NavigableMap.class.getMethod("headMap", Object.class, boolean.class), NavigableMap.class,
                m -> ((NavigableMap<String, ?>) m).headMap("z", true)));
        // entries handed out as snapshots: the map changes, the entry does not
        for (Class<?> type : List.of(NavigableMap.class, TreeMap.class)) {
            for (String name : List.of("firstEntry", "lastEntry", "pollFirstEntry", "pollLastEntry")) {
                cases.add(receiver(type.getMethod(name), type, m -> invoke(type, name, m)));
            }
            // each lookup lands on the source's one key, "k"
            for (Map.Entry<String, String> lookup : Map.of("lowerEntry", "l", "floorEntry", "k", "ceilingEntry", "k",
                    "higherEntry", "j").entrySet()) {
                cases.add(receiver(type.getMethod(lookup.getKey(), Object.class), type,
                        m -> invoke(type, lookup.getKey(), m, lookup.getValue())));
            }
        }
        for (String name : List.of("firstEntry", "lastEntry", "pollFirstEntry", "pollLastEntry")) {
            cases.add(receiver(SequencedMap.class.getMethod(name), SequencedMap.class,
                    m -> invoke(SequencedMap.class, name, m)));
        }
        cases.add(receiver(NavigableSet.class.getMethod("descendingSet"), NavigableSet.class,
                s -> ((NavigableSet<?>) s).descendingSet()));
        cases.add(receiver(SortedSet.class.getMethod("headSet", Object.class), SortedSet.class,
                s -> ((SortedSet<StringBuilder>) s).headSet(new StringBuilder("z"))));
        cases.add(receiver(SortedSet.class.getMethod("tailSet", Object.class), SortedSet.class,
                s -> ((SortedSet<StringBuilder>) s).tailSet(new StringBuilder())));

        // statics whose result is built on their first argument
        for (Method m : Collections.class.getMethods()) {
            if (m.getName().startsWith("unmodifiable") || m.getName().startsWith("synchronized")
                || m.getName().startsWith("checked") || m.getName().equals("asLifoQueue")) {
                cases.add(argument(m, 0));
            }
        }
        cases.add(new Case(Collections.class.getMethod("newSetFromMap", Map.class), 0, () -> {
            // the map must be empty when the set is made; it is changed afterwards
            StringBuilder element = new StringBuilder("e");
            Map<StringBuilder, Boolean> map = new HashMap<>();
            Set<StringBuilder> set = Collections.newSetFromMap(map);
            return observe(set, () -> map.put(element, true), element);
        }));
        for (Class<?> c : List.of(List.class, Set.class)) {
            cases.add(argument(c.getMethod("copyOf", Collection.class), 0));
        }
        cases.add(argument(Map.class.getMethod("copyOf", Map.class), 0));
        cases.add(array(Arrays.class.getMethod("asList", Object[].class), a -> Arrays.asList(a)));
        cases.add(array(Arrays.class.getMethod("copyOf", Object[].class, int.class), a -> Arrays.asList(Arrays.copyOf(a, 1))));

        // copy constructors
        for (Class<?> c : List.of(ArrayList.class, LinkedList.class, Vector.class, HashSet.class, LinkedHashSet.class,
                TreeSet.class, ArrayDeque.class, PriorityQueue.class, HashMap.class, LinkedHashMap.class, TreeMap.class,
                Hashtable.class, IdentityHashMap.class, WeakHashMap.class)) {
            for (Constructor<?> k : c.getConstructors()) {
                if (k.getParameterCount() == 1 && SOURCES.containsKey(k.getParameterTypes()[0])) {
                    cases.add(argument(k, 0));
                }
            }
        }
        // what makes an iterator: which of its methods write through to the source
        Supplier<Collection<StringBuilder>> list = () -> new ArrayList<>(List.of(new StringBuilder("e"), new StringBuilder("f")));
        Supplier<Collection<StringBuilder>> set = () -> new LinkedHashSet<>(List.of(new StringBuilder("e"), new StringBuilder("f")));
        Supplier<Collection<StringBuilder>> sorted = () -> new TreeSet<>(List.of(new StringBuilder("e"), new StringBuilder("f")));
        Supplier<Collection<StringBuilder>> deque = () -> new ArrayDeque<>(List.of(new StringBuilder("e"), new StringBuilder("f")));
        for (Class<?> type : List.of(Iterable.class, Collection.class, List.class, ArrayList.class, LinkedList.class,
                Vector.class)) {
            cases.add(iterator(type.getMethod("iterator"), list, c -> c.iterator()));
        }
        for (Class<?> type : List.of(List.class, ArrayList.class, LinkedList.class, Vector.class)) {
            cases.add(iterator(type.getMethod("listIterator"), list, c -> ((List<?>) c).listIterator()));
            cases.add(iterator(type.getMethod("listIterator", int.class), list, c -> ((List<?>) c).listIterator(0)));
        }
        cases.add(iterator(Set.class.getMethod("iterator"), set, c -> c.iterator()));
        cases.add(iterator(TreeSet.class.getMethod("iterator"), sorted, c -> c.iterator()));
        cases.add(iterator(ArrayDeque.class.getMethod("iterator"), deque, c -> c.iterator()));
        for (Class<?> type : List.of(Deque.class, ArrayDeque.class, LinkedList.class)) {
            cases.add(iterator(type.getMethod("descendingIterator"), type == LinkedList.class
                            ? () -> new LinkedList<>(List.of(new StringBuilder("e"), new StringBuilder("f"))) : deque,
                    c -> ((Deque<?>) c).descendingIterator()));
        }
        for (Class<?> type : List.of(NavigableSet.class, TreeSet.class)) {
            cases.add(iterator(type.getMethod("descendingIterator"), sorted, c -> ((NavigableSet<?>) c).descendingIterator()));
        }
        cases.add(iterator(Collections.class.getMethod("enumeration", Collection.class), list,
                c -> Collections.enumeration(c)));
        return cases;
    }

    /** A view or copy of the receiver: the source is the receiver, changed after the call. */
    private static Case receiver(Method method, Class<?> type, Function<Object, Object> call) {
        return new Case(method, -1, () -> {
            StringBuilder element = new StringBuilder("e");
            Object source = SOURCES.get(type).apply(element);
            return observe(call.apply(source), () -> change(source), element);
        });
    }

    /**
     * A method that makes an iterator ({@code Iterator}, {@code ListIterator}, {@code Enumeration}), on the receiver
     * or, for a static, on its first argument. Each of the iterator's methods is called on a fresh one, moved past its
     * first element; those after which the source holds other objects write through.
     */
    private static Case iterator(Method method, Supplier<Collection<StringBuilder>> source,
                                 Function<Collection<StringBuilder>, Object> call) {
        Class<?> iteratorType = method.getReturnType();
        int parameter = java.lang.reflect.Modifier.isStatic(method.getModifiers()) ? 0 : -1;
        Supplier<Level> level = () -> {
            Collection<StringBuilder> s = source.get();
            Object first = advance(call.apply(s));
            return s.stream().anyMatch(o -> o == first) ? Level.INDEPENDENT_HC : Level.INDEPENDENT;
        };
        Supplier<Set<String>> through = () -> {
            Set<String> names = new TreeSet<>();
            for (Method m : iteratorType.getMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() == Object.class) continue;
                Collection<StringBuilder> s = source.get();
                Object it = call.apply(s);
                advance(it);
                List<Object> before = snapshot(s);
                Object[] args = Arrays.stream(m.getParameterTypes()).map(TestJdkCollectionViews::iteratorArgument).toArray();
                try {
                    m.invoke(it, args);
                } catch (ReflectiveOperationException | RuntimeException _) {
                    // an exhausted iterator, or a call the iterator refuses: the source is compared all the same
                }
                if (!sameObjects(before, snapshot(s))) names.add(m.getName());
            }
            return names;
        };
        return new Case(method, parameter, level, through);
    }

    private static Object advance(Object iterator) {
        return iterator instanceof Iterator<?> it ? it.next() : ((Enumeration<?>) iterator).nextElement();
    }

    private static Object iteratorArgument(Class<?> type) {
        if (type == int.class) return 0;
        if (type == java.util.function.Consumer.class) return (java.util.function.Consumer<Object>) _ -> {
        };
        return new StringBuilder("x");
    }

    /** A static or a constructor whose result is built on argument {@code index}: the source is that argument. */
    private static Case argument(Executable executable, int index) {
        Class<?> type = executable.getParameterTypes()[index];
        Function<StringBuilder, Object> factory = SOURCES.get(type);
        if (factory == null) throw new UnsupportedOperationException("no source for " + type + " in " + executable);
        return new Case(executable, index, () -> {
            StringBuilder element = new StringBuilder("e");
            Object source = factory.apply(element);
            Object[] args = new Object[executable.getParameterCount()];
            args[index] = source;
            Class<?>[] types = executable.getParameterTypes();
            for (int i = 0; i < args.length; i++) {
                // checked*: a map's key type is String, every other element or value type Object
                if (i != index) args[i] = types[i] == Class.class
                        ? (i == 1 && Map.class.isAssignableFrom(type) ? String.class : Object.class) : null;
            }
            return observe(construct(executable, args), () -> change(source), element);
        });
    }

    /** A static whose result is built on an array: the array's first slot is replaced after the call. */
    private static Case array(Method method, Function<Object[], Object> call) {
        return new Case(method, 0, () -> {
            StringBuilder element = new StringBuilder("e");
            Object[] source = {element, new StringBuilder("f")};
            return observe(call.apply(source), () -> source[0] = new StringBuilder("g"), element);
        });
    }

    // ---- sources and observation

    // every source holds `element`; sorted ones use String keys and StringBuilder values, or StringBuilder elements
    // (Comparable since Java 11)
    private static final Map<Class<?>, Function<StringBuilder, Object>> SOURCES = Map.ofEntries(
            Map.entry(Collection.class, e -> new ArrayList<>(List.of(e))),
            Map.entry(List.class, e -> new ArrayList<>(List.of(e))),
            Map.entry(SequencedCollection.class, e -> new ArrayList<>(List.of(e))),
            Map.entry(Set.class, e -> new LinkedHashSet<>(List.of(e))),
            Map.entry(SequencedSet.class, e -> new LinkedHashSet<>(List.of(e))),
            Map.entry(SortedSet.class, e -> new TreeSet<>(List.of(e))),
            Map.entry(NavigableSet.class, e -> new TreeSet<>(List.of(e))),
            Map.entry(Queue.class, e -> new ArrayDeque<>(List.of(e))),
            Map.entry(Deque.class, e -> new ArrayDeque<>(List.of(e))),
            Map.entry(Map.class, e -> new LinkedHashMap<>(Map.of("k", e))),
            Map.entry(SequencedMap.class, e -> new LinkedHashMap<>(Map.of("k", e))),
            Map.entry(SortedMap.class, e -> new TreeMap<>(Map.of("k", e))),
            Map.entry(NavigableMap.class, e -> new TreeMap<>(Map.of("k", e))),
            Map.entry(TreeMap.class, e -> new TreeMap<>(Map.of("k", e))),
            Map.entry(PriorityQueue.class, e -> new PriorityQueue<>(List.of(e))));

    /** A change every source takes without invalidating its views: a list in place, the others by adding. */
    @SuppressWarnings("unchecked")
    private static void change(Object source) {
        switch (source) {
            case List<?> list -> ((List<Object>) list).set(0, new StringBuilder("g"));
            case Collection<?> collection -> ((Collection<Object>) collection).add(new StringBuilder("g"));
            case Map<?, ?> map -> ((Map<Object, Object>) map).put("a", new StringBuilder("g"));
            default -> throw new UnsupportedOperationException(source.getClass().toString());
        }
    }

    private static Level observe(Object result, Runnable change, StringBuilder element) {
        List<Object> before = snapshot(result);
        change.run();
        List<Object> after = snapshot(result);
        if (!sameObjects(before, after)) return Level.DEPENDENT;
        return before.stream().anyMatch(o -> o == element) ? Level.INDEPENDENT_HC : Level.INDEPENDENT;
    }

    /** The objects a result holds, in order: elements, or keys and values, or an entry's key and value. */
    private static List<Object> snapshot(Object result) {
        List<Object> out = new ArrayList<>();
        switch (result) {
            case Map<?, ?> map -> map.forEach((k, v) -> {
                out.add(k);
                out.add(v);
            });
            case Map.Entry<?, ?> entry -> {
                out.add(entry.getKey());
                out.add(entry.getValue());
            }
            case Object[] array -> out.addAll(Arrays.asList(array));
            case Iterable<?> iterable -> iterable.forEach(o -> {
                if (o instanceof Map.Entry<?, ?> entry) {
                    out.add(entry.getKey());
                    out.add(entry.getValue());
                } else out.add(o);
            });
            default -> throw new UnsupportedOperationException(result.getClass().toString());
        }
        return out;
    }

    private static boolean sameObjects(List<Object> a, List<Object> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    private static Object invoke(Class<?> type, String name, Object receiver, Object... args) {
        try {
            return type.getMethod(name, Arrays.stream(args).map(_ -> Object.class).toArray(Class<?>[]::new))
                    .invoke(receiver, args);
        } catch (ReflectiveOperationException e) {
            throw new UnsupportedOperationException(e);
        }
    }

    private static Object construct(Executable executable, Object[] args) {
        try {
            return executable instanceof Constructor<?> k ? k.newInstance(args) : ((Method) executable).invoke(null, args);
        } catch (ReflectiveOperationException e) {
            throw new UnsupportedOperationException(executable + ": " + e.getCause(), e);
        }
    }

    // ---- finding the CST's method

    private static String label(Executable e) {
        String name = e instanceof Constructor<?> ? "<init>" : e.getName();
        return e.getDeclaringClass().getSimpleName() + "." + name + Arrays.stream(e.getParameterTypes())
                .map(Class::getSimpleName).collect(java.util.stream.Collectors.joining(",", "(", ")"));
    }

    private static MethodInfo find(JavaInspector javaInspector, Executable e) {
        TypeInfo typeInfo = javaInspector.compiledTypesManager().typeIfLoaded(e.getDeclaringClass());
        if (typeInfo == null) return null;
        List<String> wanted = Arrays.stream(e.getParameterTypes()).map(c -> c.getTypeName().replace('$', '.')).toList();
        var candidates = e instanceof Constructor<?> ? typeInfo.constructors().stream()
                : typeInfo.methods().stream().filter(mi -> mi.name().equals(e.getName()));
        return candidates.filter(mi -> mi.parameters().stream().map(ParameterInfo::parameterizedType)
                        .map(TestJdkCollectionViews::erasure).toList().equals(wanted))
                .findFirst().orElse(null);
    }

    // the type variables of these signatures are unbounded: they erase to Object
    private static String erasure(ParameterizedType pt) {
        String base = pt.isTypeParameter() ? "java.lang.Object" : pt.typeInfo().fullyQualifiedName();
        return base + "[]".repeat(pt.arrays());
    }
}
