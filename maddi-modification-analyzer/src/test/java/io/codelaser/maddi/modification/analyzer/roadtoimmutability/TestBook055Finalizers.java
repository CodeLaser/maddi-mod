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
 * The examples of road-to-immutability §055, "Finalizers".
 */
public class TestBook055Finalizers extends BookTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class X {
                public static class Names {
                    private final List<String> names;

                    public Names(List<String> names) {
                        this.names = List.copyOf(names);
                    }

                    public List<String> upperCase() {
                        return names.stream()
                            .map(String::toUpperCase)
                            .toList();
                    }
                }

                public static class Report {
                    private final List<String> lines;

                    private Report(List<String> lines) {
                        this.lines = lines;
                    }

                    static class Builder {
                        private final List<String> lines = new ArrayList<>();

                        Builder add(String line) {
                            lines.add(line);
                            return this;
                        }

                        Report build() {
                            return new Report(lines);
                        }
                    }
                }

                static Report use() {
                    return new Report.Builder().add("a").add("b").build();
                }
            }
            """;

    @DisplayName("§055, finalizers")
    @Test
    public void test() throws IOException {
        Map<String, String> actual = verdicts("a.b.X", INPUT);
        assertVerdicts(actual,
                "Names = @Immutable(hc=true) @Container",
                "Names.upperCase() = @NotModified",
                "Builder.lines = @Owned",
                "Builder.add() = @Modified @Fluent",
                "Builder.build() = @Finalizer",
                "Report = @Immutable(hc=true)",
                "Report.lines = @Owned");
    }
}
