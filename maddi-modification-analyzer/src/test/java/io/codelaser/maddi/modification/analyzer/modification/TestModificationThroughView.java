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

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CodeLaser/maddi-mod#54: a removal through an iterator of a view of a parameter (guava's
 * {@code Maps.FilteredEntryMap.removeAllKeys}: {@code map.entrySet().iterator()}, then {@code entryItr.remove()})
 * modifies the parameter, structurally. Both {@code UNMODIFIED_PARAMETER} and
 * {@code STRUCTURALLY_UNMODIFIED_PARAMETER} must say so.
 */
public class TestModificationThroughView extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.Collection;
            import java.util.Iterator;
            import java.util.List;
            import java.util.ListIterator;
            import java.util.Map;
            import java.util.function.Predicate;
            class Views {
                // guava's Maps.FilteredEntryMap.removeAllKeys
                static <K, V> boolean removeAllKeys(Map<K, V> map, Predicate<? super Map.Entry<K, V>> entryPredicate,
                                                    Collection<?> keyCollection) {
                    Iterator<Map.Entry<K, V>> entryItr = map.entrySet().iterator();
                    boolean result = false;
                    while (entryItr.hasNext()) {
                        Map.Entry<K, V> entry = entryItr.next();
                        if (entryPredicate.test(entry) && keyCollection.contains(entry.getKey())) {
                            entryItr.remove();
                            result = true;
                        }
                    }
                    return result;
                }
                static <K, V> void removeKeys(Map<K, V> map) {
                    Iterator<K> it = map.keySet().iterator();
                    while (it.hasNext()) { if (it.next() == null) it.remove(); }
                }
                static <K, V> void removeValues(Map<K, V> map) {
                    Iterator<V> it = map.values().iterator();
                    while (it.hasNext()) { if (it.next() == null) it.remove(); }
                }
                static <T> void removeDirect(Collection<T> c) {
                    Iterator<T> it = c.iterator();
                    while (it.hasNext()) { if (it.next() == null) it.remove(); }
                }
                static <T> void setThroughListIterator(List<T> list, T t) {
                    ListIterator<T> it = list.listIterator();
                    while (it.hasNext()) { it.next(); it.set(t); }
                }
                static <T> void clearSubList(List<T> list) {
                    list.subList(0, 1).clear();
                }
                static <K, V> void clearValues(Map<K, V> map) {
                    map.values().clear();
                }
                static <K, V> void clearValuesLocal(Map<K, V> map) {
                    java.util.Collection<V> vs = map.values();
                    vs.clear();
                }
                static <T> void removeThroughSubListIterator(List<T> list) {
                    Iterator<T> it = list.subList(0, 2).iterator();
                    it.next();
                    it.remove();
                }
                static <K, V> void removeThroughEntrySetLocal(Map<K, V> map) {
                    java.util.Set<Map.Entry<K, V>> es = map.entrySet();
                    Iterator<Map.Entry<K, V>> it = es.iterator();
                    it.next();
                    it.remove();
                }
                // control: only reads through the view
                static <K, V> int countKeys(Map<K, V> map) {
                    int n = 0;
                    Iterator<Map.Entry<K, V>> it = map.entrySet().iterator();
                    while (it.hasNext()) { it.next(); n++; }
                    return n;
                }
                // a ListIterator writes through with remove, set and add only; a descending iterator with remove
                static <T> void addThroughListIterator(List<T> list, T t) {
                    ListIterator<T> it = list.listIterator();
                    it.add(t);
                }
                static <T> void removeDescending(java.util.Deque<T> deque) {
                    Iterator<T> it = deque.descendingIterator();
                    it.next();
                    it.remove();
                }
                static <T> int countThroughListIterator(List<T> list) {
                    int n = 0;
                    ListIterator<T> it = list.listIterator();
                    while (it.hasNext()) { it.next(); n++; }
                    return n;
                }
                static <T> int countDescending(java.util.Deque<T> deque) {
                    int n = 0;
                    Iterator<T> it = deque.descendingIterator();
                    while (it.hasNext()) { it.next(); n++; }
                    return n;
                }
                static <T> int countDescendingSet(java.util.NavigableSet<T> set) {
                    int n = 0;
                    Iterator<T> it = set.descendingIterator();
                    while (it.hasNext()) { it.next(); n++; }
                    return n;
                }
            }
            """;

    private static MethodInfo method(TypeInfo typeInfo, String name) {
        return typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    /** "unmodified/structurallyUnmodified" of the first parameter */
    private static String p(MethodInfo mi) {
        ParameterInfo pi = mi.parameters().getFirst();
        return pi.isUnmodified() + "/" + pi.isStructurallyUnmodified();
    }

    @DisplayName("removal, replacement and addition through a view's iterator modify the parameter, structurally; reading does not")
    @Test
    public void test() {
        TypeInfo views = javaInspector.parse("a.b.Views", INPUT);
        List<Info> ao = prepWork(views);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).build()).analyze(ao);
        StringBuilder sb = new StringBuilder();
        for (String name : List.of("removeAllKeys", "removeKeys", "removeValues", "removeDirect",
                "setThroughListIterator", "clearSubList", "clearValues", "clearValuesLocal",
                "removeThroughSubListIterator", "removeThroughEntrySetLocal", "countKeys", "addThroughListIterator",
                "removeDescending", "countThroughListIterator", "countDescending", "countDescendingSet")) {
            sb.append(name).append('=').append(p(method(views, name))).append('\n');
        }
        assertEquals("""
                removeAllKeys=false/false
                removeKeys=false/false
                removeValues=false/false
                removeDirect=false/false
                setThroughListIterator=false/false
                clearSubList=false/false
                clearValues=false/false
                clearValuesLocal=false/false
                removeThroughSubListIterator=false/false
                removeThroughEntrySetLocal=false/false
                countKeys=true/true
                addThroughListIterator=false/false
                removeDescending=false/false
                countThroughListIterator=true/true
                countDescending=true/true
                countDescendingSet=true/true
                """, sb.toString());
    }
}
