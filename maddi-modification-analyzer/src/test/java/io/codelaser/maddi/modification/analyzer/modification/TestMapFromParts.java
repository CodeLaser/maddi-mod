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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * A fresh map filled with parts of a parameter: putting into the map modifies the map, not the parameter whose parts
 * it holds (langchain4j's {@code JsonSchemaElementJsonUtils.toMap}, which made {@code ToolSpecification.toJson()}
 * modifying).
 */
public class TestMapFromParts extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            class X {
                interface E { String description(); }
                record S(String description, List<String> values) implements E { }
                record O(String description, Map<String, E> properties) implements E { }
                static Map<String, Object> typed(String type, String description) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("type", type);
                    if (description != null) map.put("description", description);
                    return map;
                }
                static Map<String, Object> enumMap(S s) {
                    Map<String, Object> map = typed("string", s.description());
                    map.put("enum", new ArrayList<>(s.values()));
                    return map;
                }
                static Map<String, Object> toMap(E e) {
                    if (e instanceof O o) return objectMap(o);
                    if (e instanceof S s) {
                        Map<String, Object> map = typed("string", s.description());
                        map.put("enum", new ArrayList<>(s.values()));
                        return map;
                    }
                    return typed("other", e.description());
                }
                static Map<String, Object> objectMap(O o) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("type", "object");
                    map.put("description", o.description());
                    Map<String, Object> properties = new LinkedHashMap<>();
                    o.properties().forEach((name, schema) -> properties.put(name, toMap(schema)));
                    map.put("properties", properties);
                    return map;
                }
            }
            """;

    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(X);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(20)
                .setModificationViaReachability(true)
                .build()).analyze(ao);
        StringBuilder sb = new StringBuilder();
        for (String name : List.of("typed", "enumMap", "toMap", "objectMap")) {
            MethodInfo m = X.methods().stream().filter(mi -> mi.name().equals(name)).findFirst().orElseThrow();
            sb.append(name);
            m.parameters().forEach(p -> sb.append(' ').append(p.name()).append('=').append(p.isUnmodified()));
            sb.append('\n');
        }
        assertEquals("""
                typed type=true description=true
                enumMap s=true
                toMap e=true
                objectMap o=true
                """, sb.toString());
    }
}
