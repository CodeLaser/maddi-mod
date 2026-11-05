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

package io.codelaser.maddi.modification.analyzer;

import static io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.METHOD_LINKS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;

/**
 * CodeLaser/maddi-mod#23, reduced from elasticsearch's ShardRouting and AllocationId. {@code make} returns a holder
 * whose {@code first} is the call result {@code $__rv1 = swap(x)}, and whose constructor fills {@code second} from
 * {@code swap(first)}. The field {@code $__rv1.id} is collapsed into a group with {@code 0:x.relocationId} and
 * {@code second.relocationId}; the member-field mirror of {@code first ← $__rv1} gave {@code first.id ← $__rv1.id}
 * and stopped there, and the summary drops the intermediate. In ShardRouting that loss came and went with the
 * input, and the recursion cycle swung between two summaries. Gate NOMIRRORSOURCES.
 */
public class TestMemberFieldMirror extends CommonTest {

    @Language("java")
    private static final String SRC = """
            package a.b;
            public class PS {
                public static class Alloc {
                    private final String id;
                    private final String relocationId;
                    private Alloc(String id, String relocationId) { this.id = id; this.relocationId = relocationId; }
                    public static Alloc swap(Alloc a) { return new Alloc(a.relocationId, a.id); }
                }
                public static class Holder {
                    private final Alloc first;
                    private final Alloc second;
                    Holder(Alloc first) { this.first = first; this.second = Alloc.swap(first); }
                }
                public static Holder make(Alloc x) {
                    return new Holder(Alloc.swap(x));
                }
            }
            """;

    @Test
    public void memberFieldMirrorCarriesTheFieldsSources() throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo typeInfo = bundle.javaInspector().parse("a.b.PS", SRC);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(typeInfo);
        IteratingAnalyzer analyzer = new IteratingAnalyzerImpl(bundle.javaInspector(),
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(20)
                        .setModificationViaReachability(true).build());
        analyzer.analyze(analysisOrder);
        MethodInfo make = typeInfo.findUniqueMethod("make", 1);
        String links = String.valueOf(make.analysis().getOrNull(METHOD_LINKS, MethodLinkedVariablesImpl.class));
        for (String expected : List.of("make.first.id←0:x.relocationId", "make.first.relocationId←0:x.id",
                "make.second.id←0:x.id", "make.second.relocationId←0:x.relocationId")) {
            assertTrue(links.contains(expected), () -> "missing " + expected + " in " + links);
        }
    }
}
