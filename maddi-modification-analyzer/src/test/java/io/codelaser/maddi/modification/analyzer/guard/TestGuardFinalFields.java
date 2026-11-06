package io.codelaser.maddi.modification.analyzer.guard;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.analysis.Message;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.impl.GuardAnalyzerImpl;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/// `@FinalFields` on a type (§020): no field is assigned after construction. The guard checks it as rule 0 of
/// `@Immutable` on its own.
public class TestGuardFinalFields extends CommonTest {

    private List<Message> violations(String fqn, String source) throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo typeInfo = bundle.javaInspector().parse(fqn, source);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(typeInfo);
        IteratingAnalyzer analyzer = new IteratingAnalyzerImpl(bundle.javaInspector(),
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(10).setGuardContracts(true).build());
        analyzer.analyze(analysisOrder);
        return analyzer.messages().stream()
                .filter(m -> GuardAnalyzerImpl.CONTRACT_VIOLATION.equals(m.category())).toList();
    }

    @Language("java")
    private static final String TEMPLATE = """
            package a.b;
            import java.util.Set;
            import io.codelaser.maddi.annotation.FinalFields;
            import io.codelaser.maddi.annotation.eventual.Mark;
            public class X {
                ANNOTATION
                static MODIFIERS class Y {
                    private final Set<String> f;
                    private Set<String> s;
                    Y(Set<String> f) { this.f = f; }
                    @Mark("s")
                    void set(Set<String> t) { s = t; }
                    Set<String> getS() { return s; }
                    ABSTRACT
                }
            }
            """;

    private static String source(String annotation, boolean isAbstract) {
        return TEMPLATE.replace("ANNOTATION", annotation)
                .replace("MODIFIERS", isAbstract ? "abstract" : "")
                .replace("ABSTRACT", isAbstract ? "abstract void run();" : "");
    }

    @DisplayName("a reassigned field violates @FinalFields")
    @Test
    public void reassigned() throws IOException {
        List<Message> violations = violations("a.b.X", source("@FinalFields", false));
        assertEquals(1, violations.size(), violations.toString());
        Message v = violations.getFirst();
        assertTrue(v.level().isError());
        assertEquals("a.b.X.Y.s", v.info().fullyQualifiedName());
        assertTrue(v.message().contains("@FinalFields contract"), v.message());
    }

    @DisplayName("also on an abstract type, whose level is the contract itself")
    @Test
    public void reassignedAbstract() throws IOException {
        List<Message> violations = violations("a.b.X", source("@FinalFields", true));
        assertEquals(1, violations.size(), violations.toString());
        assertEquals("a.b.X.Y.s", violations.getFirst().info().fullyQualifiedName());
    }

    @DisplayName("eventual @FinalFields(after=) is not guarded: the field is assigned until the mark")
    @Test
    public void eventual() throws IOException {
        assertTrue(violations("a.b.X", source("@FinalFields(after = \"s\")", false)).isEmpty());
    }

    @Language("java")
    private static final String ALL_FINAL = """
            package a.b;
            import java.util.Set;
            import io.codelaser.maddi.annotation.FinalFields;
            public class X {
                @FinalFields
                static class Y {
                    private final Set<String> f;
                    private Set<String> late;
                    Y(Set<String> f) { this.f = f; this.late = f; }
                    Set<String> getF() { return f; }
                    Set<String> getLate() { return late; }
                }
            }
            """;

    @DisplayName("no violation when every field is effectively final")
    @Test
    public void allFinal() throws IOException {
        assertTrue(violations("a.b.X", ALL_FINAL).isEmpty());
    }
}
