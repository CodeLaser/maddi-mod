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

package io.codelaser.maddi.modification.analyzer.integration;

import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.link.vf.VirtualFieldComputer;
import io.codelaser.maddi.modification.link.vf.VirtualFields;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestIntegration3 extends CommonTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestIntegration3.class);

    @Language("java")
    private static final String INPUT1 = """
            import java.util.ArrayList;
            import java.util.Map;
            import java.util.List;
            import java.util.Optional;
            import java.util.function.Function;
            import java.util.stream.Collectors;
            public class X {
                interface DTO {
                    Long getId();
                }
                static class Lists {
                    static <T> List<List<T>> partition(List<T> list, int groups) {
                        throw new UnsupportedOperationException("NYI");
                    }
                }
                interface A {
                    Optional<Long> getId();
                    boolean isA();
                }
                private A a;
                <T extends DTO> Map<Long, List<T>> find(List<Long> ids, Function<List<Long>, List<T>> function) {
                    return a
                        .getId()
                        .map(currentUserId -> {
                            ArrayList<T> logs = new ArrayList<>();
                            Lists.partition(ids, 200).forEach(partitions -> {
                              List<T> list = function.apply(partitions);
                              logs.addAll(list);
                            });
                            return logs
                                 .stream()
                                 .filter(log -> a.isA() && log != null)
                                 .collect(Collectors.groupingBy(DTO::getId));
                        })
                        .orElseGet(Map::of);
                }
            }
            """;


    @DisplayName("virtual field computation")
    @Test
    public void test1a() {
        TypeInfo B = javaInspector.parse("X", INPUT1);
        List<Info> ao = prepWork(B);
        VirtualFieldComputer virtualFieldComputer = new VirtualFieldComputer(javaInspector);
        MethodInfo find = B.findUniqueMethod("find", 2);
        assertEquals("Type java.util.Map<Long,java.util.List<T extends X.DTO>>", find.returnType().toString());
        VirtualFields vf = virtualFieldComputer.compute(find.returnType(), false).virtualFields();
        assertEquals("§m - §$TS[] §$tss", vf.toString());

        // and ... go
        analyzer.go(ao);
    }
}
