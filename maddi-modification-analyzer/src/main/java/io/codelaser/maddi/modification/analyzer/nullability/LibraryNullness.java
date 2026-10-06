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

package io.codelaser.maddi.modification.analyzer.nullability;

import io.codelaser.maddi.cst.api.info.MethodInfo;

import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The null contracts of JDK methods the pass cannot see into: which return null, which accept null. A STOPGAP for
 * docs/design/nullability.md M2, where these belong in the analysis hints; today the hints only record
 * {@code @NotNull}, and the absence of a property is NULLABLE by default for every method, so "may return null" and
 * "nobody said" cannot be told apart there. Measured need (guava oracle, 2026-10-06): 100 unsafe returns were
 * overrides of or delegations to these methods, and most of 206 unsafe parameters were the {@code Object} parameters
 * of the collection contracts.
 * <p>
 * A method matches when it, or a method it overrides, is listed: key {@code ownerFqn.name/arity}.
 */
final class LibraryNullness {

    private static final Set<String> NULLABLE_RETURNS = Set.of(
            "java.util.Map.get/1", "java.util.Map.put/2", "java.util.Map.remove/1", "java.util.Map.putIfAbsent/2",
            "java.util.Map.replace/2", "java.util.Map.compute/2", "java.util.Map.computeIfPresent/2",
            "java.util.Map.merge/3",
            "java.util.NavigableMap.lowerEntry/1", "java.util.NavigableMap.lowerKey/1",
            "java.util.NavigableMap.floorEntry/1", "java.util.NavigableMap.floorKey/1",
            "java.util.NavigableMap.ceilingEntry/1", "java.util.NavigableMap.ceilingKey/1",
            "java.util.NavigableMap.higherEntry/1", "java.util.NavigableMap.higherKey/1",
            "java.util.NavigableMap.firstEntry/0", "java.util.NavigableMap.lastEntry/0",
            "java.util.NavigableMap.pollFirstEntry/0", "java.util.NavigableMap.pollLastEntry/0",
            "java.util.NavigableSet.lower/1", "java.util.NavigableSet.floor/1", "java.util.NavigableSet.ceiling/1",
            "java.util.NavigableSet.higher/1", "java.util.NavigableSet.pollFirst/0", "java.util.NavigableSet.pollLast/0",
            "java.util.SortedMap.comparator/0", "java.util.SortedSet.comparator/0",
            "java.util.PriorityQueue.comparator/0", "java.util.concurrent.PriorityBlockingQueue.comparator/0",
            "java.util.Queue.poll/0", "java.util.Queue.peek/0",
            "java.util.Deque.pollFirst/0", "java.util.Deque.pollLast/0",
            "java.util.Deque.peekFirst/0", "java.util.Deque.peekLast/0",
            "java.util.concurrent.BlockingQueue.poll/2", "java.util.concurrent.BlockingDeque.pollFirst/2",
            "java.util.concurrent.BlockingDeque.pollLast/2",
            "java.lang.ref.Reference.get/0", "java.lang.ThreadLocal.get/0",
            "java.lang.System.getProperty/1", "java.lang.System.getenv/1",
            "java.lang.Class.getClassLoader/0", "java.lang.Class.getSuperclass/0",
            "java.lang.Class.getComponentType/0", "java.lang.Class.getEnclosingClass/0",
            "java.lang.Class.getDeclaringClass/0", "java.lang.Class.getPackage/0",
            "java.lang.Throwable.getCause/0", "java.lang.Throwable.getMessage/0",
            "java.lang.Throwable.getLocalizedMessage/0",
            "java.io.BufferedReader.readLine/0", "java.io.DataInput.readLine/0",
            "java.lang.reflect.InvocationHandler.invoke/3");

    // the parameter indices that accept null by contract
    private static final Map<String, Set<Integer>> NULLABLE_PARAMETERS = Map.ofEntries(
            Map.entry("java.lang.Object.equals/1", Set.of(0)),
            Map.entry("java.util.Collection.contains/1", Set.of(0)),
            Map.entry("java.util.Collection.remove/1", Set.of(0)),
            Map.entry("java.util.List.indexOf/1", Set.of(0)),
            Map.entry("java.util.List.lastIndexOf/1", Set.of(0)),
            Map.entry("java.util.Map.get/1", Set.of(0)),
            Map.entry("java.util.Map.containsKey/1", Set.of(0)),
            Map.entry("java.util.Map.containsValue/1", Set.of(0)),
            Map.entry("java.util.Map.remove/1", Set.of(0)),
            Map.entry("java.util.Map.getOrDefault/2", Set.of(0)),
            Map.entry("java.util.Map.remove/2", Set.of(0, 1)));

    private LibraryNullness() {
    }

    static String key(MethodInfo mi) {
        return mi.typeInfo().fullyQualifiedName() + "." + mi.name() + "/" + mi.parameters().size();
    }

    // the method and the methods it overrides
    private static Stream<MethodInfo> withOverridden(MethodInfo mi) {
        return Stream.concat(Stream.of(mi), mi.overrides().stream());
    }

    /** The listed method whose return {@code mi} shares, or null. */
    static String nullableReturn(MethodInfo mi) {
        return withOverridden(mi).map(LibraryNullness::key).filter(NULLABLE_RETURNS::contains).findFirst()
                .orElse(null);
    }

    /** The listed method by whose contract parameter {@code index} of {@code mi} accepts null, or null. */
    static String nullableParameter(MethodInfo mi, int index) {
        return withOverridden(mi).map(LibraryNullness::key)
                .filter(k -> NULLABLE_PARAMETERS.getOrDefault(k, Set.of()).contains(index))
                .findFirst().orElse(null);
    }
}
