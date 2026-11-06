package io.codelaser.maddi.modification.analyzer.field;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;

/// A source field's finality is computed from its assignments. The shallow owner rule, which runs on the fields of
/// an abstract source type, may not replace that verdict with the owner's immutability level: for a source type
/// that level is computed from these very fields, or claimed over them, so the claim would confirm itself.
/// Here `s` is reassigned in `set`, whatever the annotation on the type says.
public class TestFieldAnalyzerOwnerFinality extends CommonTest {

    @Language("java")
    private static final String TEMPLATE = """
            package a.b;
            import java.util.Set;
            import io.codelaser.maddi.annotation.FinalFields;
            import io.codelaser.maddi.annotation.Immutable;
            import io.codelaser.maddi.annotation.eventual.Mark;
            ANNOTATION
            abstract class NAME {
                private Set<String> s;
                @Mark("s")
                void set(Set<String> t) { s = t; }
                abstract void m();
            }
            """;

    private static final List<String> ANNOTATIONS = List.of(
            "@Immutable(hc = true, contract = true)",
            "@Immutable(hc = true)",
            "@FinalFields(contract = true)",
            "@FinalFields",
            "@FinalFields(after = \"s\")",
            "");

    @Test
    public void reassignedFieldStaysAssignable() {
        for (int i = 0; i < ANNOTATIONS.size(); ++i) {
            String annotation = ANNOTATIONS.get(i);
            String name = "A" + i;
            TypeInfo A = javaInspector.parse("a.b." + name,
                    TEMPLATE.replace("ANNOTATION", annotation).replace("NAME", name));
            List<Info> ao = prepWork(A);
            analyzer.go(ao, 3);
            FieldInfo s = A.getFieldByName("s", true);
            assertFalse(s.isPropertyFinal(), annotation);
        }
    }
}
