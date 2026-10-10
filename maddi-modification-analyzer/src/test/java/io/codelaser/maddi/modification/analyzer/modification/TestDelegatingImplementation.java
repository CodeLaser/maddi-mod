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
 * CodeLaser/maddi-mod#24 R3, the langchain4j EmbeddingModel shape. The abstract {@code embedAll}'s parameter is the
 * union over its implementations; one implementation delegates to the abstract method itself, the other only reads.
 * Nothing modifies the list, but the delegating implementation and the abstract method can sustain each other's
 * "modified". Two channels carried that into the reachability pass: MethodModification put the abstract's own
 * parameter into the delegating implementation's summary, untranslated, and the pass seeded it; and the pass seeded
 * {@code this.delegate} from the fixpoint's statement-level verdict instead of re-deriving it.
 */
public class TestDelegatingImplementation extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.Collections;
            import java.util.List;
            class R3 {
                record Seg(String text) { }
                interface Model {
                    default String embed(String text) { return embed(new Seg(text)); }
                    default String embed(Seg seg) {
                        List<String> all = embedAll(Collections.singletonList(seg));
                        return all.get(0);
                    }
                    List<String> embedAll(List<Seg> segs);
                }
                static final class Listening implements Model {
                    private final Model delegate;
                    Listening(Model delegate) { this.delegate = delegate; }
                    @Override
                    public List<String> embedAll(List<Seg> segs) {
                        try {
                            return delegate.embedAll(segs);
                        } catch (RuntimeException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                }
                static final class Plain implements Model {
                    @Override
                    public List<String> embedAll(List<Seg> segs) {
                        List<String> out = new ArrayList<>();
                        for (Seg s : segs) out.add(s.text());
                        return out;
                    }
                }
            }
            """;

    private static MethodInfo embedAll(TypeInfo typeInfo) {
        return typeInfo.methods().stream().filter(m -> m.name().equals("embedAll")).findFirst().orElseThrow();
    }

    private String verdicts(boolean modReach) {
        TypeInfo X = javaInspector.parse("a.b.R3", INPUT);
        List<Info> ao = prepWork(X);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10)
                .setModificationViaReachability(modReach)
                .build()).analyze(ao);
        return "Model=" + embedAll(X.findSubType("Model")).parameters().getFirst().isUnmodified()
               + " Listening=" + embedAll(X.findSubType("Listening")).parameters().getFirst().isUnmodified()
               + " Plain=" + embedAll(X.findSubType("Plain")).parameters().getFirst().isUnmodified();
    }

    private static final String EXPECTED = "Model=true Listening=true Plain=true";

    // the fixpoint alone keeps the cycle's FALSE (an undecided implementation folds as modifying, and the delegate
    // call reads the abstract's FALSE back), as in TestRecursionThroughAbstract; the reachability pass is the authority
    @DisplayName("without reachability the delegate cycle stays modified")
    @Test
    public void fixpoint() {
        assertEquals("Model=false Listening=false Plain=true", verdicts(false));
    }

    @DisplayName("a delegating implementation does not make the abstract method's parameter modified: reachability")
    @Test
    public void reachability() {
        assertEquals(EXPECTED, verdicts(true));
    }
}
