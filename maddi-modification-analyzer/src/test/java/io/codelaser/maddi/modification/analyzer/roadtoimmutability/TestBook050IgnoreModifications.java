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

package io.codelaser.maddi.modification.analyzer.roadtoimmutability;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

/**
 * The examples of road-to-immutability §050, "Ignoring modifications as manual hidden content".
 */
public class TestBook050IgnoreModifications extends BookTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import io.codelaser.maddi.annotation.rare.IgnoreModifications;
            import java.util.*;
            public class X {
                static class Price {
                    private int amount;
                    Price(int amount) { this.amount = amount; }
                    int amount() { return amount; }
                    void discount() { amount = amount / 2; }
                }

                public static class Catalogue {
                    private final Map<String, Price> prices;

                    @IgnoreModifications
                    private final List<String> lookups = new ArrayList<>();

                    public Catalogue(Map<String, Price> prices) {
                        this.prices = Map.copyOf(prices);
                    }

                    public Price price(String item) {
                        lookups.add(item);
                        return prices.get(item);
                    }
                }

                // the same, without the annotation
                public static class Catalogue0 {
                    private final Map<String, Price> prices;
                    private final List<String> lookups = new ArrayList<>();

                    public Catalogue0(Map<String, Price> prices) {
                        this.prices = Map.copyOf(prices);
                    }

                    public Price price(String item) {
                        lookups.add(item);
                        return prices.get(item);
                    }
                }

                public static class Escape {
                    private final Price base = new Price(10);

                    @IgnoreModifications
                    private final List<Price> recent = new ArrayList<>();

                    public void touch() {
                        recent.add(base);
                        recent.get(0).discount();
                    }
                }

                static class TypeNode { }
                static class MethodNode { }

                public static class FieldNode {
                    private final String name;
                    private final TypeNode type;

                    @IgnoreModifications
                    private final Map<String, Object> analysis = new HashMap<>();

                    public FieldNode(String name, TypeNode type) {
                        this.name = name;
                        this.type = type;
                    }

                    public void record(String property, Object value) {
                        analysis.put(property, value);
                    }
                }

                static void use(FieldNode fieldNode, MethodNode methodNode) {
                    fieldNode.record("assignedIn", methodNode);
                }
            }
            """;

    @DisplayName("§050, ignoring modifications")
    @Test
    public void test() throws IOException {
        Map<String, String> actual = verdicts("a.b.X", INPUT);
        assertVerdicts(actual,
                "Catalogue = @Immutable(hc=true) @Container",
                "Catalogue.lookups = @NotModified",
                // DEVIATION: the book says @NotModified; a modifying call on an ignored field leaves the field and
                // the type unmodified, but the method itself is computed @Modified
                "Catalogue.price() = @Modified",
                // without the annotation
                "Catalogue0 = @FinalFields",
                "Catalogue0.lookups = @Modified",
                // the escape: the modification made through the ignored field lands on 'base'
                "Escape.base = @Modified",
                "Escape = @FinalFields",
                "FieldNode = @Immutable(hc=true) @Container",
                "FieldNode.analysis = @NotModified",
                // DEVIATION: the book says @NotModified, as for Catalogue.price()
                "FieldNode.record() = @Modified");
    }
}
