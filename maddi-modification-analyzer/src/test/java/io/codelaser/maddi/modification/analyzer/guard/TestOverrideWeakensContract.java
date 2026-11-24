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

package io.codelaser.maddi.modification.analyzer.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.analysis.Message;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * The rule of road-to-immutability 050 §Inheritance: an override may go from {@code @Modified} to
 * {@code @NotModified}, never from {@code @NotModified} to {@code @Modified}.
 * <p>
 * It holds against a contract written in source, with or without a body on the override, and against the contracts
 * of the annotated APIs: the book's {@code MyString} example, and its claim that a modifying {@code equals()} or
 * {@code toString()} is blocked in any class.
 */
public class TestOverrideWeakensContract extends CommonTest {

    @Language("java")
    private static final String SOURCE_PARENT = """
            package a.b;
            import io.codelaser.maddi.annotation.Modified;
            import io.codelaser.maddi.annotation.NotModified;

            public class Y {
                static class Base {
                    protected int n;
                    @NotModified
                    public int get() { return n; }
                    @Modified
                    public int next() { return ++n; }
                }
                static class Stronger extends Base {
                    @Override
                    @NotModified
                    public int next() { return n + 1; }
                }
                static abstract class AbstractWeaker extends Base {
                    @Override
                    @Modified
                    public abstract int get();
                }
            }
            """;

    @DisplayName("@Modified to @NotModified is accepted; @NotModified to @Modified on a bodiless override is a conflict")
    @Test
    public void sourceParent() throws IOException {
        Run run = analyze("a.b.Y", SOURCE_PARENT);
        assertEquals(List.of("contract-conflict @ a.b.Y.AbstractWeaker.get()"), describe(run.messages()));
        assertTrue(run.messages().getFirst().level().isError());
        assertTrue(nonModifying(run, "Stronger", "next"));
    }

    @Language("java")
    private static final String CONCRETE_WEAKER = """
            package a.b;
            import io.codelaser.maddi.annotation.Modified;
            import io.codelaser.maddi.annotation.NotModified;

            public class Z {
                static class Base {
                    protected int n;
                    @NotModified
                    public int get() { return n; }
                }
                static class ConcreteWeaker extends Base {
                    @Override
                    @Modified
                    public int get() { return n + 1; }
                }
            }
            """;

    @DisplayName("@NotModified to @Modified on an override with a body is a conflict too")
    @Test
    public void concreteWeaker() throws IOException {
        Run run = analyze("a.b.Z", CONCRETE_WEAKER);
        assertEquals(List.of("contract-conflict @ a.b.Z.ConcreteWeaker.get()"), describe(run.messages()));
        // neither contract is applied: the body decides
        assertTrue(nonModifying(run, "ConcreteWeaker", "get"));
    }

    // the book's MyString implements List<String>; AbstractList keeps the example small, and isEmpty() stands in for
    // getFirst()
    @Language("java")
    private static final String LIBRARY_PARENT = """
            package a.b;
            import io.codelaser.maddi.annotation.Modified;
            import io.codelaser.maddi.annotation.NotModified;
            import java.util.AbstractList;

            public class X {
                static abstract class MyString extends AbstractList<String> {
                    private String string = "";

                    @Override
                    public int size() {
                        string = string + "!";
                        return string.length();
                    }

                    @Override
                    @Modified
                    public abstract boolean isEmpty();

                    @Override
                    @NotModified
                    public abstract boolean add(String s);
                }

                static class Plain {
                    private int n;

                    @Override
                    public String toString() { n++; return "n=" + n; }

                    @Override
                    public boolean equals(Object o) { n++; return o == this; }
                }
            }
            """;

    @DisplayName("the JDK's @NotModified contracts are enforced on source overrides")
    @Test
    public void libraryParent() throws IOException {
        Run run = analyze("a.b.X", LIBRARY_PARENT);
        assertEquals(List.of(
                "contract-conflict @ a.b.X.MyString.isEmpty()",
                "contract-violation @ a.b.X.MyString.size()",
                "contract-violation @ a.b.X.Plain.equals(Object)",
                "contract-violation @ a.b.X.Plain.toString()"), describe(run.messages()));
        // the authored conflict is an error; a computed violation of a library contract is a warning
        assertTrue(run.messages().stream().allMatch(m -> m.level().isError()
                                                         == m.category().toString().startsWith("contract-conflict")));
        assertTrue(!nonModifying(run, "MyString", "size"));
        assertTrue(!nonModifying(run, "Plain", "toString"));
        assertTrue(!nonModifying(run, "Plain", "equals"));
        // the allowed direction
        assertTrue(nonModifying(run, "MyString", "add"));
    }

    private record Run(TypeInfo typeInfo, List<Message> messages) {
    }

    private Run analyze(String fqn, String source) throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo typeInfo = bundle.javaInspector().parse(fqn, source);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(typeInfo);
        IteratingAnalyzer analyzer = new IteratingAnalyzerImpl(bundle.javaInspector(),
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(10).setGuardContracts(true)
                        .setModificationViaReachability(true).build());
        analyzer.analyze(analysisOrder);
        return new Run(typeInfo, analyzer.messages());
    }

    private static boolean nonModifying(Run run, String subType, String method) {
        Value.Bool value = run.typeInfo().findSubType(subType).methodStream()
                .filter(m -> method.equals(m.name())).findFirst().orElseThrow()
                .analysis().getOrNull(PropertyImpl.NON_MODIFYING_METHOD, ValueImpl.BoolImpl.class);
        return value != null && value.isTrue();
    }

    private static List<String> describe(List<Message> messages) {
        return messages.stream().map(m -> m.category() + " @ " + m.info().fullyQualifiedName()).sorted().toList();
    }
}
