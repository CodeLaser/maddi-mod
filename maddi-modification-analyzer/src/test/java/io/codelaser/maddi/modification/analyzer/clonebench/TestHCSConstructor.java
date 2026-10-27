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


public class TestHCSConstructor extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            import org.junit.jupiter.api.Assertions;
            import org.junit.jupiter.api.Test;
            
            import java.util.ArrayList;
            import java.util.Arrays;
            import java.util.HashSet;
            import java.util.Set;
            import java.util.stream.Collectors;
            
            public class ArrayList_Union_NoDup {

                public static ArrayList<String> unionAddAllStreamDistinct(ArrayList<String> list1, ArrayList<String> list2) {
                    ArrayList<String> unionList = new ArrayList<>();
                    unionList.addAll(list1);
                    unionList.addAll(list2);
                    return unionList.stream().distinct().collect(Collectors.toCollection(ArrayList::new));
                }
                @Test
                public void tests() {
                    ArrayList<String> list1 = new ArrayList<>(Arrays.asList("a", "b", "c", "d", "d", "d"));
                    ArrayList<String> list2 = new ArrayList<>(Arrays.asList("b", "d", "e", "f", "f", "d"));
                    ArrayList<String> expected = new ArrayList<>(Arrays.asList("a", "b", "c", "d", "e", "f"));
                    Assertions.assertEquals(expected, unionAddAllStreamDistinct(list1, list2));
                }
            }
            
            """;

    @DisplayName("various dependent variable issues")
    @Test
    public void test1() {
        TypeInfo B = javaInspector.parse("ArrayList_Union_NoDup", INPUT1);
        List<Info> ao = prepWork(B);
        analyzer.go(ao);
    }
}
