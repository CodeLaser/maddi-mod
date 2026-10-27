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
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;


public class TestBreakVariable extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            import java.util.HashMap;
            
            public class B {
              public void removeMode(char mode) {
                int modeIndex = chanModes.indexOf(mode);
                if (modeIndex != -1) {
                  char prefix = getModePrefix(mode);
                  if (prefix == 0) return;
                  chanModes = chanModes.replaceAll(String.valueOf(mode), "");
                  if (type == prefix) {
                    char newType = TYPE_NORMAL;
                    int maxWeight = 0;
                    for (int i = 0; i < chanModes.length(); i++) {
                      char modePrefix = getModePrefix(chanModes.charAt(i));
                      int weight = ((Integer) typesWeight.get(new Character(modePrefix))).intValue();
                      if (weight > maxWeight) {
                        newType = modePrefix;
                        maxWeight = weight;
                      }
                    }
                    type = newType;
                  }
                }
              }
            
              public static final char TYPE_NORMAL = ' ';
              private static final HashMap typesWeight = new HashMap();
              private char type = TYPE_NORMAL;
              private String chanModes = "";
            
              private char getModePrefix(char mode) {
                switch (mode) {
                  case 'q':
                    return '~';
                  case 'a':
                    return '&';
                  case 'o':
                    return '@';
                  case 'h':
                    return '%';
                  case 'v':
                    return '+';
                }
                return 0;
              }
            }
            """;

    @DisplayName("used to fix issue involving non-existent variables in ComputeLinkCompletion")
    @Test
    public void test1() {
        TypeInfo B = javaInspector.parse("B", INPUT1);
        List<Info> ao = prepWork(B);
        analyzer.go(ao);
    }
}
