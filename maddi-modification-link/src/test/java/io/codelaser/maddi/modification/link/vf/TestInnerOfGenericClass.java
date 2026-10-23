package io.codelaser.maddi.modification.link.vf;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.link.LinkComputer;
import io.codelaser.maddi.modification.link.impl.LinkComputerImpl;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.METHOD_LINKS;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * jenkins's Run: {@code Run<?, ?>.RunExecution exec = RunnerStack.INSTANCE.peek()} and
 * {@code List<? extends Run<?, ?>.Artifact>}. Since maddi b8a227d6b an inner class of a generic class carries the
 * outer's type arguments first, so {@code X<?, ?>.Execution} has two parameters where Execution declares none; the
 * virtual-field computer must not take the formal container's components for type parameters.
 */
public class TestInnerOfGenericClass extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            class X<J, R> {
                abstract class Execution {
                    int id;
                    String label;
                    List<String> names = new ArrayList<>();
                    abstract J job();
                    List<String> names() { return names; }
                }
                class Artifact {
                    String name;
                    List<String> parts = new ArrayList<>();
                    R run() { return null; }
                    List<String> parts() { return parts; }
                }
                final class ArtifactList extends ArrayList<Artifact> { }
                static final List<X<?, ?>.Execution> STACK = new ArrayList<>();
                static int waitFor() {
                    X<?, ?>.Execution exec = STACK.get(0);
                    return exec.id;
                }
                static int size(List<? extends X<?, ?>.Artifact> a) {
                    X<?, ?>.Artifact first = a.get(0);
                    return a.size() + first.name.length();
                }
            }
            """;

    @DisplayName("an inner class of a generic class, through the outer's wildcards")
    @Test
    public void innerOfGenericClass() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build());
        analyzer.doPrimaryType(X);
        LinkComputer tlc = new LinkComputerImpl(javaInspector);
        for (String name : new String[]{"waitFor", "size"}) {
            MethodInfo method = X.methodStream().filter(m -> name.equals(m.name())).findFirst().orElseThrow();
            assertNotNull(method.analysis().getOrCreate(METHOD_LINKS, () -> tlc.doMethod(method)));
        }
    }
}
