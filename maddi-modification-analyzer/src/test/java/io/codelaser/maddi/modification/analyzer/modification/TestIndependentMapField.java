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

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A map's keys and values are linked through slices of its virtual field ({@code this.items.§$$s[-1]}), typed as
 * the array of the component. The field's independence must judge the component that a link transports, not that
 * array: a map of immutable records, filled from and read into the outside world, shares nothing.
 */
public class TestIndependentMapField extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.HashMap;
            import java.util.List;
            import java.util.Map;
            public class X {
                public record Item(String name, int count) {
                }
                static final class Inventory {
                    private final Map<String, Item> items = new HashMap<>();
                    public Item find(String name) { return items.get(name); }
                    public void putItem(Item item) { items.put(item.name(), item); }
                    public List<String> names() { return new ArrayList<>(items.keySet()); }
                }
                static final class Buffers {
                    private final Map<String, StringBuilder> items = new HashMap<>();
                    public StringBuilder find(String name) { return items.get(name); }
                    public void put(String name, StringBuilder sb) { items.put(name, sb); }
                }
            }
            """;

    private static String verdict(TypeInfo x, String name) {
        TypeInfo t = x.subTypes().stream().filter(s -> name.equals(s.simpleName())).findFirst().orElseThrow();
        return t.analysis().getOrNull(PropertyImpl.INDEPENDENT_TYPE, ValueImpl.IndependentImpl.class) + " "
               + t.getFieldByName("items", true).analysis().getOrNull(PropertyImpl.INDEPENDENT_FIELD,
                ValueImpl.IndependentImpl.class);
    }

    @DisplayName("a map of immutable values is independent; a map of mutable ones stays dependent")
    @Test
    public void test() {
        TypeInfo x = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepAnalyzer.doPrimaryTypes(Set.of(x));
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10)
                .setModificationViaReachability(true)
                .build()).analyze(ao);
        assertEquals("@Independent @Independent", verdict(x, "Inventory"));
        assertEquals("@Dependent @Dependent", verdict(x, "Buffers"));
    }
}
