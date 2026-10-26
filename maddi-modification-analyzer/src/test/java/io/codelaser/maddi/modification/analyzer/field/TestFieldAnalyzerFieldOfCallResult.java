package io.codelaser.maddi.modification.analyzer.field;

import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestFieldAnalyzerFieldOfCallResult extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.cr;
            import java.util.Objects;
            class L {
                static class Node {
                    Node next;
                }
                private Node tail = new Node();
                private Node last = new Node();
                void append(Node node) {
                    Objects.requireNonNull(tail).next = node;
                }
                void appendLast(Node node) {
                    self().next = node;
                }
                private Node self() {
                    return last;
                }
            }
            """;

    @DisplayName("assigning a field of a call's result modifies the object the result stands for")
    @Test
    public void test() throws IOException {
        check(true);
    }

    @DisplayName("... and the method that does so is modifying, in the fixpoint mode too")
    @Test
    public void testFixpoint() throws IOException {
        check(false);
    }

    private void check(boolean modReach) throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo L = bundle.javaInspector().parse("a.cr.L", INPUT);
        List<Info> ao = bundle.prepAnalyzer().doPrimaryType(L);
        IteratingAnalyzer analyzer = new IteratingAnalyzerImpl(bundle.javaInspector(),
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(10)
                        .setModificationViaReachability(modReach).build());
        analyzer.analyze(ao);
        // guava's LinkedListMultimap.addNode: 'requireNonNull(tail).next = node'. The assignment marked the scope
        // chain of its target modified, but a scope that is an expression (a call) has no scope VARIABLE, so
        // nothing was marked: 'tail' read as unmodified. A modifying call on the same result ('.setNext(node)')
        // was fine.
        Value.Bool tailUnmodified = L.getFieldByName("tail", true).analysis()
                .getOrNull(PropertyImpl.UNMODIFIED_FIELD, ValueImpl.BoolImpl.class);
        Value.Bool lastUnmodified = L.getFieldByName("last", true).analysis()
                .getOrNull(PropertyImpl.UNMODIFIED_FIELD, ValueImpl.BoolImpl.class);
        assertTrue(tailUnmodified != null && tailUnmodified.isFalse()
                   && lastUnmodified != null && lastUnmodified.isFalse(),
                "tail unmodified=" + tailUnmodified + ", last unmodified=" + lastUnmodified);
        // ... and the methods modify what their fields hold: the MODREACH walk seeded nothing for a field whose scope
        // is an expression, so both read non-modifying there while the fields were modified (CodeLaser/maddi-mod#71,
        // found by rustc: 'append_last(&self)' writing through self)
        assertFalse(L.findUniqueMethod("append", 1).isNonModifying(), "append, modReach=" + modReach);
        assertFalse(L.findUniqueMethod("appendLast", 1).isNonModifying(), "appendLast, modReach=" + modReach);
        assertTrue(L.findUniqueMethod("self", 0).isNonModifying(), "self, modReach=" + modReach);
    }
}
