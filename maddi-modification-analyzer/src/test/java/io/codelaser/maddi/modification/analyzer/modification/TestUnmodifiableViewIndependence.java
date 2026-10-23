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
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CodeLaser/maddi-mod#60: a read-only view of a mutable field is unmodifiable, but dependent: the object modifies the
 * field, the view shows it. A copy is independent.
 */
public class TestUnmodifiableViewIndependence extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.Collections;
            import java.util.List;
            class Log {
                private final List<String> entries = new ArrayList<>();
                private final List<StringBuilder> builders = new ArrayList<>();
                public void add(String e) { entries.add(e); }
                public List<String> view() { return Collections.unmodifiableList(entries); }
                public List<String> synchronizedView() { return Collections.synchronizedList(entries); }
                public List<String> direct() { return entries; }
                public List<String> copy() { return new ArrayList<>(entries); }
                public List<String> listCopyOf() { return List.copyOf(entries); }
                public List<StringBuilder> builderView() { return Collections.unmodifiableList(builders); }
            }
            """;

    private static String independent(TypeInfo typeInfo, String name) {
        MethodInfo mi = typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
        return mi.analysis().getOrDefault(PropertyImpl.INDEPENDENT_METHOD, ValueImpl.IndependentImpl.DEPENDENT)
                .toString();
    }

    @DisplayName("a view of a mutable field is dependent, a copy independent")
    @Test
    public void test() {
        TypeInfo log = javaInspector.parse("a.b.Log", INPUT);
        List<Info> ao = prepWork(log);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(true).build()).analyze(ao);
        String dependent = ValueImpl.IndependentImpl.DEPENDENT.toString();
        String independent = ValueImpl.IndependentImpl.INDEPENDENT.toString();
        assertEquals(dependent, independent(log, "direct"));
        assertEquals(dependent, independent(log, "view"));
        assertEquals(dependent, independent(log, "synchronizedView"));
        assertEquals(dependent, independent(log, "builderView"));
        assertEquals(independent, independent(log, "copy"));
        assertEquals(independent, independent(log, "listCopyOf"));
    }
}
