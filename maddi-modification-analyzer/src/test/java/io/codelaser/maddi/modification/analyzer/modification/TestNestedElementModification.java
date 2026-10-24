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
 * CodeLaser/maddi-mod#62: a modification reaching a local only through its own element ({@code vlist.get(i).put(..)},
 * a cast {@code Object} value) marked the local modified, but not the field holding the local as an element: the
 * completion of the modified set was written for a ≡-hit only. Deep modifying, structurally not, as #59's one-level
 * {@code deactivate}.
 */
public class TestNestedElementModification extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            class P {
                private final Map<String, List<Map<String, String>>> versions = new HashMap<>();
                private final Map<String, Map<String, Object>> products = new HashMap<>();
                private final List<Set<String>> tagList = new ArrayList<>();
                public void deactivate(String sku) {
                    Map<String, Object> p = products.get(sku);
                    if (p != null) p.put("active", false);
                }
                public void labelVersion(String id, int n, String label) {
                    List<Map<String, String>> vlist = versions.get(id);
                    if (vlist != null && n > 0 && n <= vlist.size()) vlist.get(n - 1).put("label", label);
                }
                @SuppressWarnings("unchecked")
                public void setAttr(String sku, String key, String value) {
                    Map<String, Object> p = products.get(sku);
                    if (p != null) ((Map<String, String>) p.get("attributes")).put(key, value);
                }
                @SuppressWarnings("unchecked")
                public void addNote(String sku, String note) {
                    Map<String, Object> a = products.get(sku);
                    if (a != null) ((List<String>) a.get("notes")).add(note);
                }
                public void renameInList(String oldTag) {
                    for (Set<String> tags : tagList) { tags.remove(oldTag); }
                }
                public int count(String id) {
                    List<Map<String, String>> vlist = versions.get(id);
                    return vlist == null ? 0 : vlist.get(0).size();
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
        TypeInfo p = javaInspector.parse("a.b.P", INPUT);
        List<Info> ao = prepWork(p);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(modReach).build()).analyze(ao);
        // deep / structural
        assertEquals("false/true", m(p, "deactivate"));
        assertEquals("false/true", m(p, "labelVersion"));
        assertEquals("false/true", m(p, "setAttr"));
        assertEquals("false/true", m(p, "addNote"));
        assertEquals("false/true", m(p, "renameInList"));
        assertEquals("true/true", m(p, "count"));
    }
}
