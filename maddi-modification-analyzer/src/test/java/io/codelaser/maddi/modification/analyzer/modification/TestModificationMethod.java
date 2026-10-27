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

package io.codelaser.maddi.modification.analyzer.modification;


import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.codelaser.maddi.modification.prepwork.callgraph.ComputePartOfConstructionFinalField.EMPTY_PART_OF_CONSTRUCTION;
import static io.codelaser.maddi.modification.prepwork.callgraph.ComputePartOfConstructionFinalField.PART_OF_CONSTRUCTION;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class TestModificationMethod extends CommonTest {
    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.io.IOException;
            import java.io.InputStream;
            public class X {
                private int method(final int n) throws IOException {
                    int bsLiveShadow = this.bsLive;
                    int bsBuffShadow = this.bsBuff;
                    if (bsLiveShadow < n) {
                        final InputStream inShadow = this.in;
                        do {
                            int thech = inShadow.read();
                            if (thech < 0) {
                                throw new IOException("unexpected end of stream");
                            }
                            bsBuffShadow = (bsBuffShadow << 8) | thech;
                            bsLiveShadow += 8;
                        } while (bsLiveShadow < n);
                        this.bsBuff = bsBuffShadow;
                    }
                    this.bsLive = bsLiveShadow - n;
                    return (bsBuffShadow >> (bsLiveShadow - n)) & ((1 << n) - 1);
                }
            
                private int bsBuff;
                private int bsLive;
                private InputStream in;
            }
            """;

    @DisplayName("simple method modification")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT1);
        List<Info> analysisOrder = prepWork(X);
        analyzer.go(analysisOrder);
        Value.SetOfInfo poc = X.analysis().getOrDefault(PART_OF_CONSTRUCTION, EMPTY_PART_OF_CONSTRUCTION);
        MethodInfo methodInfo = X.findUniqueMethod("method", 1);
        assertFalse(poc.infoSet().contains(methodInfo));
        assertTrue(methodInfo.isModifying());
    }
}

