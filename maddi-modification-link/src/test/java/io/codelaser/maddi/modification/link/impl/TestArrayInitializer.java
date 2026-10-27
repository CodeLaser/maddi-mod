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

package io.codelaser.maddi.modification.link.impl;


import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.link.LinkComputer;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;


public class TestArrayInitializer extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """   
            public class B {
              public int NthLowestSkill(int n) {
                int[] skillIds = new int[] {0, 1, 2, 3};
                for (int j = 0; j < 3; j++) {
                  for (int i = 0; i < 3 - j; i++) {
                    if (Skills()[skillIds[i]] > Skills()[skillIds[i + 1]]) {
                      int temp = skillIds[i];
                      skillIds[i] = skillIds[i + 1];
                      skillIds[i + 1] = temp;
                    }
                  }
                }
                return skillIds[n - 1];
              }
            
              private int[] _skills = new int[4];
            
              public int[] Skills() {
                return _skills;
              }
            }
            """;

    @DisplayName("various dependent variable issues")
    @Test
    public void test1() {
        TypeInfo B = javaInspector.parse("B", INPUT1);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build());
        analyzer.doPrimaryType(B);

        MethodInfo get = B.findUniqueMethod("NthLowestSkill", 1);
        LinkComputer tlc = new LinkComputerImpl(javaInspector, LinkComputer.Options.PRODUCTION);
        MethodLinkedVariables mlv = tlc.doMethod(get);
        assertEquals("[-] --> -", mlv.toString());
    }


    @Language("java")
    private static final String INPUT2 = """
            import java.io.File;
            
            public class B {
                File base;
              public File[] add(File[] list, File item) {
                if (null == item) return list;
                else if (null == list) return new File[] {item};
                else {
                  File[] copy = new File[] { base, item};
                  return copy;
                }
              }
            }
            """;

    @DisplayName("constructor calls")
    @Test
    public void test2() {
        TypeInfo B = javaInspector.parse("B", INPUT2);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build());
        analyzer.doPrimaryType(B);

        MethodInfo get = B.findUniqueMethod("add", 2);
        LinkComputer tlc = new LinkComputerImpl(javaInspector);
        MethodLinkedVariables mlv = tlc.doMethod(get);
        // no 'add[1]→add[0]' (nor its §m companion): 'add[0] ← item' holds for the array returned by the
        // else-if, 'add[1] ← item' for the one returned by the else; no returned array has both. Linked into
        // one graph, the two branches combined into that relation (LinkComputerImpl.linkAlternatives).
        // and no §m companions at all since 2026-10-12: java.io.File is @Immutable in the JDK hints (like Path), so
        // an element has no mutable content to share
        assertEquals("""
                [-, -] --> add←0:list,add←$_v,add[0]←1:item,add[0]←this.base,add[1]←1:item\
                """, mlv.toString());
    }
}
