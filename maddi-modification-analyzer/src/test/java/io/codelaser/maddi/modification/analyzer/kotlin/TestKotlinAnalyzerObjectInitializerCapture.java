package io.codelaser.maddi.modification.analyzer.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

/*
 fernflower's StackVarsProcessor.replaceSingleVar: `set = ent.value; set.complement(…)` over
 `ssau.getSsuversions().nodes.getWithKey(v).live.entryList()`, whose entries are an `object : MutableEntry` holding the
 loop's element in `val v = e`. Since #147 Java sees the entry's value modified reach `ssau`; Kotlin did not, because
 the K2 front end converted an `object :`'s property initializers without the captured locals (`v = <unresolved e>`),
 and entryList() linked its result to nothing (maddi-mod#145; maddi's ObjectLiteralCaptureTest).
 */
public class TestKotlinAnalyzerObjectInitializerCapture extends CommonKotlinAnalyzerTest {

    @Language("kotlin")
    private static final String KOTLIN = """
            package k
            import java.util.ArrayList
            import java.util.HashMap
            class S { var n: Int = 0; fun complement(o: S?) { n++ } }
            class M {
                private val elements: Array<S?> = arrayOfNulls<S>(4)
                fun entryList(): MutableList<MutableMap.MutableEntry<Int, S>> {
                    val list: MutableList<MutableMap.MutableEntry<Int, S>> = ArrayList<MutableMap.MutableEntry<Int, S>>()
                    for (e in elements) {
                        if (e != null) {
                            list.add(object : MutableMap.MutableEntry<Int, S> {
                                internal val v: S = e
                                override val key: Int get() = 0
                                override val value: S get() = v
                                override fun setValue(newValue: S): S = throw UnsupportedOperationException()
                            })
                        }
                    }
                    return list
                }
                fun get(k: Int): S? = elements[k]
            }
            class Node { val live: M = M() }
            class U { val nodes: MutableMap<Int, Node> = HashMap<Int, Node>(); fun node(k: Int): Node? = nodes.get(k) }
            class P {
                fun viaAnon(u: U, k: Int, livemap: M) {
                    val node = u.node(k)!!
                    val it = node.live.entryList().iterator()
                    while (it.hasNext()) { val ent = it.next(); val set = ent.value; set.complement(livemap.get(ent.key)) }
                }
                fun viaMap(m: MutableMap<Int, S>) {
                    val it = m.entries.iterator()
                    while (it.hasNext()) { val ent = it.next(); val set = ent.value; set.complement(null) }
                }
                fun viaList(m: M) {
                    for (ent in m.entryList()) { ent.value.complement(null) }
                }
            }
            """;

    @Language("java")
    private static final String JAVA = """
            package j;
            import java.util.*;
            class S { int n; void complement(S o) { n++; } }
            class M {
                private final S[] elements = new S[4];
                List<Map.Entry<Integer, S>> entryList() {
                    List<Map.Entry<Integer, S>> list = new ArrayList<>();
                    for (final S e : elements) {
                        if (e != null) {
                            list.add(new Map.Entry<>() {
                                private final S v = e;
                                @Override public Integer getKey() { return 0; }
                                @Override public S getValue() { return v; }
                                @Override public S setValue(S newValue) { throw new UnsupportedOperationException(); }
                            });
                        }
                    }
                    return list;
                }
                S get(int k) { return elements[k]; }
            }
            class Node { final M live = new M(); }
            class U { final Map<Integer, Node> nodes = new HashMap<>(); Node node(int k) { return nodes.get(k); } }
            class P {
                void viaAnon(U u, int k, M livemap) {
                    Node node = u.node(k);
                    for (Iterator<Map.Entry<Integer, S>> it = node.live.entryList().iterator(); it.hasNext(); ) {
                        Map.Entry<Integer, S> ent = it.next(); S set = ent.getValue(); set.complement(livemap.get(ent.getKey()));
                    }
                }
                void viaMap(Map<Integer, S> m) {
                    for (Iterator<Map.Entry<Integer, S>> it = m.entrySet().iterator(); it.hasNext(); ) {
                        Map.Entry<Integer, S> ent = it.next(); S set = ent.getValue(); set.complement(null);
                    }
                }
                void viaList(M m) { for (Map.Entry<Integer, S> ent : m.entryList()) ent.getValue().complement(null); }
            }
            """;

    @Test
    public void theEntryValueReachesItsOwner() {
        Analyzed a = analyze(List.of("X.kt", KOTLIN), List.of("X.java", JAVA), true);
        StringBuilder sb = new StringBuilder();
        for (String m : new String[]{"viaAnon", "viaMap", "viaList"}) {
            var jm = a.javaType("P").methods().stream().filter(x -> x.name().equals(m)).findFirst().orElseThrow();
            var km = a.kotlinType("P").methods().stream().filter(x -> x.name().equals(m)).findFirst().orElseThrow();
            sb.append(m).append(" java=").append(jm.parameters().getFirst().isUnmodified())
              .append(" kotlin=").append(km.parameters().getFirst().isUnmodified()).append('\n');
        }
        assertEquals("""
                viaAnon java=false kotlin=false
                viaMap java=false kotlin=false
                viaList java=false kotlin=false
                """, sb.toString());
        a.assertSameAsJava("M");
    }
}
