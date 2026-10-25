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

package io.codelaser.maddi.modification.link.io;

import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import io.codelaser.maddi.modification.common.CommonTest;
import io.codelaser.maddi.modification.prepwork.io.LoadAnalysisResults;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.PriorityQueue;
import java.util.SequencedCollection;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.Stack;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Vector;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import static io.codelaser.maddi.modification.prepwork.io.LoadAnalysisResults.ANALYZED_RESULTS_JDK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JDK hints' modifying methods of the collection interfaces, against a yardstick the JDK itself provides: an
 * unmodifiable instance throws {@link UnsupportedOperationException} on exactly the methods that change the
 * collection. The verdict checked is the one the engine uses, {@link MethodInfo#isModifying()} after loading the JDK
 * hints the way the Java → Kotlin runs do, defaults included -- not the hint files' text.
 * <p>
 * Kotlin draws the same line with its read-only types: kotlinc 2.4.0 accepts a call on {@code List},
 * {@code Map}, {@code Map.Entry}, ... exactly where this test's run does not throw, on all 116 methods both judge
 * (measured 2026-10-25; the rest are members Kotlin hides, {@code toArray}, {@code getFirst}, or deprecates). So a
 * hint that disagrees here also prints the wrong Kotlin type.
 * <p>
 * An iterator's {@code next()} moves its cursor, and maddi counts that as modifying the iterator; the yardstick asks
 * only about the collection. For {@link Iterator} and {@link ListIterator} the check therefore goes one way: what
 * throws must be modifying.
 * <p>
 * The concrete classes have no unmodifiable variant, but they keep their interfaces' contracts: an {@code ArrayList}
 * used as a {@code List} must modify exactly where {@code List} does. {@link #classesFollowTheirInterfaces()} holds
 * every public method of the common {@code java.util} collections to the interface method it implements.
 */
public class TestJdkCollectionMutators {

    private record Interface(Class<?> type, boolean bothWays, Supplier<Object> unmodifiable) {
    }

    private static final List<Interface> INTERFACES = List.of(
            new Interface(Iterable.class, true, () -> Collections.unmodifiableList(new ArrayList<>(List.of("a", "b")))),
            new Interface(Collection.class, true,
                    () -> Collections.unmodifiableCollection(new ArrayList<>(List.of("a", "b")))),
            new Interface(List.class, true, () -> Collections.unmodifiableList(new ArrayList<>(List.of("a", "b")))),
            new Interface(Set.class, true, () -> Collections.unmodifiableSet(new LinkedHashSet<>(List.of("a", "b")))),
            new Interface(Map.class, true, () -> Collections.unmodifiableMap(new LinkedHashMap<>(Map.of("a", "b")))),
            new Interface(SequencedCollection.class, true,
                    () -> Collections.unmodifiableSequencedCollection(new ArrayList<>(List.of("a", "b")))),
            new Interface(SequencedSet.class, true,
                    () -> Collections.unmodifiableSequencedSet(new LinkedHashSet<>(List.of("a", "b")))),
            new Interface(SortedSet.class, true, () -> Collections.unmodifiableSortedSet(new TreeSet<>(List.of("a", "b")))),
            new Interface(NavigableSet.class, true,
                    () -> Collections.unmodifiableNavigableSet(new TreeSet<>(List.of("a", "b")))),
            new Interface(SequencedMap.class, true,
                    () -> Collections.unmodifiableSequencedMap(new LinkedHashMap<>(Map.of("a", "b")))),
            new Interface(SortedMap.class, true, () -> Collections.unmodifiableSortedMap(new TreeMap<>(Map.of("a", "b")))),
            new Interface(NavigableMap.class, true,
                    () -> Collections.unmodifiableNavigableMap(new TreeMap<>(Map.of("a", "b")))),
            new Interface(Map.Entry.class, true,
                    () -> Collections.unmodifiableMap(new LinkedHashMap<>(Map.of("a", "b"))).entrySet().iterator().next()),
            new Interface(Iterator.class, false, () -> {
                Iterator<String> it = Collections.unmodifiableList(new ArrayList<>(List.of("a", "b"))).iterator();
                it.next();
                return it;
            }),
            new Interface(ListIterator.class, false, () -> {
                ListIterator<String> it = Collections.unmodifiableList(new ArrayList<>(List.of("a", "b"))).listIterator();
                it.next();
                return it;
            }));

    private static final List<Class<?>> CLASSES = List.of(ArrayList.class, LinkedList.class, Vector.class,
            Stack.class, ArrayDeque.class, PriorityQueue.class, HashSet.class, LinkedHashSet.class, TreeSet.class,
            HashMap.class, LinkedHashMap.class, TreeMap.class, Hashtable.class, IdentityHashMap.class,
            WeakHashMap.class, EnumMap.class);

    private static JavaInspector javaInspector;

    @BeforeAll
    public static void beforeAll() throws IOException {
        SourceSet sourceSet = SourceSetImpl.testProtocolSourceSet();
        javaInspector = CommonTest.javaInspectorFactory().withSources(sourceSet);
        javaInspector.onlyPreload();
        new LoadAnalysisResults(javaInspector.runtime(), sourceSet).go(List.of(ANALYZED_RESULTS_JDK));
    }

    @Test
    public void test() {

        List<String> wrong = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        int checked = 0;
        for (Interface i : INTERFACES) {
            Set<String> seen = new HashSet<>();
            Method[] methods = i.type().getMethods();
            Arrays.sort(methods, Comparator.comparing(Method::toString));
            for (Method m : methods) {
                if (Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() == Object.class) continue;
                String signature = signature(m);
                if (!seen.add(signature)) continue;
                MethodInfo methodInfo = find(javaInspector, m);
                String where = i.type().getSimpleName() + "." + signature;
                if (methodInfo == null) {
                    notFound.add(where + " (declared in " + m.getDeclaringClass().getSimpleName() + ")");
                    continue;
                }
                boolean mutator = throwsUnsupported(i, m);
                boolean modifying = methodInfo.isModifying();
                checked++;
                if (mutator && !modifying || i.bothWays() && !mutator && modifying) {
                    wrong.add(where + ": the JDK says " + (mutator ? "mutator" : "read") + ", the hints "
                              + (modifying ? "modifying" : "non-modifying"));
                }
            }
        }
        assertEquals(List.of(), notFound, "methods the CST does not have");
        assertTrue(checked > 100, "checked only " + checked);
        assertEquals("", String.join("\n", wrong));
    }

    @Test
    public void classesFollowTheirInterfaces() {
        List<String> wrong = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        int checked = 0;
        for (Class<?> c : CLASSES) {
            Method[] methods = c.getMethods();
            Arrays.sort(methods, Comparator.comparing(Method::toString));
            for (Method m : methods) {
                if (Modifier.isStatic(m.getModifiers()) || m.isBridge() || m.getDeclaringClass().isInterface()
                    || m.getDeclaringClass() == Object.class) continue;
                Method implemented = implemented(c, m);
                if (implemented == null) continue;
                MethodInfo own = find(javaInspector, m);
                MethodInfo contract = find(javaInspector, implemented);
                String where = c.getSimpleName() + "." + signature(m);
                if (own == null || contract == null) {
                    notFound.add(where);
                    continue;
                }
                checked++;
                if (own.isModifying() != contract.isModifying()) {
                    wrong.add(where + " (declared in " + m.getDeclaringClass().getSimpleName() + "): "
                              + (own.isModifying() ? "modifying" : "non-modifying") + ", but "
                              + implemented.getDeclaringClass().getSimpleName() + "." + signature(implemented)
                              + " is " + (contract.isModifying() ? "modifying" : "non-modifying"));
                }
            }
        }
        assertEquals(List.of(), notFound, "methods the CST does not have");
        assertTrue(checked > 200, "checked only " + checked);
        assertEquals("", String.join("\n", wrong));
    }

    /** The method of one of {@link #INTERFACES} that {@code m} implements, as declared there; null if none. */
    private static Method implemented(Class<?> c, Method m) {
        for (Interface i : INTERFACES) {
            if (!i.type().isAssignableFrom(c)) continue;
            try {
                Method im = i.type().getMethod(m.getName(), m.getParameterTypes());
                if (!Modifier.isStatic(im.getModifiers()) && im.getDeclaringClass() != Object.class) return im;
            } catch (NoSuchMethodException _) {
                // not a member of this interface
            }
        }
        return null;
    }

    private static String signature(Method m) {
        return m.getName() + Arrays.stream(m.getParameterTypes()).map(Class::getTypeName)
                .collect(java.util.stream.Collectors.joining(",", "(", ")"));
    }

    private static MethodInfo find(JavaInspector javaInspector, Method m) {
        TypeInfo typeInfo = typeInfo(javaInspector, m.getDeclaringClass());
        if (typeInfo == null) return null;
        List<String> wanted = Arrays.stream(m.getParameterTypes()).map(c -> c.getTypeName().replace('$', '.')).toList();
        return typeInfo.methods().stream()
                .filter(mi -> mi.name().equals(m.getName()))
                .filter(mi -> mi.parameters().stream().map(ParameterInfo::parameterizedType)
                        .map(TestJdkCollectionMutators::erasure).toList().equals(wanted))
                .findFirst().orElse(null);
    }

    private static TypeInfo typeInfo(JavaInspector javaInspector, Class<?> clazz) {
        if (clazz.getEnclosingClass() == null) return javaInspector.compiledTypesManager().typeIfLoaded(clazz);
        TypeInfo enclosing = typeInfo(javaInspector, clazz.getEnclosingClass());
        return enclosing == null ? null : enclosing.findSubType(clazz.getSimpleName(), false);
    }

    // the type variables of the collection interfaces are unbounded: they erase to Object
    private static String erasure(ParameterizedType pt) {
        String base = pt.isTypeParameter() ? "java.lang.Object" : pt.typeInfo().fullyQualifiedName();
        return base + "[]".repeat(pt.arrays());
    }

    /** Calls {@code m} on a fresh unmodifiable instance with harmless arguments. */
    private static boolean throwsUnsupported(Interface i, Method m) {
        Object[] args = Arrays.stream(m.getParameterTypes()).map(TestJdkCollectionMutators::argument).toArray();
        try {
            m.invoke(i.unmodifiable().get(), args);
            return false;
        } catch (InvocationTargetException e) {
            return e.getCause() instanceof UnsupportedOperationException;
        } catch (IllegalAccessException e) {
            throw new UnsupportedOperationException(e);
        }
    }

    private static Object argument(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == boolean.class) return false;
        if (type == Object[].class) return new Object[0];
        if (type == Collection.class) return new ArrayList<>(List.of("a"));
        if (type == Map.class) return new HashMap<>(Map.of("a", "b"));
        if (type == Comparator.class) return null;
        if (type.isInterface()) {
            // a functional interface: true, 0, an array of the asked length, or a value
            return Proxy.newProxyInstance(TestJdkCollectionMutators.class.getClassLoader(), new Class<?>[]{type},
                    (_, method, as) -> {
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) return true;
                        if (rt == int.class) return 0;
                        if (rt == void.class) return null;
                        if (as != null && as.length == 1 && as[0] instanceof Integer n) return new Object[n];
                        return "v";
                    });
        }
        return "a";
    }
}
