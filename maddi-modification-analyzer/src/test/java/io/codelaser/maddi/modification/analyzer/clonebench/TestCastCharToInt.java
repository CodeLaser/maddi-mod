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

package io.codelaser.maddi.modification.analyzer.clonebench;

import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;


public class TestCastCharToInt extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            class X {
                protected static short[][] method(String[] sa) {
                    StringBuilder sb = new StringBuilder(sa[0]);
                    for (int i = 1; i < sa.length; i++) sb.append(sa[i]);
                    int n = 0;
                    int size1 = (((int) sb.charAt(n)) << 16) | ((int) sb.charAt(n + 1));
                    n += 2;
                    short[][] result = new short[size1][];
                    for (int i = 0; i < size1; i++) {
                        int size2 = (((int) sb.charAt(n)) << 16) | ((int) sb.charAt(n + 1));
                        n += 2;
                        result[i] = new short[size2];
                        for (int j = 0; j < size2; j++) result[i][j] = (short) (sb.charAt(n++) - 2);
                    }
                    return result;
                }
            }
            """;

    @DisplayName("assign char from int")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse("X", INPUT1);
        List<Info> ao = prepWork(X);
        analyzer.go(ao);
        MethodInfo method = X.findUniqueMethod("method", 1);

    }
}
