package io.codelaser.maddi.modification.analyzer.modification;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What Mockito's verification calls do to the mock, as the analysis reads them (libs/mockito). Verifying an
 * interaction does not modify the mock in any way the code can observe -- it answers as before -- so neither
 * {@code verify(mock)} nor the call made on what it returns ({@code verify(list).add("x")}, which re-states an
 * expected interaction, not a mutation) modifies the argument; {@code verifyNoMoreInteractions} reads. {@code reset}
 * does change the mock's behaviour, its stubbing, and modifies it.
 */
public class TestMockitoVerify extends CommonTest {

    @Override
    protected List<SourceSet> extraClassPathParts() {
        return List.of(SourceSetImpl.sourceSetOf(org.mockito.Mockito.class));
    }

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.List;
            import static org.mockito.Mockito.*;
            class X {
                void verifyThenAdd(List<String> list) { verify(list).add("x"); }
                void verifyTimesThenClear(List<String> list) { verify(list, times(2)).clear(); }
                void verifySize(List<String> list) { verify(list).size(); }
                void verifyNoMore(List<String> list) { verifyNoMoreInteractions(list); }
                void resetMock(List<String> list) { reset(list); }
            }
            """;

    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(X);
        var iterating = new io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl(javaInspector,
                new io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl.ConfigurationBuilder()
                        .setMaxIterations(10)
                        .setModificationViaReachability(true)
                        .build());
        iterating.analyze(ao);
        StringBuilder sb = new StringBuilder();
        for (String m : List.of("verifyThenAdd", "verifyTimesThenClear", "verifySize", "verifyNoMore", "resetMock")) {
            sb.append(m).append('=').append(X.findUniqueMethod(m, 1).parameters().getFirst().isUnmodified() ? "U" : "M")
                    .append(' ');
        }
        assertEquals("verifyThenAdd=U verifyTimesThenClear=U verifySize=U verifyNoMore=U resetMock=M ",
                sb.toString());
    }
}
