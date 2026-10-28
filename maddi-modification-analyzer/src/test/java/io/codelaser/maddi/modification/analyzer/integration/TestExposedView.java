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

import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A getter that hands out a view of a field shares the field's mutable object with the caller, exactly as one that
 * hands out the field itself: the field, and with it the type, is dependent, and the type cannot be immutable.
 */
public class TestExposedView extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            class X {
                static final class Direct {
                    private final List<String> items = new ArrayList<>();
                    public List<String> get() { return items; }
                }
                static final class View {
                    private final List<String> items = new ArrayList<>();
                    public List<String> get() { return items.subList(0, 10); }
                }
                static final class Copy {
                    private final List<String> items = new ArrayList<>();
                    public List<String> get() { return new ArrayList<>(items); }
                }
            }
            """;

    private static String verdict(TypeInfo t) {
        FieldInfo items = t.getFieldByName("items", true);
        MethodInfo get = t.findUniqueMethod("get", 0);
        return "field " + items.analysis().getOrNull(INDEPENDENT_FIELD, ValueImpl.IndependentImpl.class)
               + ", get " + get.analysis().getOrNull(INDEPENDENT_METHOD, ValueImpl.IndependentImpl.class)
               + ", type " + t.analysis().getOrNull(INDEPENDENT_TYPE, ValueImpl.IndependentImpl.class)
               + " " + t.analysis().getOrNull(IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.class);
    }

    @DisplayName("a getter handing out a subList of a field makes the field and the type dependent")
    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(X);
        analyzer.go(ao);
        assertEquals("field @Dependent, get @Dependent, type @Dependent @FinalFields",
                verdict(X.findSubType("Direct")));
        // subList is a view: modifying it modifies items, so this is the same exposure as Direct
        assertEquals("field @Dependent, get @Dependent, type @Dependent @FinalFields",
                verdict(X.findSubType("View")));
        assertEquals("field @Independent, get @Independent, type @Independent @Immutable(hc=true)",
                verdict(X.findSubType("Copy")));
    }
}
