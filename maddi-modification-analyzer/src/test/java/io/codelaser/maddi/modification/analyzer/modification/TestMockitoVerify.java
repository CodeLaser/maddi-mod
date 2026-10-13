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

    // stubbing (OrgMockitoStubbing): the values and throwables a stub hands out later are stored, not changed
    @Language("java")
    private static final String STUBBING = """
            package a.b;
            import java.util.List;
            import static org.mockito.Mockito.*;
            import static org.mockito.BDDMockito.willThrow;
            class Y {
                @SuppressWarnings("unchecked")
                private final List<Object> m = mock(List.class);
                void thenReturnNext(List<String> next) { when(m.get(0)).thenReturn("a", next); }
                void thenThrowIt(RuntimeException e) { when(m.size()).thenThrow(e); }
                void doThrowIt(RuntimeException e) { doThrow(e).when(m).clear(); }
                void doReturnNext(List<String> next) { doReturn(1, next).when(m).size(); }
                void willThrowIt(RuntimeException e) { willThrow(e).given(m).clear(); }
                void inOrderOf(List<String> list) { inOrder(list); }
            }
            """;

    @Test
    public void testStubbing() {
        TypeInfo Y = javaInspector.parse("a.b.Y", STUBBING);
        List<Info> ao = prepWork(Y);
        var iterating = new io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl(javaInspector,
                new io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl.ConfigurationBuilder()
                        .setMaxIterations(10)
                        .setModificationViaReachability(true)
                        .build());
        iterating.analyze(ao);
        StringBuilder sb = new StringBuilder();
        for (String m : List.of("thenReturnNext", "thenThrowIt", "doThrowIt", "doReturnNext", "willThrowIt", "inOrderOf")) {
            sb.append(m).append('=').append(Y.findUniqueMethod(m, 1).parameters().getFirst().isUnmodified() ? "U" : "M")
                    .append(' ');
        }
        assertEquals("thenReturnNext=U thenThrowIt=U doThrowIt=U doReturnNext=U willThrowIt=U inOrderOf=U ",
                sb.toString());
    }
}
