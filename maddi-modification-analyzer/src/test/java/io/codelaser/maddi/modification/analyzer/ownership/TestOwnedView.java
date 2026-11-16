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

package io.codelaser.maddi.modification.analyzer.ownership;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * An unmodifiable view over an object only the view holds: the field holding the view owns it, and the object
 * behind it (road-to-immutability §060, "Ownership and eventual immutability").
 */
public class TestOwnedView extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class X {
                // a private constructor, its only caller hands the list over
                public static final class Priv {
                    private final List<String> l;
                    private Priv(List<String> l) { this.l = Collections.unmodifiableList(l); }
                    public static Priv of(String s) { List<String> list = new ArrayList<>(); list.add(s); return new Priv(list); }
                    public String get(int i) { return l.get(i); }
                }
                // a public constructor: owned in a closed world only
                public static final class Pub {
                    private final List<String> l;
                    public Pub(List<String> l) { this.l = Collections.unmodifiableList(l); }
                    public static Pub of(String s) { List<String> list = new ArrayList<>(); list.add(s); return new Pub(list); }
                    public String get(int i) { return l.get(i); }
                }
                // a view over a local the constructor builds
                public static final class Local {
                    private final List<String> l;
                    public Local(String s) {
                        List<String> list = new ArrayList<>();
                        list.add(s);
                        this.l = Collections.unmodifiableList(list);
                    }
                    public String get(int i) { return l.get(i); }
                }
                // a view over a created object
                public static final class Created {
                    private final Set<String> s = Collections.unmodifiableSet(new HashSet<>());
                    public boolean has(String t) { return s.contains(t); }
                }
                // the caller keeps the list and writes it later
                public static final class Kept {
                    private final List<String> l;
                    private Kept(List<String> l) { this.l = Collections.unmodifiableList(l); }
                    public static Kept of(List<String> sink) {
                        List<String> list = new ArrayList<>();
                        Kept k = new Kept(list);
                        list.add("x");
                        return k;
                    }
                    public String get(int i) { return l.get(i); }
                }
                // the constructor keeps the list in a second field
                public static final class Twice {
                    private final List<String> l;
                    private final List<String> raw;
                    private Twice(List<String> l) { this.l = Collections.unmodifiableList(l); this.raw = l; }
                    public static Twice of() { return new Twice(new ArrayList<>()); }
                    public String get(int i) { return l.get(i) + raw.size(); }
                }
                // the local behind the view also escapes into a static
                public static final class Escapes {
                    static final List<List<String>> ALL = new ArrayList<>();
                    private final List<String> l;
                    public Escapes() {
                        List<String> list = new ArrayList<>();
                        ALL.add(list);
                        this.l = Collections.unmodifiableList(list);
                    }
                    public String get(int i) { return l.get(i); }
                }
                // a view of another field: two holders of one list
                public static final class OfField {
                    private final List<String> raw = new ArrayList<>();
                    private final List<String> l = Collections.unmodifiableList(raw);
                    public String get(int i) { raw.add("x"); return l.get(i); }
                }
                // the local is written after the view is made: a holder besides the view, if only for a while
                public static final class After {
                    private final List<String> l;
                    public After(String s) {
                        List<String> list = new ArrayList<>();
                        this.l = Collections.unmodifiableList(list);
                        list.add(s);
                    }
                    public String get(int i) { return l.get(i); }
                }
                // the view is handed out
                public static final class Exposed {
                    private final List<String> l = Collections.unmodifiableList(new ArrayList<>());
                    public List<String> get() { return l; }
                }
            }
            """;

    private static final Map<String, String> EXPECTED_OPEN = new TreeMap<>(Map.ofEntries(
            Map.entry("Priv.l", "OWNED"),
            Map.entry("Pub.l", "NOT_OWNED"),
            Map.entry("Local.l", "OWNED"),
            Map.entry("Created.s", "OWNED"),
            Map.entry("Kept.l", "NOT_OWNED"),
            Map.entry("Twice.l", "NOT_OWNED"),
            Map.entry("Escapes.l", "NOT_OWNED"),
            Map.entry("Exposed.l", "NOT_OWNED"),
            Map.entry("OfField.l", "NOT_OWNED"),
            Map.entry("After.l", "NOT_OWNED")));
    private static final Map<String, String> EXPECTED_CLOSED = new TreeMap<>(EXPECTED_OPEN);

    static {
        EXPECTED_CLOSED.put("Pub.l", "OWNED");
    }

    @DisplayName("a view over an object only the view holds is owned with it")
    @Test
    public void test() throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo x = bundle.javaInspector().parse("a.b.X", INPUT);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(x);
        new IteratingAnalyzerImpl(bundle.javaInspector(), new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(true).build())
                .analyze(analysisOrder);
        Map<String, String> open = verdicts(new OwnershipPass(false).go(analysisOrder), "open");
        Map<String, String> closed = verdicts(new OwnershipPass(true).go(analysisOrder), "closed");
        assertEquals(EXPECTED_OPEN, open);
        assertEquals(EXPECTED_CLOSED, closed);
    }

    private static Map<String, String> verdicts(OwnershipPass.Report report, String world) {
        Map<String, String> actual = new TreeMap<>();
        for (Map.Entry<FieldInfo, OwnershipPass.Owned> e : report.owned().entrySet()) {
            FieldInfo fi = e.getKey();
            String key = fi.owner().simpleName() + "." + fi.name();
            if (!EXPECTED_OPEN.containsKey(key)) continue;
            actual.put(key, e.getValue().name());
            System.out.println("VIEW " + world + " " + key + " = " + e.getValue() + "  "
                               + report.notOwnedReason().getOrDefault(fi, ""));
        }
        return actual;
    }
}
