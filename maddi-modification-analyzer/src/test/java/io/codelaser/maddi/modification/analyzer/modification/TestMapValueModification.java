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
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CodeLaser/maddi-mod#59: modifying a value taken out of a map field ({@code products.get(sku).put(...)}) modifies the
 * field's content, so the method is modifying (deep) but structurally non-modifying. The reachability pass skipped
 * the summary's receiver-rooted deep-only entry of a walkable body, and its walk does not follow the element link
 * {@code p ∈ this.products}: it wrote the deep verdict TRUE.
 */
public class TestMapValueModification extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.HashMap;
            import java.util.List;
            import java.util.Map;
            class Catalog {
                private final Map<String, Map<String, Object>> products = new HashMap<>();
                private final Map<String, List<String>> tags = new HashMap<>();
                public void deactivate(String sku) {
                    Map<String, Object> p = products.get(sku);
                    if (p != null) p.put("active", false);
                }
                public void deactivateDirect(String sku) {
                    products.get(sku).put("active", false);
                }
                public void tag(String sku, String tag) {
                    List<String> l = tags.get(sku);
                    if (l != null) l.add(tag);
                }
                public void put(String sku) {
                    products.put(sku, new HashMap<>());
                }
            }
            """;

    private static String m(TypeInfo typeInfo, String name) {
        MethodInfo mi = typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
        return mi.isNonModifying() + "/" + mi.isStructurallyNonModifying();
    }

    @DisplayName("the fixpoint's verdicts")
    @Test
    public void fixpoint() {
        analyze(false);
    }

    @DisplayName("the reachability pass, the CLI's default, keeps them")
    @Test
    public void reachability() {
        analyze(true);
    }

    private void analyze(boolean modReach) {
        TypeInfo c = javaInspector.parse("a.b.Catalog", INPUT);
        List<Info> ao = prepWork(c);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(modReach).build()).analyze(ao);
        // deep / structural
        assertEquals("false/true", m(c, "deactivate"));
        assertEquals("false/true", m(c, "deactivateDirect"));
        assertEquals("false/true", m(c, "tag"));
        assertEquals("false/false", m(c, "put"));
    }
}
