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
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;


public class TestEmptyBlock extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            import java.util.Vector;
            class X {
              private void method(Vector<Integer> v) {
                v.add(1);
                v.add(4);
    
                for (int i = 1; i < 99999; i++) {
                  Integer number = i;
                  if (!number.toString().contains("5")) {
                    // empty
                  }
                }
              }
            }
            """;

    @DisplayName("empty block causes issues")
    @Test
    public void test1() {
        TypeInfo B = javaInspector.parse("X", INPUT1);
        List<Info> ao = prepWork(B);
        analyzer.go(ao);
    }
}
