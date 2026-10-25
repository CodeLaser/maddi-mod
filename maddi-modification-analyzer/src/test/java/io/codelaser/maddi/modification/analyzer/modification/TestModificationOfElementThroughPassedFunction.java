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
import io.codelaser.maddi.cst.api.info.ParameterInfo;
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
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An element of a parameter handed to one of the method's functional parameters (CodeLaser/maddi-mod#67, option b),
 * or the parameter itself (#69):
 * the container stays unmodified, and the parameter records the function in
 * {@link PropertyImpl#MODIFIED_THROUGH_PASSED_FUNCTION}; a caller resolves the entry with the function it passes. The
 * element is read by {@code List.get}, {@code Optional.get}, a for-each, or a {@code Val}-style getter (vavr's
 * {@code Value}, reduced); the verdict is the same whatever the container, the call shape, or the mode.
 */
public class TestModificationOfElementThroughPassedFunction extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import io.codelaser.maddi.annotation.NotModified;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Optional;
            import java.util.function.Consumer;
            import java.util.function.Function;

            public class X {
                interface Val<T> extends Iterable<T> {
                    @NotModified
                    T get();
                }

                static <T, R> R listElement(List<T> list, Function<T, R> f) {
                    return f.apply(list.get(0));
                }

                static <T, R> R optionalElement(Optional<T> opt, Function<T, R> f) {
                    return f.apply(opt.get());
                }

                static <T, R> R valElement(Val<T> val, Function<T, R> f) {
                    return f.apply(val.get());
                }

                static <T> void eachElement(List<T> list, Consumer<T> c) {
                    for (T t : list) c.accept(t);
                }

                static <T, R> R whole(List<T> list, Function<List<T>, R> f) {
                    return f.apply(list);
                }

                static <T> int callerListElement(List<T> list) {
                    return listElement(list, Object::hashCode);
                }

                static boolean callerListElementAdds(List<List<String>> list) {
                    return listElement(list, e -> e.add("x"));
                }

                static <T> void callerEachElement(List<T> list) {
                    eachElement(list, Object::hashCode);
                }

                static void callerEachElementAdds(List<List<String>> list) {
                    eachElement(list, e -> e.add("x"));
                }

                static <T> int callerOptionalElement(Optional<T> opt) {
                    return optionalElement(opt, Object::hashCode);
                }

                static boolean callerOptionalElementAdds(Optional<List<String>> opt) {
                    return optionalElement(opt, e -> e.add("x"));
                }

                static <T, R> R forwardListElement(List<T> list, Function<T, R> f) {
                    return listElement(list, f);
                }

                static <T> int callerWhole(List<T> list) {
                    return whole(list, List::size);
                }

                static boolean callerWholeAdds(List<String> list) {
                    return whole(list, l -> l.add("x"));
                }
            }
            """;

    private static final List<String> METHODS = List.of("listElement", "optionalElement", "valElement",
            "eachElement", "whole", "callerListElement", "callerListElementAdds", "callerEachElement",
            "callerEachElementAdds", "callerOptionalElement", "callerOptionalElementAdds", "forwardListElement",
            "callerWhole", "callerWholeAdds");

    /*
     first parameter: deep unmodified / structurally unmodified / through-set
     */
    private static final Map<String, String> EXPECTED = new TreeMap<>(Map.ofEntries(
            Map.entry("listElement", "unmodified/unmodified/[1:0:e]"),
            Map.entry("optionalElement", "unmodified/unmodified/[1:0:e]"),
            Map.entry("valElement", "unmodified/unmodified/[1:0:e]"),
            Map.entry("eachElement", "unmodified/unmodified/[1:0:e]"),
            // the whole parameter, likewise (CodeLaser/maddi-mod#69): that f is applied is recorded on f itself
            Map.entry("whole", "unmodified/unmodified/[1:0]"),
            Map.entry("callerWhole", "unmodified/unmodified/[]"),
            Map.entry("callerWholeAdds", "modified/modified/[]"),
            Map.entry("callerListElement", "unmodified/unmodified/[]"),
            Map.entry("callerListElementAdds", "modified/unmodified/[]"),
            Map.entry("callerEachElement", "unmodified/unmodified/[]"),
            Map.entry("callerEachElementAdds", "modified/unmodified/[]"),
            Map.entry("callerOptionalElement", "unmodified/unmodified/[]"),
            Map.entry("callerOptionalElementAdds", "modified/unmodified/[]"),
            // the caller's own function, unknown here: its entry is the caller's, for ITS callers to resolve
            Map.entry("forwardListElement", "unmodified/unmodified/[1:0:e]")));

    @DisplayName("an element handed to a passed function: the container unmodified, the function recorded")
    @Test
    public void test() throws IOException {
        assertEquals(EXPECTED, run(false));
    }

    @DisplayName("... and the same with the MODREACH cutover on")
    @Test
    public void testModReach() throws IOException {
        assertEquals(EXPECTED, run(true));
    }

    private Map<String, String> run(boolean modReach) throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo x = bundle.javaInspector().parse("a.b.X", INPUT);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(x);
        IteratingAnalyzer analyzer = new IteratingAnalyzerImpl(bundle.javaInspector(),
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(10)
                        .setModificationViaReachability(modReach).build());
        analyzer.analyze(analysisOrder);
        Map<String, String> actual = new TreeMap<>();
        for (String m : METHODS) {
            ParameterInfo pi = x.methods().stream().filter(mi -> mi.name().equals(m)).findFirst().orElseThrow()
                    .parameters().getFirst();
            actual.put(m, (pi.isModified() ? "modified" : "unmodified") + "/"
                          + (pi.isStructurallyUnmodified() ? "unmodified" : "modified") + "/"
                          + pi.analysis().getOrDefault(PropertyImpl.MODIFIED_THROUGH_PASSED_FUNCTION,
                    ValueImpl.SetOfStringsImpl.EMPTY_SET).set().stream().sorted().toList()
                                  .toString().replace(" ", ""));
        }
        System.out.println("### modReach=" + modReach + " " + actual);
        return actual;
    }
}
